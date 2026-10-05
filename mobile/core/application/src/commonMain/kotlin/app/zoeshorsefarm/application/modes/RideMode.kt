package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.domain.course.Highlight
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.sim.HorsePose
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimRules

// The strategy a ride session runs: free mode or course mode. No DOM, no i18n (texts are keys).
// Modes save nothing: only the ride session writes, through the progress service.

/** Stable id of a ride mode. */
enum class RideModeId(
    val id: String,
) {
    FREE("free"),
    COURSE("course"),
    ;

    companion object {
        fun fromId(id: String?): RideModeId? = entries.firstOrNull { it.id == id }
    }
}

// Text keys of the feedback toasts a mode asks for.
const val FEEDBACK_REFUSAL = "feedback.refusal"
const val FEEDBACK_KNOCKDOWN = "feedback.knockdown"
const val FEEDBACK_WRONG_OBSTACLE = "feedback.wrongObstacle"

/** Where and how a ride starts: the pose and, for tests, a running start ([speed], [gallop]). */
data class RideStart(
    val pose: Pose,
    val speed: Double = 0.0,
    val gallop: Boolean = false,
)

/**
 * The horse now and its position before the step. One instance is reused for every step of a ride
 * (no allocation per frame).
 */
class RideFrame(
    var horse: HorsePose,
    var prevX: Double = 0.0,
    var prevZ: Double = 0.0,
)

/** The element the jump aid points at. Modes reuse one instance; copy what you keep. */
data class AidTarget(
    var elementId: String,
    var dir: Int,
)

/** A course ride ended: go to [screen] with the [result] of the course [courseId]. */
data class RideFinish(
    val screen: String,
    val result: RideResult,
    val courseId: Int,
)

/** Text keys of the start and finish line labels. */
data class LineLabelKeys(
    val start: String,
    val finish: String,
)

/** Start and finish line of a course, drawn in the arena; the host translates the label keys. */
data class CourseLines(
    val start: Line,
    val finish: Line,
    val labelKeys: LineLabelKeys,
)

/**
 * Plain-data HUD model of a course ride. The mode reuses one instance, so read it every frame and
 * do not keep it. [nextLabel] is the number of the obstacle that is due, "3b" for part b of a
 * combination, else "finish".
 */
class CourseHud {
    var phase: RunPhase = RunPhase.PRESTART
        private set
    var timeCs: Int = 0
        private set
    var allowedS: Int = 0
        private set
    var faults: Int = 0
        private set
    var overTime: Boolean = false
        private set
    var nextLabel: String = ""
        private set

    /** Number of the obstacle named by the "missing obstacle" hint, or null. */
    var missingHint: Int? = null
        private set

    @Suppress("LongParameterList") // one value per HUD field, set together once per frame
    internal fun set(
        phase: RunPhase,
        timeCs: Int,
        allowedS: Int,
        faults: Int,
        overTime: Boolean,
        nextLabel: String,
        missingHint: Int?,
    ) {
        this.phase = phase
        this.timeCs = timeCs
        this.allowedS = allowedS
        this.faults = faults
        this.overTime = overTime
        this.nextLabel = nextLabel
        this.missingHint = missingHint
    }
}

/** What a mode may ask for while it handles events or updates. */
interface ModeHost {
    /** Show the feedback with this text key. */
    fun feedback(key: String)

    /** Put the poles of [elementId] up again after [seconds] (replaces a pending rebuild). */
    fun rebuildIn(
        elementId: String,
        seconds: Double,
    )

    fun rebuildNow(elementId: String)

    /** Drop a pending delayed rebuild of [elementId] (none pending: nothing happens). */
    fun cancelRebuild(elementId: String)
}

interface RideMode {
    val id: RideModeId
    val obstacles: List<Obstacle>

    /** Start and finish flags are drawn (course mode). */
    val flags: Boolean

    /** Start/finish lines with label keys, or null (free mode). */
    val lines: CourseLines?
    val quitLabelKey: String
    val quitScreen: String
    val rules: SimRules

    /** Obstacle that is due next (with its number), shown highlighted in the arena. */
    val highlight: Highlight?
    val finishMarked: Boolean

    fun startPose(): RideStart

    fun onRestart()

    fun onEvents(
        events: List<SimEvent>,
        host: ModeHost,
    )

    /** One step after the sim step of [dt] seconds; returns the end of the ride, or null. */
    fun update(
        dt: Double,
        frame: RideFrame,
        host: ModeHost,
    ): RideFinish?

    /** Jump aid in front of an element (rule 42), or null. [approach] is the approached obstacle. */
    fun aidTarget(
        approach: SimApproach?,
        settings: Settings,
    ): AidTarget?

    /** HUD model, or null when the mode has none. */
    fun hudModel(): CourseHud? = null
}
