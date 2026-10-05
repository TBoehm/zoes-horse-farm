package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.application.PreviousRun
import app.zoeshorsefarm.application.SaveEnv
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.audio.Audio
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.i18n.detectLang
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.ErrorLog
import app.zoeshorsefarm.platform.GameKey
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.platform.ManualAppLifecycle
import app.zoeshorsefarm.platform.appVersionOf
import app.zoeshorsefarm.platform.bindCrashGuardLifecycle
import app.zoeshorsefarm.platform.describeError
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.UiScheduler
import app.zoeshorsefarm.presentation.audio.AudioSoundPort
import app.zoeshorsefarm.presentation.audio.AudioWiring
import app.zoeshorsefarm.presentation.audio.toAudioSettings
import app.zoeshorsefarm.presentation.nav.AppNavigator
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.notice.FullNotice
import app.zoeshorsefarm.presentation.notice.NoticeKind
import app.zoeshorsefarm.presentation.notice.RotateNotice
import app.zoeshorsefarm.presentation.notice.SaveNotice
import app.zoeshorsefarm.presentation.profile.BadgeToastQueue
import app.zoeshorsefarm.presentation.registerScreens
import app.zoeshorsefarm.presentation.startRoute
import app.zoeshorsefarm.storage.LocalStore

/**
 * The composition root of the native app (web: `main.js`): creates the save game, the services, the
 * crash guard, the texts, the sound, the input mode, the navigation and the bridge to the 3D engine,
 * and gives the platform shell one small surface to drive it:
 *
 * - [start] once, after the UI listens to [navigator];
 * - [onFrame] on every display frame (ticks the UI timers and draws the ride);
 * - [onSurfaceCreated] / [onSurfaceResized] / [onSurfaceDestroyed] for the 3D surface;
 * - [onLifecycle], [onViewportChanged], [onTouch], [onKey], [onFocusLost];
 * - [dispose] at the end.
 *
 * It contains no rules: ports (store, clock, rng) are created here and handed to the layers. Not thread
 * safe: call everything from the one thread that draws (the UI / render thread).
 */
@Suppress("TooManyFunctions") // the entry points of the shells, one small function per platform event
class ZoesHorseFarmApp(
    private val platform: AppPlatform,
) {
    /** The last errors for the debug box; the shell feeds uncaught errors with [recordError]. */
    val errorLog = ErrorLog(now = { platform.secondsClock.nowSeconds() })

    val i18n = I18n(onMissing = { errorLog.add("Missing text: $it") })

    /** The save game on the device. */
    val store = LocalStore(platform.keyValueBackend, env = SaveEnv(detectLang(platform.preferredLanguages)))

    /** The only writer of the settings section. */
    val settings = SettingsService(store)

    /** Foreground or background: the shell reports it with [onLifecycle]. */
    val lifecycle = ManualAppLifecycle(platform.initialAppState)

    val inputMode = InputMode(platform.inputDevice)

    /** Delayed tasks of the screens; ticked by [onFrame]. */
    val scheduler = UiScheduler(platform.clock)

    /** The screen stack; the UI shows `currentModel` and listens to `onScreen`. */
    val navigator = AppNavigator(i18n)

    /** The sound output of the game (unlocked by the first touch, see [onTouch]). */
    val audio: Audio

    /** Toasts of awarded badges, shown above every screen. */
    val badgeToasts: BadgeToastQueue

    /** The context every screen model shares. */
    val context: AppContext

    /** The "turn the device" notice (touch mode, portrait); the UI shows it while `blocked`. */
    val rotateNotice: RotateNotice

    /** The "saving is not possible" note; the UI shows it while `visible`. */
    val saveNotice = SaveNotice(i18n)

    /** Fires when [notice] changes. */
    val noticeChanges = Changes()

    /**
     * A full-page notice that replaces the whole UI: "no 3D" when the device has no graphics backend, an
     * error notice when the app could not start (web: `renderNo3dNotice`, `renderErrorNotice`).
     */
    var notice: FullNotice? = null
        private set

    /** What the crash guard found about the previous run (the debug box names it). */
    val previousRun: PreviousRun

    private val crashGuard: CrashGuard
    private val bridge: EngineBridge
    private val audioWiring: AudioWiring
    private var viewportHeightDp = 0
    private var started = false
    private var disposed = false
    private val subscriptions = ArrayList<() -> Unit>()

    init {
        // the start language is the saved one (the first start: the language of the system)
        i18n.setLang(settings.get().lang)
        store.flush()
        crashGuard =
            CrashGuard(
                store = store,
                settings = settings,
                clock = platform.clock,
                decide = ::decideAfterCrash,
                tabId = null, // one instance per install, no id that survives a crash (see :adapters:platform)
            )
        // unclean exit during the 3D picture: the previous run counts like a lost device. Checked before
        // the first screen so that the engine starts with the corrected level.
        previousRun = crashGuard.checkPreviousRun()
        // "Automatic" selected anew: levels that crashed on this device may be tried again
        subscriptions += settings.onAutoSelected { crashGuard.clearBlockedLevels() }
        subscriptions += bindCrashGuardLifecycle(lifecycle, crashGuard::markBackground, crashGuard::resume)

        badgeToasts = BadgeToastQueue(i18n, scheduler, viewportHeight = { viewportHeightDp })
        audio = Audio(settings.get().toAudioSettings(), platform.audio)
        context =
            AppContext(
                store = store,
                settings = settings,
                inputMode = inputMode,
                clock = platform.clock,
                i18n = i18n,
                navigator = navigator,
                scheduler = scheduler,
                lifecycle = lifecycle,
                badgeToasts = badgeToasts,
                version = appVersionOf(platform.appVersion),
                sound = AudioSoundPort(audio),
                timeZone = platform.timeZone,
            )
        audioWiring = AudioWiring(context)
        bridge = EngineBridge(context, platform, crashGuard, previousRun, errorLog, platform.random, ::showNoGraphics)
        registerScreens(context, platform.random, bridge)
        // the ride gets its own factory: the bridge wires every ride model to the engine
        navigator.register(Route.Ride.NAME) { bridge.createRide(it as Route.Ride) }
        subscriptions += navigator.onScreen(bridge::onScreenChanged)

        rotateNotice = RotateNotice(i18n, inputMode, 0, 0, onBlockedChange = navigator::emitRotateBlocked)
        if (store.shouldShowSaveNotice()) saveNotice.show()
        store.onSaveFailed { if (store.shouldShowSaveNotice()) saveNotice.show() }
    }

    private fun showNoGraphics() = showNotice(NoticeKind.NO_3D)

    private fun showNotice(kind: NoticeKind) {
        notice = FullNotice(kind, i18n)
        noticeChanges.fire()
    }

    // ---- lifecycle of the app ----

    /** Shows the first screen of the start sequence: name question, controls help or main menu. */
    @Suppress("TooGenericExceptionCaught") // an unexpected start error shows the child-friendly notice
    fun start() {
        if (started || disposed) return
        started = true
        try {
            navigator.go(startRoute(store))
        } catch (error: Throwable) {
            recordError(error)
            showNotice(NoticeKind.ERROR)
        }
    }

    /** Stops everything: screens, engine, backend, sound. The app object is not usable afterwards. */
    fun dispose() {
        if (disposed) return
        disposed = true
        navigator.dispose()
        bridge.dispose()
        audioWiring.dispose()
        audio.dispose()
        scheduler.clear()
        rotateNotice.dispose()
        subscriptions.forEach { it() }
        subscriptions.clear()
        platform.shutdown()
    }

    // ---- frame loop ----

    /**
     * One display frame at [nowSeconds] (any monotonic clock): ticks the UI timers and, while a ride is
     * on screen, steps it and draws the world.
     */
    fun onFrame(nowSeconds: Double) {
        if (disposed) return
        tickScheduler()
        bridge.frame(nowSeconds)
    }

    // a task of a screen that throws must not take the frame loop of the shell down
    @Suppress("TooGenericExceptionCaught")
    private fun tickScheduler() {
        try {
            scheduler.tick()
        } catch (error: Throwable) {
            recordError(error)
        }
    }

    /** The text of the debug box, null unless [AppPlatform.debug] is on and a ride built the 3D side. */
    val debugText: String? get() = bridge.debugText

    // ---- surface ----

    /**
     * The shell has a drawing surface (Android `Surface`, iOS `CAMetalLayer`) of [widthPx] x [heightPx]
     * pixels and [density] pixels per dp. Send it as early as possible: a ride that starts without one
     * waits paused (as for a lost device). Call it again after [onSurfaceDestroyed].
     */
    fun onSurfaceCreated(
        surface: PlatformSurface,
        widthPx: Int,
        heightPx: Int,
        density: Double,
    ) = bridge.onSurfaceCreated(surface, SurfaceSize(widthPx, heightPx, density))

    /** The surface changed its size or density (rotation, split screen). */
    fun onSurfaceResized(
        widthPx: Int,
        heightPx: Int,
        density: Double,
    ) = bridge.onSurfaceResized(SurfaceSize(widthPx, heightPx, density))

    /**
     * The surface goes away; nothing may be drawn on it after this returns. On a switch to the
     * background call [onLifecycle] with BACKGROUND first: the engine must know the app is hidden before
     * the device is reported lost, or the level would be lowered for an ordinary app switch.
     */
    fun onSurfaceDestroyed() = bridge.onSurfaceDestroyed()

    // ---- events of the platform ----

    /** The app moved to the foreground or the background (the ride pauses, the sound mutes). */
    fun onLifecycle(state: AppState) {
        lifecycle.update(state)
        if (state == AppState.FOREGROUND) audio.unlock() // the system may have stopped the output
    }

    /** The size of the window in dp: the rotate notice and the toasts follow it. */
    fun onViewportChanged(
        widthDp: Int,
        heightDp: Int,
    ) {
        viewportHeightDp = heightDp
        rotateNotice.update(widthDp, heightDp)
        badgeToasts.relayout()
    }

    /** A finger touched the screen: touch mode on hybrid devices, and the first touch unlocks the sound. */
    fun onTouch() {
        inputMode.onTouch()
        audio.onUserInteraction()
    }

    /**
     * A hardware key event ([key] null: not a game key). Returns true when the game used the key (the
     * shell then does not pass it on). Not for typing into text fields: pass [inEditableField] for those.
     */
    fun onKey(
        key: GameKey?,
        down: Boolean,
        repeat: Boolean = false,
        inEditableField: Boolean = false,
    ): Boolean {
        if (down) inputMode.onKey(key, inEditableField)
        val ride = bridge.rideModel
        return when {
            key == null || inEditableField || ride == null -> false
            down -> ride.onKeyDown(key, repeat) || ride.input.keyboard.onKeyDown(key, repeat)
            else -> false.also { ride.input.keyboard.onKeyUp(key) }
        }
    }

    /** The window lost the focus (split screen, system dialog): the ride pauses and held keys are released. */
    fun onFocusLost() {
        val ride = bridge.rideModel ?: return
        ride.input.keyboard.onFocusLost()
        ride.onFocusLost()
    }

    /** An error the shell caught (uncaught exception handler): goes to the debug box and the log sink. */
    fun recordError(error: Any?) {
        errorLog.record(error)
        platform.logSink(describeError(error))
    }
}
