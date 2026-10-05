package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Tuning
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

// Course scoring (concept rules 32, 33, 36; glossary best result, ride time).

const val KNOCKDOWN_FAULTS = 4
const val REFUSAL_FAULTS = 4

// one fault per started 4 s
private const val TIME_FAULT_STEP_CS = 400
private const val ALLOWED_TIME_FACTOR = 1.5

/** What the "best result" comparison needs: total fault points and the time in hundredths. */
interface RatedResult {
    val totalFaults: Int
    val timeCs: Int
}

/** A plain rating (faults, time) to compare. */
data class Rating(
    override val totalFaults: Int,
    override val timeCs: Int,
) : RatedResult

private fun mid(line: Line) = Vec2((line.a.x + line.b.x) / 2, (line.a.z + line.b.z) / 2)

/**
 * Ideal line as a sequence of points: start center -> (turn waypoints) -> element centers of the
 * obstacle -> ... -> (waypoints) -> finish center. course.track[i] are the optional waypoints
 * of the leg before obstacle i or (i = number of obstacles) before the finish.
 */
fun idealLine(course: Course): List<Vec2> {
    val track = course.track
    val points = ArrayList<Vec2>()
    points.add(mid(course.start))
    course.obstacles.forEachIndexed { i, obstacle ->
        points.addAll(track.getOrElse(i) { emptyList() })
        for (element in obstacle.elements) points.add(Vec2(element.x, element.z))
    }
    points.addAll(track.getOrElse(course.obstacles.size) { emptyList() })
    points.add(mid(course.finish))
    return points
}

/** Length of the ideal line (m). */
fun idealLineLength(course: Course): Double {
    val points = idealLine(course)
    var length = 0.0
    for (i in 1 until points.size) {
        length += hypot(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
    }
    return length
}

/** Reference speed for the allowed time: medium canter, course 1 medium trot (rule 33). */
fun referenceSpeed(
    course: Course,
    tuning: Tuning = TUNING,
): Double = if (course.pace == Pace.TROT) tuning.speeds.trotMedium else tuning.speeds.canterMedium

/** Allowed time in whole seconds: ideal line / speed x 1.5, rounded up. */
fun allowedTime(
    course: Course,
    speed: Double = referenceSpeed(course),
): Int {
    val seconds = (idealLineLength(course) / speed) * ALLOWED_TIME_FACTOR
    // small tolerance against rounding noise for round values
    return ceil(seconds - 1e-9).toInt()
}

/** Milliseconds -> hundredths (truncated, like a stopwatch). */
fun toCentiseconds(ms: Double): Int = floor(ms / 10 + 1e-6).toInt()

/** Time faults: 1 point per started 4 s over the allowed time, computed in hundredths. */
fun timeFaults(overMs: Double): Int {
    // JS Math.round: halves round up
    val overCs = floor(overMs / 10 + 0.5)
    if (overCs <= 0) return 0
    return ceil(overCs / TIME_FAULT_STEP_CS).toInt()
}

/** Stars: 0 faults = 3, 1-4 = 2, more = 1 (rule 36). */
fun starsFor(totalFaults: Int): Int =
    when {
        totalFaults <= 0 -> 3
        totalFaults <= 4 -> 2
        else -> 1
    }

/**
 * Is [candidate] a new best result compared to [best]? Fewer faults, on a tie the shorter time
 * (hundredths). [best] null: always true.
 */
fun isBetterResult(
    candidate: RatedResult,
    best: RatedResult?,
): Boolean {
    if (best == null) return true
    val a = candidate.totalFaults
    val b = best.totalFaults
    if (a != b) return a < b
    return candidate.timeCs < best.timeCs
}
