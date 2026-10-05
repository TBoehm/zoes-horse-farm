package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.modes.AidTarget
import app.zoeshorsefarm.application.modes.CourseHud
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.application.modes.ModeHost
import app.zoeshorsefarm.application.modes.RideFinish
import app.zoeshorsefarm.application.modes.RideFrame
import app.zoeshorsefarm.application.modes.RideMode
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.domain.course.Highlight
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.RidingSim
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.Zone

// Ride session (use case "ride"): owns the riding sim and everything around a single ride that is
// a rule, not a pixel: stepping the sim, rebuilding fallen rails, counting jumps with instant
// badges, the jump aid, feedback and the end of a course ride. The UI adapter only feeds input
// and time, shows `view` and executes the returned commands. No rendering, no platform APIs.

// Shared result for steps without commands/events (read only): no allocation per frame.
private val NO_COMMANDS: List<RideCommand> = emptyList()
private val NO_EVENTS: List<SimEvent> = emptyList()

/**
 * The jump aid of the view: the element, its jump direction and the takeoff zone for the current
 * speed. One instance is reused on every read.
 */
class RideAid internal constructor() {
    var elementId: String = ""
        internal set
    var dir: Int = 1
        internal set
    var zone: Zone? = null
        internal set
}

/**
 * Plain data for the UI to display. The same object (and the same [aid]) is reused on every read:
 * read it, do not keep it. [horse], [rails] and [fallDirs] are live (not copied).
 */
class RideView internal constructor(
    val horse: Horse,
    /** Rails per element id: true = pole up. */
    val rails: Map<String, BooleanArray>,
    /** Direction (+1 / -1) of the last fall per element, for the pole animation of the 3D view. */
    val fallDirs: Map<String, Int>,
) {
    var aid: RideAid? = null
        internal set
    var highlight: Highlight? = null
        internal set
    var finishMarked: Boolean = false
        internal set

    /**
     * A jump is in progress (take-off, flight, landing): the graphics automatic does not start a
     * level step then (rule 4).
     */
    var jumping: Boolean = false
        internal set

    /**
     * An obstacle is being approached (closer than the approach distance, on its line): the
     * automatic does not start a level step then either, a stage can stall the frames for seconds.
     */
    var approaching: Boolean = false
        internal set
    var lines: CourseLines? = null
        internal set
    var hud: CourseHud? = null
        internal set
}

/**
 * Result of one [RideSession.step]; the same object is reused by every step, so read it before the
 * next step. Both lists are the shared empty list when there is nothing (never add to them).
 */
class StepResult internal constructor() {
    var events: List<SimEvent> = NO_EVENTS
        internal set
    var commands: List<RideCommand> = NO_COMMANDS
        internal set
}

/**
 * @param mode strategy from application/modes (free or course)
 * @param store the save game (progress, settings)
 * @param clock badge dates
 * @param rng random source in [0, 1)
 *
 * Commands returned by [restart] and [step]: see [RideCommand]. A course ride that ended is
 * already saved; a `Sound(FINISH_SIGNAL)` comes right before its `Finished` command.
 */
class RideSession(
    private val mode: RideMode,
    private val store: Store,
    private val clock: Clock,
    rng: Rng,
) {
    private class Rebuild(
        val elementId: String,
        var left: Double,
    )

    private val sim = RidingSim(mode.obstacles, mode.rules, rng)
    private var rebuilds = ArrayList<Rebuild>()
    private var finished = false
    private var commands = ArrayList<RideCommand>()

    // Direction (+1 / -1) of the last fall per element, for the pole animation of the 3D view
    private val fallDirs = HashMap<String, Int>()

    // Scratch objects reused every frame (the view is read by the render loop at 60 Hz)
    private val frame = RideFrame(sim.horse)
    private val aidScratch = RideAid()
    private val viewState = RideView(sim.horse, sim.rails, fallDirs)
    private val stepResult = StepResult()
    private val sounds = SoundMapper()

    // The jump aid needs the settings every frame: keep a copy and refresh it on change
    private var settings = store.get(SettingsSection)
    private val stopListening = store.onChange(SettingsSection) { settings = it }

    private fun cancelRebuild(elementId: String) {
        if (rebuilds.isNotEmpty()) rebuilds.removeAll { it.elementId == elementId }
    }

    // What a mode may ask for while it handles events or updates.
    private val host =
        object : ModeHost {
            override fun feedback(key: String) {
                commands.add(RideCommand.Feedback(key))
            }

            override fun rebuildIn(
                elementId: String,
                seconds: Double,
            ) {
                cancelRebuild(elementId)
                rebuilds.add(Rebuild(elementId, seconds))
            }

            override fun rebuildNow(elementId: String) {
                cancelRebuild(elementId)
                sim.rebuild(elementId)
            }

            override fun cancelRebuild(elementId: String) = this@RideSession.cancelRebuild(elementId)
        }

    private fun handleEvents(events: List<SimEvent>) {
        for (i in events.indices) {
            val e = events[i]
            if (e is SimEvent.GallopEnded) commands.add(RideCommand.EndGallop)
            if (e is SimEvent.Landed) {
                // Every jump over an obstacle counts, saved at once (rules 40, 45)
                val ids = recordJump(store, clock)
                if (ids.isNotEmpty()) commands.add(RideCommand.Badges(ids))
            }
            if (e is SimEvent.RailDown) fallDirs[e.elementId] = e.dir
        }
        commands.addAll(sounds.commandsFor(events))
        mode.onEvents(events, host)
    }

    private fun tickRebuilds(dt: Double) {
        if (rebuilds.isEmpty()) return
        var due = false
        for (i in rebuilds.indices) {
            val r = rebuilds[i]
            r.left -= dt
            if (r.left <= 0) {
                sim.rebuild(r.elementId)
                due = true
            }
        }
        if (due) rebuilds.removeAll { it.left <= 0 }
    }

    private fun finish(end: RideFinish) {
        finished = true
        commands.add(RideCommand.Sound(RideSound.FINISH_SIGNAL))
        val outcome = finishRide(store, clock, end.result)
        val params =
            FinishedParams(end.courseId, end.result, outcome.isNewBest, outcome.unlockedCourse, outcome.awarded)
        commands.add(RideCommand.Finished(end.screen, params))
    }

    private fun takeCommands(): List<RideCommand> {
        if (commands.isEmpty()) return NO_COMMANDS
        val out = commands
        commands = ArrayList()
        return out
    }

    /** Startpose, rails up, mode reset. Returns the commands (reset the touch gallop). */
    fun restart(): List<RideCommand> {
        rebuilds = ArrayList()
        finished = false
        sounds.reset()
        fallDirs.clear()
        val start = mode.startPose()
        sim.reset(start.pose, start.speed, start.gallop)
        sim.rebuildAll()
        mode.onRestart()
        commands = arrayListOf(RideCommand.ResetTouchGallop)
        return takeCommands()
    }

    /**
     * One simulation step of [dt] seconds with the [input] (steer, throttle, gallop, jump). The
     * result is reused by the next step.
     */
    fun step(
        dt: Double,
        input: SimInput,
    ): StepResult {
        if (finished) {
            stepResult.events = NO_EVENTS
            stepResult.commands = NO_COMMANDS
            return stepResult
        }
        frame.prevX = sim.horse.x
        frame.prevZ = sim.horse.z
        val events = sim.step(dt, input)
        handleEvents(events)
        val end = mode.update(dt, frame, host)
        tickRebuilds(dt)
        if (end != null) finish(end)
        stepResult.events = events
        stepResult.commands = takeCommands()
        return stepResult
    }

    val modeId: RideModeId get() = mode.id
    val obstacles: List<Obstacle> get() = mode.obstacles
    val flags: Boolean get() = mode.flags
    val quitLabelKey: String get() = mode.quitLabelKey
    val quitScreen: String get() = mode.quitScreen

    /** Stops listening to the store; call when the ride screen is left. */
    fun dispose() = stopListening()

    /** Plain data for the UI to display, refreshed on every read; see [RideView]. */
    val view: RideView
        get() {
            val target: AidTarget? = mode.aidTarget(sim.approach, settings)
            if (target != null) {
                aidScratch.elementId = target.elementId
                aidScratch.dir = target.dir
                aidScratch.zone = sim.zoneFor(target.elementId, target.dir, sim.horse.speed)
                viewState.aid = aidScratch
            } else {
                viewState.aid = null
            }
            viewState.highlight = mode.highlight
            viewState.finishMarked = mode.finishMarked
            viewState.jumping = sim.horse.jump != null
            viewState.approaching = sim.approach != null
            viewState.lines = mode.lines
            viewState.hud = mode.hudModel()
            return viewState
        }

    init {
        restart()
    }
}
