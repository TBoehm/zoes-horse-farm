package app.zoeshorsefarm.domain.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

// Geometry helpers for obstacle elements (see docs/specs/springreiten-trainer/architecture.md).

/** A point or direction in the arena plane (x, z). */
data class Vec2(
    val x: Double,
    val z: Double,
)

/** Local coordinates of a point in the element system: along n (jump axis) and across t. */
data class Local(
    val along: Double,
    val across: Double,
)

/** Position and heading of the horse (anything that has them: the sim horse, a start pose). */
interface HorsePose {
    val x: Double
    val z: Double
    val heading: Double
}

/** An immutable pose, e.g. a start pose. */
data class Pose(
    override val x: Double,
    override val z: Double,
    override val heading: Double,
) : HorsePose

/** Jump axis n of an element. */
fun axisOf(element: Placed): Vec2 = Vec2(sin(element.rot), cos(element.rot))

/** Cross axis t (to the right when jumping in +n). */
fun crossAxisOf(element: Placed): Vec2 = Vec2(-cos(element.rot), sin(element.rot))

/** Local coordinates of a point in the element system: along = along n, across = along t. */
fun toLocal(
    element: Placed,
    x: Double,
    z: Double,
): Local = Local(localAlong(element, x, z), localAcross(element, x, z))

/** The `along` part of [toLocal] without allocating (hot loops of the sim). */
fun localAlong(
    element: Placed,
    x: Double,
    z: Double,
): Double = (x - element.x) * sin(element.rot) + (z - element.z) * cos(element.rot)

/** The `across` part of [toLocal] without allocating (hot loops of the sim). */
fun localAcross(
    element: Placed,
    x: Double,
    z: Double,
): Double = (x - element.x) * -cos(element.rot) + (z - element.z) * sin(element.rot)

/** Local element coordinates to world coordinates. */
fun fromLocal(
    element: Placed,
    along: Double,
    across: Double,
): Vec2 {
    val n = axisOf(element)
    val t = crossAxisOf(element)
    return Vec2(
        element.x + along * n.x + across * t.x,
        element.z + along * n.z + across * t.z,
    )
}

fun forwardOf(heading: Double): Vec2 = Vec2(sin(heading), cos(heading))

/** Heading for a direction vector (inverse of [forwardOf]). */
fun headingOf(
    x: Double,
    z: Double,
): Double = atan2(x, z)

/**
 * Did the step prev -> next lead against the heading (rein-back)? False for a missing heading.
 * Used so that crossing a line backwards does not count (rule 9).
 */
fun movedBackwards(
    prevX: Double,
    prevZ: Double,
    nextX: Double,
    nextZ: Double,
    heading: Double,
): Boolean {
    if (!heading.isFinite()) return false
    return (nextX - prevX) * sin(heading) + (nextZ - prevZ) * cos(heading) < 0
}

/** Angle wrapped to (-pi, pi]. */
fun wrapAngle(a: Double): Double {
    var r = a % (2 * PI)
    if (r <= -PI) r += 2 * PI
    if (r > PI) r -= 2 * PI
    return r
}

/**
 * Approach info of a horse to an element (concept glossary "approach").
 * - dir: +1 = jump in direction +n, -1 = in direction -n
 * - distance: distance (m) from the leading edge (pole on the approach side), measured along n
 * - angle: deviation of the course from the perpendicular to the obstacle (rad, >= 0)
 * - crossing: lateral offset (m) at the point where the course meets the obstacle plane
 * - onLine: course hits the obstacle between the stands
 * - approaching: onLine && distance < approachDistance
 */
data class ApproachInfo(
    val dir: Int,
    val distance: Double,
    val angle: Double,
    val crossing: Double,
    val onLine: Boolean,
    val approaching: Boolean,
)

/** Approach info of [horse] to [element]; null if the horse is not moving toward the element. */
fun approachInfo(
    element: Element,
    horse: HorsePose,
    approachDistance: Double,
): ApproachInfo? {
    val along = localAlong(element, horse.x, horse.z)
    val across = localAcross(element, horse.x, horse.z)
    val fx = sin(horse.heading)
    val fz = cos(horse.heading)
    val fAlong = fx * sin(element.rot) + fz * cos(element.rot)
    val fAcross = fx * -cos(element.rot) + fz * sin(element.rot)
    if (abs(fAlong) < 1e-6) return null
    val dir = if (along < 0) 1 else -1
    // is the horse moving toward the plane?
    if (sign(fAlong) != dir.toDouble()) return null
    val halfSpread = element.spread / 2
    val distance = abs(along) - halfSpread
    val angle = acos(min(1.0, abs(fAlong)))
    // lateral offset at the intersection with the element's center plane
    val travel = abs(along) / abs(fAlong)
    val crossing = across + fAcross * travel
    val onLine = abs(crossing) <= POLE_LENGTH / 2
    return ApproachInfo(
        dir = dir,
        distance = distance,
        angle = angle,
        crossing = crossing,
        onLine = onLine,
        approaching = onLine && distance < approachDistance,
    )
}
