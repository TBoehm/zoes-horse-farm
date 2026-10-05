package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.RideCommand
import app.zoeshorsefarm.application.RideSession
import app.zoeshorsefarm.application.RideView
import app.zoeshorsefarm.application.Rng
import app.zoeshorsefarm.application.canStart
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.modes.createRideMode
import app.zoeshorsefarm.audio.Cancellable
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.input.Input
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.courses.CourseHudPresenter
import app.zoeshorsefarm.presentation.courses.CourseHudState
import app.zoeshorsefarm.presentation.nav.RedirectModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.nav.routeForScreenName
import app.zoeshorsefarm.presentation.nav.toRoute

// Ride screen without the 3D engine (web: `screens/ride-screen.js`). All ride rules live in the
// application's RideSession: this model calls `session.step`, shows what the session says and executes
// the commands it returns. It owns what is not a pixel: the pause menu and its state machine
// (auto-pause, lost graphics device with its watchdog), the feedback toast with its timer, the
// frame-rate display, the graphics hints, the touch/keyboard input and the mapping of session commands
// to input, sound, toasts and navigation. The engine adapter draws the world and calls `frame` once per
// frame; see [RideEnginePort] for what it provides.

private const val FEEDBACK_VISIBLE_S = 2.0
private const val HINT_VISIBLE_S = 5.0 // the "graphics too high" hint is longer than a jump message

// How long the model waits for the graphics device to come back before it asks to restart the view
// (some systems stop restoring after repeated losses). A technical value, not a game value.
private const val CONTEXT_RESTORE_TIMEOUT_MS = 8000L

private const val ESCAPE_CODE = "Escape"

/** The buttons of the pause menu, in the order they are shown. */
enum class PauseAction {
    /** Only shown when the lost graphics device does not come back. */
    RELOAD,
    RESUME,
    RESTART,
    QUIT,
    SETTINGS,
    HELP,
}

/** A button of the pause menu; [enabled] is false for "Continue" while the graphics are lost. */
data class PauseButton(
    val action: PauseAction,
    val label: String,
    val enabled: Boolean,
)

/** What the frame loop should do after [RideScreenModel.frame]. */
enum class FrameResult {
    /** Paused (or another screen is on top): only draw the scene, the graphics governor measures nothing. */
    PAUSED,

    /** The ride was paused or left by this frame: nothing more to do. */
    STOP,

    /** The ride ran: update the world from [RideScreenModel.view] and draw. */
    RUNNING,
}

/**
 * The model of the ride screen. [route] says free ride or course; [rng] is the random source of the
 * ride (the application layer never draws random numbers itself).
 *
 * The UI shows [paused], [pauseButtons], [feedbackText], [fpsText], [hudState] and the touch controls
 * (`input.touch`), reacts to [changes], and calls the actions. Not thread safe: UI thread only.
 */
@Suppress("TooManyFunctions") // one small function per event of the ride screen
class RideScreenModel(
    private val ctx: AppContext,
    route: Route.Ride,
    rng: Rng,
    private val engine: RideEnginePort,
    private val restoreTimeoutMs: Long = CONTEXT_RESTORE_TIMEOUT_MS,
) : ScreenModel {
    /** Changes whenever something the UI shows changes (not once per frame). */
    val changes = Changes()

    /** The ride use case; the engine adapter reads obstacles, flags and the view from it. */
    val session = RideSession(createRideMode(route.mode, route.courseId), ctx.store, ctx.clock, rng)

    /** The merged keyboard and touch input; the Compose touch layer feeds `input.touch`. */
    val input = Input(touchMode = ctx.inputMode.touch, isActive = { inputActive })

    /** The HUD of the mode (course only); null for a free ride. */
    val hud: CourseHudPresenter? = if (session.view.hud != null) CourseHudPresenter(ctx.i18n) else null

    /** The values of the HUD chips, refreshed every frame (a new object only when a value changed). */
    var hudState: CourseHudState? = null
        private set

    override val music = false
    override val rerenderOnLang = false

    var paused: Boolean = false
        private set

    private var contextLost = false
    private var restoreOverdue = false // the lost device did not come back in time: ask for a reload
    private var watchdog: Cancellable? = null
    private var destroyed = false

    private var feedbackKey: String? = null
    private var feedbackTimer = 0.0
    var feedbackIsHint: Boolean = false
        private set

    private val fpsMeter = FpsMeter()
    private var showFps = ctx.settings.get().showFps
    private var autoGraphics = ctx.settings.get().graphicsAuto
    private var fps: Int? = null

    private val simInput = SimInput()
    private val subscriptions = ArrayList<() -> Unit>()

    /** The keyboard only listens while this ride is the top screen and not paused. */
    val inputActive: Boolean get() = !paused && isTop

    private val isTop: Boolean get() = ctx.navigator.current is Route.Ride

    val pauseTitle: String get() = ctx.t("pause.title")

    /** "Esc = Pause": shown only without touch controls. */
    val pauseHint: String get() = ctx.t("ride.pauseHint")
    val showPauseHint: Boolean get() = !ctx.inputMode.touch

    /** The pause menu buttons; "Reload" appears only when the graphics do not come back. */
    val pauseButtons: List<PauseButton>
        get() =
            buildList {
                if (restoreOverdue) add(PauseButton(PauseAction.RELOAD, ctx.t("pause.reload"), true))
                add(PauseButton(PauseAction.RESUME, ctx.t("pause.resume"), enabled = !contextLost))
                add(PauseButton(PauseAction.RESTART, ctx.t("pause.restart"), true))
                add(PauseButton(PauseAction.QUIT, ctx.t(session.quitLabelKey), true))
                add(PauseButton(PauseAction.SETTINGS, ctx.t("pause.settings"), true))
                add(PauseButton(PauseAction.HELP, ctx.t("pause.help"), true))
            }

    /** The button that gets the focus when the menu opens: the first one that can be used. */
    val focusedPauseAction: PauseAction? get() = pauseButtons.firstOrNull { it.enabled }?.action

    /** The note under the pause title while the graphics are lost, else null. */
    val lostNote: String?
        get() = if (contextLost) ctx.t(if (restoreOverdue) "pause.graphicsReload" else "pause.graphicsLost") else null

    /** The toast text (jump feedback or a hint), or null while nothing is shown. */
    val feedbackText: String? get() = feedbackKey?.let { ctx.t(it) }

    /** The frame-rate display text, or null when it is switched off. */
    val fpsText: String?
        get() =
            if (showFps) {
                formatFpsText(fps, engine.graphicsLevel, autoGraphics) { key, params -> ctx.t(key, params) }
            } else {
                null
            }

    /**
     * The view of the ride for the 3D scene. It is refreshed on every read: read it once per frame,
     * after [frame] returned [FrameResult.RUNNING]. Do not keep it.
     */
    val view: RideView get() = session.view

    /**
     * A jump is in progress or an obstacle is being approached: the graphics automatic does not
     * start a level step then (rule 4). Pass it to the governor.
     */
    val busy: Boolean
        get() {
            val v = session.view
            return v.jumping || v.approaching
        }

    init {
        subscriptions +=
            ctx.settings.onChange { s ->
                if (s.showFps != showFps) {
                    // switched on or off: do not show the value of the last time
                    fpsMeter.reset()
                    fps = null
                }
                showFps = s.showFps
                autoGraphics = s.graphicsAuto
                changes.fire()
            }
        // the line labels are translated texts too; the rest of the screen reads its texts when shown
        subscriptions +=
            ctx.i18n.onLangChange {
                applyLines()
                changes.fire()
            }
        // a switch between touch and keyboard ends an active gallop (rules 9, 11)
        subscriptions += ctx.inputMode.onChange { input.onTouchModeChange(it) }
        subscriptions += engine.onContextLost { onContextLost() }
        subscriptions += engine.onContextRestored { onContextRestored() }
        // auto-pause when the app goes to the background (rules 12, 38) or the rotate notice blocks
        subscriptions += ctx.lifecycle.onChange { if (it == AppState.BACKGROUND) setPaused(true) }
        subscriptions += ctx.navigator.onRotateBlocked { if (it) setPaused(true) }

        engine.setCameraMode(ctx.settings.get().camera)
        restart()
        // The device may have been lost while no ride was running: start paused then
        if (engine.contextLost) onContextLost() else showContextLossHint() // a loss between two rides may leave a hint
        showCrashHint()
    }

    private fun canHintLowerLevel() = !autoGraphics && engine.graphicsLevel != GraphicsLevel.LOW

    // ---- feedback ----

    private fun showFeedback(
        key: String,
        long: Boolean = false,
    ) {
        feedbackKey = key
        feedbackIsHint = long
        feedbackTimer = if (long) HINT_VISIBLE_S else FEEDBACK_VISIBLE_S
        changes.fire()
    }

    /**
     * After a lost graphics device with a manual level above low (rule 4): the toast tells the player
     * to pick a lower level. Shown once the ride goes on (the pause menu covers the screen before);
     * the engine hands it out only once per loss.
     */
    private fun showContextLossHint() {
        if (engine.takeGraphicsHint()) showFeedback("ride.graphicsContextLost", long = true)
    }

    /**
     * After an unexpected end of the previous run with a manual level above low (rule 4, crash guard):
     * the same hint, once. Not when the player has lowered the level since.
     */
    private fun showCrashHint() {
        if (engine.takeCrashHint() && canHintLowerLevel()) showFeedback("ride.graphicsContextLost", long = true)
    }

    // ---- lines and HUD ----

    private fun applyLines() {
        val lines = session.view.lines
        engine.showLines(
            lines,
            lines?.let { ctx.t(it.labelKeys.start) }.orEmpty(),
            lines?.let { ctx.t(it.labelKeys.finish) }.orEmpty(),
        )
    }

    private fun refreshHud(view: RideView) {
        val presenter = hud ?: return
        val model = view.hud ?: return
        val next = presenter.update(model)
        if (next !== hudState) {
            hudState = next
            changes.fire()
        }
    }

    // ---- commands of the session ----

    /** Executes the commands of the session; returns true if the screen is being left. */
    internal fun execute(commands: List<RideCommand>): Boolean {
        for (i in commands.indices) {
            when (val cmd = commands[i]) {
                RideCommand.EndGallop -> {
                    input.endGallop()
                }

                RideCommand.ResetTouchGallop -> {
                    input.resetTouchGallop()
                }

                is RideCommand.Feedback -> {
                    showFeedback(cmd.key)
                }

                is RideCommand.Badges -> {
                    cmd.ids.forEach { ctx.badgeToasts.show(it) }
                }

                is RideCommand.Sound -> {
                    ctx.sound.play(cmd.sound)
                }

                is RideCommand.Finished -> {
                    input.resetTouchGallop()
                    ctx.navigator.go(cmd.toRoute())
                    return true
                }
            }
        }
        return false
    }

    /** "Start again" (and the first start): a new ride from the start pose. */
    fun restart() {
        execute(session.restart())
        input.clearEdges()
        applyLines()
        refreshHud(session.view)
        engine.onRideRestarted()
    }

    // ---- pause ----

    private fun setPaused(next: Boolean) {
        if (paused == next) return
        if (!next && contextLost) return
        paused = next
        input.clearEdges()
        engine.interruptMeasuring()
        ctx.sound.setPaused(paused)
        // the hint waits for the pause menu to close, it would be hidden behind it
        if (!paused) showContextLossHint()
        changes.fire()
    }

    /** The window lost the focus (split screen, system dialog): the ride pauses. */
    fun onFocusLost() = setPaused(true)

    /**
     * A hardware key press while this screen is shown. Escape continues a paused ride (key repeat is
     * ignored). Returns true when the key was used.
     */
    fun onKeyDown(
        code: String,
        repeat: Boolean,
    ): Boolean {
        if (!paused || !isTop || code != ESCAPE_CODE) return false
        if (!repeat) setPaused(false)
        return true
    }

    /** A button of the pause menu was pressed. */
    fun onPauseAction(action: PauseAction) {
        if (!paused) return
        when (action) {
            PauseAction.RELOAD -> {
                engine.reloadGraphics()
            }

            PauseAction.RESUME -> {
                setPaused(false)
            }

            PauseAction.RESTART -> {
                restart()
                setPaused(false)
            }

            PauseAction.QUIT -> {
                ctx.navigator.go(routeForScreenName(session.quitScreen))
            }

            PauseAction.SETTINGS -> {
                ctx.navigator.push(Route.Settings(fromPause = true))
            }

            PauseAction.HELP -> {
                ctx.navigator.push(Route.ControlsHelp(fromPause = true))
            }
        }
    }

    // ---- lost graphics device (rule 4) ----

    private fun onContextLost() {
        contextLost = true
        restoreOverdue = false
        setPaused(true)
        watchdog?.cancel()
        watchdog =
            ctx.scheduler.postDelayed(restoreTimeoutMs) {
                watchdog = null
                if (destroyed) return@postDelayed
                restoreOverdue = true
                changes.fire()
            }
        changes.fire()
    }

    private fun onContextRestored() {
        contextLost = false
        watchdog?.cancel()
        watchdog = null
        restoreOverdue = false
        engine.interruptMeasuring()
        changes.fire()
    }

    /** The engine's hoof beat (footfall of the horse): a sound unless paused. */
    fun onFootfall(gait: String) {
        if (!paused) ctx.sound.hoof(gait)
    }

    // ---- frame ----

    /**
     * One frame of the ride, called by the engine's frame loop. [dt] is the game time step, [rawDt] the
     * real time (the toast and the frame-rate display must not stay longer at a low frame rate).
     * The scene is also drawn while paused.
     */
    fun frame(
        dt: Double,
        rawDt: Double,
    ): FrameResult {
        if (showFps) {
            val value = fpsMeter.frame(rawDt) // restarts itself after a suspended app
            if (value != null) {
                fps = value
                changes.fire()
            }
        }
        return if (paused || !isTop) FrameResult.PAUSED else rideFrame(dt, rawDt)
    }

    // the part of a frame in which the ride runs
    private fun rideFrame(
        dt: Double,
        rawDt: Double,
    ): FrameResult {
        val inp = input.poll()
        if (inp.pause) {
            setPaused(true)
            return FrameResult.STOP
        }
        if (inp.camera) ctx.settings.setCamera(engine.toggleCamera())
        simInput.set(inp.steer, inp.throttle, inp.gallop, inp.jump)
        if (execute(session.step(dt, simInput).commands)) return FrameResult.STOP

        if (feedbackTimer > 0) {
            feedbackTimer -= rawDt
            if (feedbackTimer <= 0) {
                feedbackKey = null
                changes.fire()
            }
        }
        refreshHud(session.view)
        // a manual level that is too high: one hint per ride, the level stays (rule 4)
        val measuring = ctx.lifecycle.state == AppState.FOREGROUND && canHintLowerLevel()
        if (engine.lowFpsHintFrame(rawDt, measuring)) showFeedback("ride.graphicsTooHigh", long = true)
        return FrameResult.RUNNING
    }

    override fun onShow() {
        // back from the settings: still paused, the UI focuses the pause menu again
        changes.fire()
    }

    override fun destroy() {
        destroyed = true
        watchdog?.cancel()
        watchdog = null
        subscriptions.forEach { it() }
        subscriptions.clear()
        session.dispose()
        ctx.sound.setPaused(false)
        changes.clear()
    }
}

/**
 * The ride screen for [route], or a redirect to the course selection for a locked course: a locked
 * course cannot be ridden (the course card is only disabled).
 */
fun createRideScreen(
    ctx: AppContext,
    route: Route.Ride,
    rng: Rng,
    engine: RideEnginePort,
): ScreenModel =
    if (route.mode == RideModeId.COURSE && !canStart(ctx.store, route.courseId ?: -1)) {
        RedirectModel(Route.CourseSelect)
    } else {
        RideScreenModel(ctx, route, rng, engine)
    }
