package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.stepSpring
import kotlin.math.atan2
import kotlin.math.max

// Ponytail secondary motion (pure): a short chain of damped angular springs that lags behind the
// head. The input is the motion of the head in the head's own frame (+Z forward, +Y up, +X to the
// left), so bob, acceleration, turns and posting all reach the ponytail without the rider code
// knowing about gaits.

/** Bones of the chain (the ponytail hangs from the head bone). */
const val PONY_SEGMENTS = 3

// share of the total deflection each segment takes (they add up along the chain)
private val SHARE = doubleArrayOf(0.3, 0.35, 0.35)

// natural frequency (rad/s) per segment: the tip follows the slowest
private val OMEGA = doubleArrayOf(17.0, 12.0, 8.5)
private const val ZETA = 0.42

// pitch (about X, + = tail swings up): rad per m/s^2 and per m/s
private const val ACCEL_UP = 0.018 // head accelerating upwards -> tail trails downwards
private const val ACCEL_FORWARD = 0.01
private const val WIND_FORWARD = 0.04 // moving forward -> tail streams backwards (lifts)

// yaw (about Y, + = tail swings to -X)
private const val ACCEL_SIDE = 0.04
private const val WIND_SIDE = 0.06

// gravity: how much of the head's pitch the tail compensates to keep hanging down
private const val HANG = 0.7
private const val LIMIT = 1.0 // total deflection (rad) per axis
private const val ACCEL_MAX = 40.0 // m/s^2 (ignores teleports and one-frame spikes)
private const val SPEED_MAX = 14.0 // m/s

/**
 * Motion of the head in its own frame: acceleration (`ax`, `ay`, `az`, m/s^2), velocity (`vx`,
 * `vy`, `vz`, m/s) and the gravity direction (`gy`, `gz`) in the head frame.
 */
class PonytailInput(
    var ax: Double = 0.0,
    var ay: Double = 0.0,
    var az: Double = 0.0,
    var vx: Double = 0.0,
    var vy: Double = 0.0,
    var vz: Double = 0.0,
    var gy: Double = -1.0,
    var gz: Double = 0.0,
)

/** Deflection (rad) the ponytail is pulled towards. */
class PonytailTarget {
    var pitch = 0.0
    var yaw = 0.0
}

/** The springs of the chain: angles of segment i are `pitch[i].x` and `yaw[i].x` (rad). */
class Ponytail {
    val pitch: List<Spring> = List(PONY_SEGMENTS) { createSpring(0.0) }
    val yaw: List<Spring> = List(PONY_SEGMENTS) { createSpring(0.0) }
}

fun createPonytail(): Ponytail = Ponytail()

/** Deflection (rad) the ponytail is pulled towards for a head-motion input. */
fun ponytailTarget(
    input: PonytailInput,
    out: PonytailTarget = PonytailTarget(),
): PonytailTarget {
    val ay = clamp(input.ay, -ACCEL_MAX, ACCEL_MAX)
    val az = clamp(input.az, -ACCEL_MAX, ACCEL_MAX)
    val ax = clamp(input.ax, -ACCEL_MAX, ACCEL_MAX)
    val vz = clamp(input.vz, -SPEED_MAX, SPEED_MAX)
    val vx = clamp(input.vx, -SPEED_MAX, SPEED_MAX)
    // head pitched forward by theta: local gravity = (0, -cos theta, sin theta)
    val headPitch = atan2(input.gz, -input.gy)
    val pitch = -ACCEL_UP * ay + ACCEL_FORWARD * az + WIND_FORWARD * max(0.0, vz) - HANG * headPitch
    val yaw = ACCEL_SIDE * ax + WIND_SIDE * vx
    out.pitch = clamp(pitch, -LIMIT, LIMIT)
    out.yaw = clamp(yaw, -LIMIT, LIMIT)
    return out
}

private val target = PonytailTarget()

/** One step of the chain; the angles of segment i are p.pitch[i].x and p.yaw[i].x (rad). */
fun stepPonytail(
    p: Ponytail,
    input: PonytailInput,
    dt: Double,
): Ponytail {
    ponytailTarget(input, target)
    for (i in 0 until PONY_SEGMENTS) {
        val w = OMEGA[i]
        stepSpring(p.pitch[i], target.pitch * SHARE[i], w, ZETA, dt)
        stepSpring(p.yaw[i], target.yaw * SHARE[i], w, ZETA, dt)
    }
    return p
}
