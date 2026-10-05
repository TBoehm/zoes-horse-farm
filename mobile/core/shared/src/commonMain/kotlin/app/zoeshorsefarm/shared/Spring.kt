package app.zoeshorsefarm.shared

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Damped spring (pure). The step is the closed-form solution of the damped harmonic oscillator for
// a constant target, so it is stable for any time step (no blow-up at large dt, a hitch of a whole
// second cannot hurt it) and frame-rate independent. Used for the secondary motion of the horse
// (mane, tail, smoothing of parameters) and of the rider (hands, head, ponytail, seat).
// References: https://theorangeduck.com/page/spring-roll-call and
// https://www.ryanjuckett.com/damped-springs/

/** Longest time step (s) that a spring integrates in one go; a longer frame is clamped to it. */
private const val MAX_SPRING_DT = 0.4

/** Output limit of a spring state (guard against bad input, in the unit of the spring). */
private const val STATE_LIMIT = 50.0

/** A spring state: position [x] (offset from rest) and velocity [v]. Mutated by [stepSpring]. */
data class Spring(
    var x: Double = 0.0,
    var v: Double = 0.0,
)

fun createSpring(
    x: Double = 0.0,
    v: Double = 0.0,
): Spring = Spring(x, v)

/**
 * Advances a spring towards [target]. [omega]: undamped angular frequency (rad/s), [zeta]: damping
 * ratio (1 = critical, < 1 swings over). Exact for a constant target and any dt >= 0.
 */
fun stepSpring(
    s: Spring,
    target: Double,
    omega: Double,
    zeta: Double,
    dt: Double,
): Spring {
    if (!(dt > 0)) return s
    val h = min(dt, MAX_SPRING_DT)
    val e = s.x - target
    val v = s.v
    val w = max(omega, 1e-6)
    val z = max(zeta, 0.0)
    val ne: Double
    val nv: Double
    if (abs(z - 1) < 1e-4) {
        val ex = exp(-w * h)
        val j = v + w * e
        ne = (e + j * h) * ex
        nv = (v - j * w * h) * ex
    } else if (z < 1) {
        val wd = w * sqrt(1 - z * z)
        val ex = exp(-z * w * h)
        val c = cos(wd * h)
        val sn = sin(wd * h)
        val c2 = (v + z * w * e) / wd
        ne = ex * (e * c + c2 * sn)
        nv = ex * (v * c - ((z * w * v + w * w * e) / wd) * sn)
    } else {
        val r = w * sqrt(z * z - 1)
        val r1 = -w * z + r
        val r2 = -w * z - r
        val c2 = (v - r1 * e) / (r2 - r1)
        val c1 = e - c2
        val e1 = exp(r1 * h)
        val e2 = exp(r2 * h)
        ne = c1 * e1 + c2 * e2
        nv = c1 * r1 * e1 + c2 * r2 * e2
    }
    s.x = clamp(target + ne, -STATE_LIMIT, STATE_LIMIT)
    s.v = clamp(nv, -STATE_LIMIT * 10, STATE_LIMIT * 10)
    return s
}

/** Puts the spring at value [x] without velocity (first frame, teleports). */
fun snapSpring(
    s: Spring,
    x: Double,
): Spring {
    s.x = x
    s.v = 0.0
    return s
}
