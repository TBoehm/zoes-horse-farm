package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Falling poles: durations, curves and target poses (pure maths of world-layout.js).

const val FALL_DURATION = 0.7
const val RISE_DURATION = 0.45

// the second end falls a little later
private const val FALL_LAG = 0.16

fun easeOut(t: Double): Double = 1 - (1 - t) * (1 - t)

fun easeInOut(t: Double): Double = if (t < 0.5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)

// the fall is a gravity curve until this share of the time, then a small bounce
private const val IMPACT = 0.78
private const val BOUNCE = 0.1

/** Height curve of a fall (gravity, then a small bounce): 0 to 1. */
fun fallCurve(t: Double): Double =
    when {
        t <= 0 -> 0.0
        t >= 1 -> 1.0
        t < IMPACT -> (t / IMPACT).pow(2)
        else -> 1 - BOUNCE * sin((t - IMPACT) / (1 - IMPACT) * PI)
    }

/** Progress of both ends of a falling pole. */
data class EndProgress(
    val a: Double,
    val b: Double,
)

/** Progress of both ends; lead = 0: end a falls first. */
fun endProgress(
    t: Double,
    lead: Int = 0,
): EndProgress {
    val first = clamp(t / (1 - FALL_LAG), 0.0, 1.0)
    val second = clamp((t - FALL_LAG) / (1 - FALL_LAG), 0.0, 1.0)
    return if (lead == 0) EndProgress(first, second) else EndProgress(second, first)
}

/** Progress of one end (a or b) without allocating; same values as [endProgress]. */
fun endProgressOf(
    t: Double,
    lead: Int,
    isA: Boolean,
): Double {
    val first = clamp(t / (1 - FALL_LAG), 0.0, 1.0)
    val second = clamp((t - FALL_LAG) / (1 - FALL_LAG), 0.0, 1.0)
    return if ((lead == 0) == isA) first else second
}

/** Point of a falling end: eased horizontally, fall curve vertically. Points are [x, y, z]. */
fun fallPoint(
    from: DoubleArray,
    to: DoubleArray,
    t: Double,
): DoubleArray {
    val k = easeOut(t)
    val f = fallCurve(t)
    return doubleArrayOf(
        from[0] + (to[0] - from[0]) * k,
        from[1] + (to[1] - from[1]) * f,
        from[2] + (to[2] - from[2]) * k,
    )
}

/** Like [fallPoint], but for [Vec3] and without allocating: writes into [out] and returns it. */
fun fallPointInto(
    out: Vec3,
    from: Vec3,
    to: Vec3,
    t: Double,
): Vec3 {
    val k = easeOut(t)
    val f = fallCurve(t)
    out.x = from.x + (to.x - from.x) * k
    out.y = from.y + (to.y - from.y) * f
    out.z = from.z + (to.z - from.z) * k
    return out
}

/**
 * Where a pole ends up: end points [a] and [b] ([x, y, z]), the [roll] about its axis (rad) and
 * which end falls first ([lead], 0 or 1).
 */
class FallTarget(
    val a: DoubleArray,
    val b: DoubleArray,
    val roll: Double,
    val lead: Int,
)

/**
 * Target pose of a fallen pole (local): lies on the sand, shifted towards [side] (+-1 along n)
 * and slightly rotated. [rnd] gives numbers in [0, 1).
 */
fun fallTarget(
    center: DoubleArray,
    length: Double,
    side: Int,
    rnd: () -> Double,
): FallTarget {
    val travel = 0.55 + rnd() * 0.6
    val yaw = (rnd() - 0.5) * 0.5
    val cx = (rnd() - 0.5) * 0.35
    val cz = center[2] + side * travel
    val hx = cos(yaw) * length / 2
    val hz = -sin(yaw) * length / 2
    val roll = side * (travel / POLE_RADIUS) * (0.6 + rnd() * 0.3)
    val lead = if (rnd() < 0.5) 0 else 1
    return FallTarget(
        a = doubleArrayOf(cx - hx, POLE_RADIUS, cz - hz),
        b = doubleArrayOf(cx + hx, POLE_RADIUS, cz + hz),
        roll = roll,
        lead = lead,
    )
}
