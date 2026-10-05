package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.HorsePose
import app.zoeshorsefarm.domain.sim.SimRules
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Tuning
import app.zoeshorsefarm.domain.sim.approachInfo
import kotlin.math.hypot
import kotlin.math.max

// Ride state machine for a course (concept rules 22, 26-33, 49; contract "Parcours" in
// docs/specs/springreiten-trainer/architecture.md). Pure: time comes in as timeMs.

enum class RunPhase(
    val id: String,
) {
    PRESTART("prestart"),
    RIDING("riding"),
    FINISHED("finished"),
}

/** What a line crossing meant: the ride started, ended, or the finish was crossed too early. */
enum class LineCross {
    START,
    FINISH,

    /** The finish line was crossed before all obstacles were jumped (rule 30). */
    MISSING,
}

/** Fault points of a ride, itemized. */
data class Faults(
    val knockdowns: Int,
    val refusals: Int,
    val timeFaults: Int,
    val total: Int,
)

/** Result of a finished ride (input of the progress). */
data class RideResult(
    val courseId: Int,
    override val timeCs: Int,
    val faults: Faults,
    val stars: Int,
    val cleanOxer: Boolean,
    val cleanCombination: Boolean,
) : RatedResult {
    override val totalFaults: Int get() = faults.total
}

/** The element that is due next: obstacle index, part (0 = a or single, 1 = b) and element id. */
data class CurrentTarget(
    val obstacleIndex: Int,
    val part: Int,
    val elementId: String,
)

/** The element to highlight in the arena with the obstacle number (null in free mode). */
data class Highlight(
    val elementId: String,
    val number: Int?,
)

/** Answer to a landing: was the jump scored, and when should a fallen pole be rebuilt (s)? */
data class Landing(
    val scored: Boolean,
    val rebuildAfterS: Double?,
)

/** [CourseRun.nextLabel] when all obstacles are done and only the finish is due. */
const val NEXT_LABEL_FINISH = "finish"

/**
 * Does the segment prev -> next cross the line in direction line.dir?
 * Returns true only when changing from the back side (-dir) to the front side.
 */
private fun crossesLine(
    line: Line,
    prevX: Double,
    prevZ: Double,
    nextX: Double,
    nextZ: Double,
): Boolean {
    val ax = line.a.x
    val az = line.a.z
    val ex = line.b.x - ax
    val ez = line.b.z - az
    // Normal of the line, oriented so that it points in the riding direction
    var nx = -ez
    var nz = ex
    if (nx * line.dir.x + nz * line.dir.z < 0) {
        nx = -nx
        nz = -nz
    }
    val s0 = (prevX - ax) * nx + (prevZ - az) * nz
    val s1 = (nextX - ax) * nx + (nextZ - az) * nz
    if (!(s0 < 0 && s1 >= 0)) return false
    val t = s0 / (s0 - s1)
    val px = prevX + (nextX - prevX) * t - ax
    val pz = prevZ + (nextZ - prevZ) * t - az
    val u = (px * ex + pz * ez) / (ex * ex + ez * ez)
    return u >= 0 && u <= 1
}

/**
 * The state of one ride over [course]. Feed it the landings, refusals, line crossings and the
 * horse position with the course time; read the highlight, HUD values and the result.
 */
class CourseRun(
    private val course: Course,
    private val tuning: Tuning = TUNING,
) {
    private val obstacles = course.obstacles
    private val allowedMs = course.allowedTimeS * 1000.0

    private var currentPhase = RunPhase.PRESTART
    private var index = 0 // obstacle whose turn it is (= obstacles.size -> finish)
    private var part = 0 // 0 = a or single jump, 1 = part b of a combination
    private var startMs = 0.0
    private var nowMs = 0.0
    private var finalMs = 0.0
    private var knockdowns = 0
    private var refusals = 0
    private var currentHint: Int? = null
    private var hintSince = 0.0
    private var currentResult: RideResult? = null
    private val rebuilds = LinkedHashSet<String>()
    private val lying = HashSet<String>() // elements with a scored knockdown whose poles stay down

    // per element: scored jump / scored knockdown / refused
    private val scoredJumps = HashSet<String>()
    private val scoredKnocks = HashSet<String>()
    private val refused = HashSet<String>()

    private fun currentElement() = if (index >= obstacles.size) null else obstacles[index].elements[part]

    private fun rideMs(): Double =
        when (currentPhase) {
            RunPhase.PRESTART -> 0.0
            RunPhase.FINISHED -> finalMs
            RunPhase.RIDING -> max(0.0, nowMs - startMs)
        }

    private fun overMs(ms: Double) = toCentiseconds(ms) * 10 - allowedMs

    private fun timeFaultsNow() = if (currentPhase == RunPhase.PRESTART) 0 else timeFaults(overMs(rideMs()))

    private fun currentFaults(): Faults {
        val time = timeFaultsNow()
        val total = knockdowns * KNOCKDOWN_FAULTS + refusals * REFUSAL_FAULTS + time
        return Faults(knockdowns, refusals, time, total)
    }

    private fun isCurrent(
        elementId: String,
        dir: Int,
    ): Boolean {
        val current = currentElement()
        return currentPhase == RunPhase.RIDING && current != null && current.id == elementId && dir == 1
    }

    /** Re-approach the combination: back to a, rebuild a and b immediately. */
    private fun restartCombination() {
        part = 0
        for (e in obstacles[index].elements) {
            rebuilds.add(e.id)
            lying.remove(e.id)
        }
    }

    private fun advance() {
        val obstacle = obstacles[index]
        if (part + 1 < obstacle.elements.size) {
            part += 1
        } else {
            index += 1
            part = 0
        }
    }

    private fun cleanAt(ids: List<String>) =
        ids.all {
            scoredJumps.contains(it) && !scoredKnocks.contains(it) &&
                !refused.contains(it)
        }

    private fun finish(timeMs: Double) {
        nowMs = timeMs
        finalMs = max(0.0, timeMs - startMs)
        currentPhase = RunPhase.FINISHED
        currentHint = null
        val f = currentFaults()
        val oxers = obstacles.flatMap { o -> o.elements.filter { it.kind == ElementKind.OXER } }
        val combos = obstacles.filter { it.elements.size > 1 }
        currentResult =
            RideResult(
                courseId = course.id,
                timeCs = toCentiseconds(finalMs),
                faults = Faults(knockdowns, refusals, f.timeFaults, f.total),
                stars = starsFor(f.total),
                cleanOxer = oxers.any { cleanAt(listOf(it.id)) },
                cleanCombination = combos.any { o -> cleanAt(o.elements.map { it.id }) },
            )
    }

    /** Rules for the riding sim: refusal only at the current obstacle in jump direction. */
    val rules: SimRules = SimRules { elementId, dir -> isCurrent(elementId, dir) }

    val phase: RunPhase get() = currentPhase

    /** The element that is due next, or null when only the finish is left. */
    val current: CurrentTarget?
        get() {
            val e = currentElement() ?: return null
            return CurrentTarget(index, part, e.id)
        }

    /** The element to highlight (with the obstacle number), or null. */
    val highlight: Highlight?
        get() {
            val e = currentElement() ?: return null
            return Highlight(e.id, obstacles[index].number)
        }

    /** All obstacles are done: the finish line is marked. */
    val finishMarked: Boolean get() = currentElement() == null

    /** Number of the obstacle that is due, "3b" for part b of a combination, else [NEXT_LABEL_FINISH]. */
    val nextLabel: String
        get() {
            if (currentElement() == null) return NEXT_LABEL_FINISH
            val number = obstacles[index].number
            return if (part == 1) "${number}b" else "$number"
        }

    val faults: Faults get() = currentFaults()

    /** Course time in ms: 0 before the start, running while riding, frozen after the finish. */
    val timeMs: Double get() = rideMs()

    /** True once the allowed time is exceeded (same truncated hundredths as the time faults). */
    val overTime: Boolean get() = timeFaultsNow() > 0

    /** Number of the obstacle named by the "missing obstacle" hint, or null. */
    val missingHint: Int? get() = currentHint

    val result: RideResult? get() = currentResult

    /**
     * Checks the start and finish lines for the step (prevX, prevZ) -> (nextX, nextZ).
     * [backwards]: the horse moved backwards (rein-back) - a line crossed that way does not count.
     */
    fun onLineCross(
        prevX: Double,
        prevZ: Double,
        nextX: Double,
        nextZ: Double,
        timeMs: Double,
        backwards: Boolean = false,
    ): LineCross? {
        if (backwards) return null
        if (currentPhase == RunPhase.PRESTART && crossesLine(course.start, prevX, prevZ, nextX, nextZ)) {
            currentPhase = RunPhase.RIDING
            startMs = timeMs
            nowMs = timeMs
            return LineCross.START
        }
        if (currentPhase == RunPhase.RIDING && crossesLine(course.finish, prevX, prevZ, nextX, nextZ)) {
            if (currentElement() == null) {
                finish(timeMs)
                return LineCross.FINISH
            }
            currentHint = obstacles[index].number
            hintSince = timeMs
            return LineCross.MISSING
        }
        return null
    }

    fun onLanded(
        elementId: String,
        dir: Int,
        knocked: Boolean,
    ): Landing {
        if (isCurrent(elementId, dir)) {
            scoredJumps.add(elementId)
            if (knocked) {
                knockdowns += 1
                scoredKnocks.add(elementId)
                lying.add(elementId)
            }
            currentHint = null
            advance()
            return Landing(scored = true, rebuildAfterS = null)
        }
        // unscored; poles of a scored knockdown stay down until the ride ends
        val rebuild = knocked && !(currentPhase == RunPhase.RIDING && lying.contains(elementId))
        return Landing(scored = false, rebuildAfterS = if (rebuild) tuning.rebuildDelayS else null)
    }

    fun onRefusal(
        elementId: String,
        dir: Int,
    ) {
        if (!isCurrent(elementId, dir)) return
        refusals += 1
        refused.add(elementId)
        if (obstacles[index].elements.size > 1) restartCombination()
    }

    /** Advances the course clock to [timeMs] and checks for turning away between a and b (rule 31). */
    fun update(
        horse: HorsePose,
        timeMs: Double,
    ) {
        if (currentPhase != RunPhase.RIDING) return
        nowMs = timeMs
        if (currentHint != null && timeMs - hintSince >= tuning.missingHintS * 1000) currentHint = null
        // Turning away between a and b (rule 31)
        if (part == 1) {
            val b = checkNotNull(currentElement()) { "part b without a current element" }
            val info = approachInfo(b, horse, tuning.approachDistance)
            val far = hypot(horse.x - b.x, horse.z - b.z) > tuning.approachDistance
            if (!(info != null && info.approaching) && far) restartCombination()
        }
    }

    /** IDs of the elements that must be rebuilt immediately (once). */
    fun drainRebuilds(): List<String> {
        if (rebuilds.isEmpty()) return emptyList()
        val ids = rebuilds.toList()
        rebuilds.clear()
        return ids
    }
}
