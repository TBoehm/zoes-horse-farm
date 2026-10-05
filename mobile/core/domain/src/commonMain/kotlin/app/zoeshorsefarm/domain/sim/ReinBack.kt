package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.shared.clamp
import kotlin.math.min

// Rein-back from halt (concept rule 9). Modeled as negative speed: `horse.speed < 0` is the
// whole state, gait BACK. Everything that moves the horse (advance, steering, fence) works
// unchanged with a negative speed; everything that decides a jump, a refusal or a swerve is
// guarded in RidingSim. Pure and deterministic.

/** The horse is backing: follow the throttle, or end it at once with W or gallop. */
private fun whileBacking(
    state: SpeedState,
    th: Double,
    dt: Double,
    c: ReinBackTuning,
): Boolean {
    state.backHold = 0.0
    if (state.gallop || th > 0) {
        // W / gallop: stop stepping back, the normal update accelerates from standing
        state.speed = 0.0
        return false
    }
    val target = c.maxSpeed * th // th <= 0 here: a negative target speed
    val rate = if (state.speed > target) c.accel else c.decel
    val step = clamp(target - state.speed, -rate * dt, rate * dt)
    state.speed += step
    return true
}

/** Standing with S held: count the pause, then take the first step back. */
private fun startBacking(
    state: SpeedState,
    th: Double,
    dt: Double,
    c: ReinBackTuning,
): Boolean {
    state.backHold += dt
    if (state.backHold < c.delayS) return false
    state.speed = -min(c.maxSpeed * -th, c.accel * dt)
    return true
}

/**
 * Rein-back controller, called before the normal speed update every step. [state] is mutated.
 * - Standing (speed 0) with a negative throttle held and no gallop: after `delayS` the horse
 *   steps back, up to `maxSpeed * deflection`.
 * - Backing: released -> decelerates to a stop; W (throttle > 0) or gallop ends it at once.
 * - `backBlocked` (set by the sim when fence or obstacle stopped the horse) keeps it standing
 *   until the throttle is released.
 *
 * @return true when the rein-back set `state.speed` for this step (the normal speed update must
 *   then be skipped), false when the normal speed update applies.
 */
fun updateReinBack(
    state: SpeedState,
    throttle: Double,
    dt: Double,
    tuning: Tuning,
): Boolean {
    val c = tuning.reinBack
    val th = clamp(if (throttle.isNaN()) 0.0 else throttle, -1.0, 1.0)
    if (th >= 0) state.backBlocked = false

    if (state.speed < 0) return whileBacking(state, th, dt, c)

    val wants = th < 0 && !state.gallop && !state.settling && !state.backBlocked
    if (state.speed == 0.0 && wants) return startBacking(state, th, dt, c)
    state.backHold = 0.0
    return false
}
