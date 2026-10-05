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

// speed reached the working trot (tolerance for the end of the settling)
private const val SETTLE_EPS = 1e-9

/** Speed while galloping: strike off up to canterMin, then W/S within the canter range. */
private fun gallopSpeed(
    state: SpeedState,
    v: Double,
    delta: Double,
    dt: Double,
    tuning: Tuning,
): Double {
    val s = tuning.speeds
    state.settling = false
    return if (v < s.canterMin) {
        // Striking off into canter: smoothly up to at least canterMin
        min(s.canterMin, v + tuning.control.canterDepart * dt + max(0.0, delta))
    } else {
        clamp(v + delta, s.canterMin, s.canterMax)
    }
}

/**
 * Speed after the gallop ended during the strike-off (below trotMin): ease up to trot instead of
 * falling to a walk (rule 9). Braking (S) takes over at once.
 */
private fun easeUpToTrot(
    state: SpeedState,
    v: Double,
    th: Double,
    delta: Double,
    dt: Double,
    tuning: Tuning,
): Double {
    val s = tuning.speeds
    if (th < 0) {
        state.settling = false
        val braked = clamp(v + delta, 0.0, s.trotMax)
        return if (braked < s.haltBelow) 0.0 else braked
    }
    val eased = min(s.trotMin, v + tuning.control.gallopEndTrotUp * dt + max(0.0, delta))
    if (eased >= s.trotMin) state.settling = false
    return eased
}

/** Speed without gallop: the normal W/S control and the settling after a gallop. */
private fun walkTrotSpeed(
    state: SpeedState,
    v: Double,
    th: Double,
    delta: Double,
    dt: Double,
    tuning: Tuning,
): Double {
    val s = tuning.speeds
    if (v > s.trotMax) state.settling = true
    if (state.settling && v < s.trotMin) return easeUpToTrot(state, v, th, delta, dt, tuning)
    if (state.settling) {
        // after the gallop ends, smoothly back to working trot
        val settled = max(s.trotMedium, v - tuning.control.settleDecel * dt + min(0.0, delta))
        if (settled <= s.trotMedium + SETTLE_EPS) state.settling = false
        return settled
    }
    val next = clamp(v + delta, 0.0, s.trotMax)
    return if (th <= 0 && next < s.haltBelow) 0.0 else next
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
    val c = tuning.control
    val th = clamp(if (throttle.isNaN()) 0.0 else throttle, -1.0, 1.0)
    val delta = if (th >= 0) th * c.speedUp * dt else th * c.slowDown * dt
    val v =
        if (state.gallop) {
            gallopSpeed(state, state.speed, delta, dt, tuning)
        } else {
            walkTrotSpeed(state, state.speed, th, delta, dt, tuning)
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

/** Result of a wall hit: frontal (the caller stops) or slid along the wall; [normal] points out of the arena. */
data class FenceHit(
    val frontal: Boolean,
    val slid: Boolean,
    val normal: Vec2,
)

// The four walls (+x, -x, +z, -z) with their outward normals; hits are shared constants so a horse
// riding along the fence allocates nothing per step.
private const val WALL_COUNT = 4
private val WALL_NX = doubleArrayOf(1.0, -1.0, 0.0, 0.0)
private val WALL_NZ = doubleArrayOf(0.0, 0.0, 1.0, -1.0)
private val FRONTAL_HITS =
    Array(WALL_COUNT) { FenceHit(frontal = true, slid = false, normal = Vec2(WALL_NX[it], WALL_NZ[it])) }
private val SLID_HITS =
    Array(WALL_COUNT) { FenceHit(frontal = false, slid = true, normal = Vec2(WALL_NX[it], WALL_NZ[it])) }

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

/** How far the horse reference point is beyond wall [i] (> 0 = outside). */
private fun overshoot(
    horse: Horse,
    bounds: ArenaBounds,
    i: Int,
): Double =
    when (i) {
        0 -> horse.x - bounds.maxX
        1 -> -bounds.maxX - horse.x
        2 -> horse.z - bounds.maxZ
        else -> -bounds.maxZ - horse.z
    }

/** Eases the heading parallel to wall [i] (with [dt] > 0 at the slide turn rate, else at once). */
private fun slideAlongWall(
    horse: Horse,
    tuning: Tuning,
    i: Int,
    dt: Double,
) {
    val nx = WALL_NX[i]
    val nz = WALL_NZ[i]
    // parallel to the wall, direction as close as possible to the previous one
    val tx = -nz
    val tz = nx
    val sign = if (sin(horse.heading) * tx + cos(horse.heading) * tz >= 0) 1.0 else -1.0
    val target = headingOf(tx * sign, tz * sign)
    if (dt > 0) {
        val maxStep = tuning.fence.slideTurnRate * dt
        horse.heading = wrapAngle(horse.heading + clamp(wrapAngle(target - horse.heading), -maxStep, maxStep))
    } else {
        horse.heading = target
    }
}

/** Pushes the horse back inside wall [i] and returns the hit (null: no overshoot or moving away). */
private fun hitWall(
    horse: Horse,
    tuning: Tuning,
    bounds: ArenaBounds,
    i: Int,
    allowStop: Boolean,
    dt: Double,
): FenceHit? {
    val over = overshoot(horse, bounds, i)
    if (over <= 0) return null
    horse.x -= WALL_NX[i] * over
    horse.z -= WALL_NZ[i] * over
    val into = sin(horse.heading) * WALL_NX[i] + cos(horse.heading) * WALL_NZ[i]
    return when {
        into <= 0 -> {
            null
        }

        allowStop && into >= cos(tuning.fence.frontalAngle) -> {
            FRONTAL_HITS[i]
        }

        else -> {
            slideAlongWall(horse, tuning, i, dt)
            SLID_HITS[i]
        }
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
    var result: FenceHit? = null
    for (i in 0 until WALL_COUNT) {
        val hit = hitWall(horse, tuning, bounds, i, allowStop, dt)
        // a frontal hit wins over a slide; the first slide is kept
        if (hit != null && (hit.frontal || result == null)) result = hit
    }
    keepRearInside(horse, tuning)
    return result
}
