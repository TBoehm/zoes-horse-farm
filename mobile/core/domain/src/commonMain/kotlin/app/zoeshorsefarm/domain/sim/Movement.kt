package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.shared.clamp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Speed, gait, steering and fencing (concept rules 8, 9, 10, 24).

/**
 * State of the speed controller, shared by the rein-back and the normal speed update; mutated in
 * place. `settling` is set by the caller when the gallop ends below trotMin (ease up to trot) and
 * by [updateSpeed] when it ends above trotMax. `backHold` is the pause before the horse starts to
 * step back, `backBlocked` keeps it standing after fence or obstacle stopped it until S is released.
 */
class SpeedState(
    var speed: Double = 0.0,
    var gallop: Boolean = false,
    var settling: Boolean = false,
    var backHold: Double = 0.0,
    var backBlocked: Boolean = false,
)

/** Gait from speed; with gallop it is always canter, negative speed is the rein-back (rule 9). */
fun gaitForSpeed(
    speed: Double,
    gallop: Boolean,
    speeds: SpeedTuning,
): Gait =
    when {
        gallop -> Gait.CANTER
        speed < 0 -> Gait.BACK
        speed < speeds.haltBelow -> Gait.HALT
        speed <= speeds.walkMax -> Gait.WALK
        else -> Gait.TROT
    }

/**
 * Continuously variable speed by throttle (-1..1, rate proportional to deflection).
 * [state] is mutated.
 */
fun updateSpeed(
    state: SpeedState,
    throttle: Double,
    dt: Double,
    tuning: Tuning,
): Double {
    val s = tuning.speeds
    val c = tuning.control
    val th = clamp(if (throttle.isNaN()) 0.0 else throttle, -1.0, 1.0)
    var v = state.speed
    val delta = if (th >= 0) th * c.speedUp * dt else th * c.slowDown * dt
    if (state.gallop) {
        state.settling = false
        if (v < s.canterMin) {
            // Striking off into canter: smoothly up to at least canterMin
            v = min(s.canterMin, v + c.canterDepart * dt + max(0.0, delta))
        } else {
            v = clamp(v + delta, s.canterMin, s.canterMax)
        }
    } else {
        if (v > s.trotMax) state.settling = true
        if (state.settling && v < s.trotMin) {
            // The gallop ended during the strike-off: ease up to trot instead of falling to a walk
            // (rule 9). Braking (S) takes over at once.
            if (th < 0) {
                state.settling = false
                v = clamp(v + delta, 0.0, s.trotMax)
                if (v < s.haltBelow) v = 0.0
            } else {
                v = min(s.trotMin, v + c.gallopEndTrotUp * dt + max(0.0, delta))
                if (v >= s.trotMin) state.settling = false
            }
        } else if (state.settling) {
            // after the gallop ends, smoothly back to working trot
            v = max(s.trotMedium, v - c.settleDecel * dt + min(0.0, delta))
            if (v <= s.trotMedium + 1e-9) state.settling = false
        } else {
            v = clamp(v + delta, 0.0, s.trotMax)
            if (th <= 0 && v < s.haltBelow) v = 0.0
        }
    }
    state.speed = v
    return v
}

/** Maximum turn rate (rad/s) at this speed; radius v/w grows with speed. */
fun maxTurnRate(
    speed: Double,
    tuning: Tuning,
): Double {
    val c = tuning.control
    return c.turnInPlace / (1 + max(0.0, speed) / c.turnSpeedRef)
}

/**
 * Steering: steer -1..1 (right +). horse.turnRate is rad/s, positive = right turn;
 * the heading changes by -turnRate * dt (h grows to the left).
 */
fun updateSteering(
    horse: Horse,
    steer: Double,
    dt: Double,
    tuning: Tuning,
) {
    val target = clamp(if (steer.isNaN()) 0.0 else steer, -1.0, 1.0) * maxTurnRate(horse.speed, tuning)
    val k = 1 - exp(-tuning.control.turnResponse * dt)
    horse.turnRate += (target - horse.turnRate) * k
    horse.heading = wrapAngle(horse.heading - horse.turnRate * dt)
}

/** Moves the horse along its heading. */
fun advance(
    horse: Horse,
    speed: Double,
    dt: Double,
) {
    horse.x += sin(horse.heading) * speed * dt
    horse.z += cos(horse.heading) * speed * dt
}

/** Limits of the reference point in the arena. */
data class ArenaBounds(
    val maxX: Double,
    val maxZ: Double,
)

fun arenaBounds(tuning: Tuning): ArenaBounds {
    val r = tuning.horse.radius
    return ArenaBounds(maxX = ARENA.width / 2 - r, maxZ = ARENA.length / 2 - r)
}

/** Result of a wall hit: frontal (the caller stops) or slid along the wall; [normal] points into the arena's outside. */
data class FenceHit(
    val frontal: Boolean,
    val slid: Boolean,
    val normal: Vec2,
)

// The four walls (+x, -x, +z, -z) with their outward normals; hits are shared constants so a horse
// riding along the fence allocates nothing per step.
private val WALL_NX = doubleArrayOf(1.0, -1.0, 0.0, 0.0)
private val WALL_NZ = doubleArrayOf(0.0, 0.0, 1.0, -1.0)
private val FRONTAL_HITS = Array(4) { FenceHit(frontal = true, slid = false, normal = Vec2(WALL_NX[it], WALL_NZ[it])) }
private val SLID_HITS = Array(4) { FenceHit(frontal = false, slid = true, normal = Vec2(WALL_NX[it], WALL_NZ[it])) }

/**
 * The hindquarters (a rear point behind the reference point) must not poke through the fence
 * either: the horse is pushed inward by the overshoot, e.g. when it turns around at the wall.
 */
private fun keepRearInside(
    horse: Horse,
    tuning: Tuning,
) {
    val length = tuning.horse.rearLength
    val maxX = ARENA.width / 2 - tuning.horse.rearMargin
    val maxZ = ARENA.length / 2 - tuning.horse.rearMargin
    val rx = horse.x - sin(horse.heading) * length
    val rz = horse.z - cos(horse.heading) * length
    if (rx > maxX) {
        horse.x -= rx - maxX
    } else if (rx < -maxX) {
        horse.x -= rx + maxX
    }
    if (rz > maxZ) {
        horse.z -= rz - maxZ
    } else if (rz < -maxZ) {
        horse.z -= rz + maxZ
    }
}

/**
 * Fencing (rule 24). Returns the wall hit, or null.
 * Always clamps the position into the arena (reference point and hindquarters).
 * Frontal: only reported, the caller stops. Oblique: the heading eases parallel to the wall
 * (with [dt] > 0 at fence.slideTurnRate, without it at once), speed unchanged.
 */
fun applyFence(
    horse: Horse,
    tuning: Tuning,
    allowStop: Boolean = true,
    dt: Double = 0.0,
): FenceHit? {
    val bounds = arenaBounds(tuning)
    val cosFrontal = cos(tuning.fence.frontalAngle)
    var result: FenceHit? = null
    for (i in 0 until 4) {
        val nx = WALL_NX[i]
        val nz = WALL_NZ[i]
        val over =
            when (i) {
                0 -> horse.x - bounds.maxX
                1 -> -bounds.maxX - horse.x
                2 -> horse.z - bounds.maxZ
                else -> -bounds.maxZ - horse.z
            }
        if (over <= 0) continue
        horse.x -= nx * over
        horse.z -= nz * over
        val fx = sin(horse.heading)
        val fz = cos(horse.heading)
        val into = fx * nx + fz * nz
        if (into <= 0) continue
        if (allowStop && into >= cosFrontal) {
            result = FRONTAL_HITS[i]
            continue
        }
        // parallel to the wall, direction as close as possible to the previous one
        val tx = -nz
        val tz = nx
        val sign = if (fx * tx + fz * tz >= 0) 1.0 else -1.0
        val target = headingOf(tx * sign, tz * sign)
        if (dt > 0) {
            val maxStep = tuning.fence.slideTurnRate * dt
            horse.heading =
                wrapAngle(horse.heading + clamp(wrapAngle(target - horse.heading), -maxStep, maxStep))
        } else {
            horse.heading = target
        }
        if (result == null) result = SLID_HITS[i]
    }
    keepRearInside(horse, tuning)
    return result
}
