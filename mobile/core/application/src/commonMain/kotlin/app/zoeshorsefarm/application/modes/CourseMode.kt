package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.domain.course.CourseRun
import app.zoeshorsefarm.domain.course.Highlight
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.course.courseById
import app.zoeshorsefarm.domain.course.toCentiseconds
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimRules
import app.zoeshorsefarm.domain.sim.movedBackwards

private const val MS_PER_SECOND = 1000.0

/**
 * Course mode for the ride screen (SRT-004): prestart, ride, scoring, end of ride.
 * No DOM and no i18n here: the HUD is exposed as plain data ([hudModel]) and rendered by the UI;
 * texts are passed as keys.
 *
 * Pure strategy: knows the course run, but neither the store nor the progress. The ride session
 * saves the finished ride (progress service) and the settings it passes decide the jump aid.
 * An unknown or missing [courseId] gives the first course.
 */
class CourseMode(
    courseId: Int? = null,
) : RideMode {
    private val course = courseById(courseId)
    private var run = CourseRun(course)
    private var clockMs = 0.0
    private val start = RideStart(course.startPose)
    private val hud = CourseHud()
    private val aid = AidTarget("", 1) // reused every frame
    private val courseLines =
        CourseLines(
            start = course.start,
            finish = course.finish,
            labelKeys = LineLabelKeys(start = "prestart.legendStart", finish = "prestart.legendFinish"),
        )

    override val id = RideModeId.COURSE
    override val obstacles = course.obstacles
    override val flags = true

    /** Start/finish lines; the host translates the label keys. */
    override val lines: CourseLines = courseLines
    override val quitLabelKey = "pause.toSelect"
    override val quitScreen = "courseSelect"

    // Delegation, because "restart" creates a new run
    override val rules: SimRules = SimRules { elementId, dir -> run.rules.canRefuse(elementId, dir) }

    override fun startPose() = start

    /** Obstacle that is due next (with its number), shown highlighted in the arena. */
    override val highlight: Highlight? get() = run.highlight

    override val finishMarked: Boolean get() = run.finishMarked

    /** Plain-data HUD model for the UI; always the same object, refreshed on every call. */
    override fun hudModel(): CourseHud {
        hud.set(
            phase = run.phase,
            timeCs = toCentiseconds(run.timeMs),
            allowedS = course.allowedTimeS,
            faults = run.faults.total,
            overTime = run.overTime,
            nextLabel = run.nextLabel,
            missingHint = run.missingHint,
        )
        return hud
    }

    override fun onRestart() {
        run = CourseRun(course)
        clockMs = 0.0
    }

    override fun onEvents(
        events: List<SimEvent>,
        host: ModeHost,
    ) {
        for (e in events) {
            if (e is SimEvent.Landed) onLanded(e, host)
            if (e is SimEvent.Refusal) {
                run.onRefusal(e.elementId, e.dir)
                host.feedback(FEEDBACK_REFUSAL)
            }
        }
    }

    private fun onLanded(
        e: SimEvent.Landed,
        host: ModeHost,
    ) {
        val riding = run.phase == RunPhase.RIDING
        val landing = run.onLanded(e.elementId, e.dir, e.knocked)
        if (landing.scored) {
            if (e.knocked) {
                host.feedback(FEEDBACK_KNOCKDOWN)
                // the poles of a scored knockdown stay down: drop a pending unscored rebuild
                host.cancelRebuild(e.elementId)
            }
        } else if (riding) {
            // a jump that does not count (wrong obstacle or direction), during the ride only
            host.feedback(FEEDBACK_WRONG_OBSTACLE)
        }
        val rebuildAfterS = landing.rebuildAfterS
        if (rebuildAfterS != null && rebuildAfterS != 0.0) host.rebuildIn(e.elementId, rebuildAfterS)
    }

    override fun update(
        dt: Double,
        frame: RideFrame,
        host: ModeHost,
    ): RideFinish? {
        if (run.phase == RunPhase.RIDING) clockMs += dt * MS_PER_SECOND
        val horse = frame.horse
        // a line crossed in rein-back does not count (rule 9)
        run.onLineCross(
            frame.prevX,
            frame.prevZ,
            horse.x,
            horse.z,
            clockMs,
            backwards = movedBackwards(frame.prevX, frame.prevZ, horse.x, horse.z, horse.heading),
        )
        run.update(horse, clockMs)
        for (id in run.drainRebuilds()) host.rebuildNow(id)
        if (run.phase != RunPhase.FINISHED) return null
        return run.result?.let { RideFinish("results", it, course.id) }
    }

    override fun aidTarget(
        approach: SimApproach?,
        settings: Settings,
    ): AidTarget? {
        if (!settings.aidCourse) return null
        val current = run.current ?: return null
        aid.elementId = current.elementId
        aid.dir = 1
        return aid
    }
}
