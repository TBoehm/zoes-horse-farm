// Rein-back from halt (concept rule 9). Modeled as negative speed: `horse.speed < 0` is the
// whole state, gait 'back'. Everything that moves the horse (advance, steering, fence) works
// unchanged with a negative speed; everything that decides a jump, a refusal or a swerve is
// guarded in riding-sim.js. Pure and deterministic.
import { clamp } from '../../shared/math.js';

/**
 * Rein-back controller, called before the normal speed update every step.
 * state: { speed, gallop, settling, backHold, backBlocked } – is mutated.
 * - Standing (speed 0) with a negative throttle held and no gallop: after `delayS` the horse
 *   steps back, up to `maxSpeed · deflection`.
 * - Backing: released → decelerates to a stop; W (throttle > 0) or gallop ends it at once.
 * - `backBlocked` (set by the sim when fence or obstacle stopped the horse) keeps it standing
 *   until the throttle is released.
 * @returns {boolean} true when the rein-back set `state.speed` for this step (the normal speed
 *   update must then be skipped), false when the normal speed update applies.
 */
export function updateReinBack(state, throttle, dt, tuning) {
  const c = tuning.reinBack;
  const th = clamp(throttle || 0, -1, 1);
  if (th >= 0) state.backBlocked = false;

  if (state.speed < 0) {
    state.backHold = 0;
    if (state.gallop || th > 0) {
      // W / gallop: stop stepping back, the normal update accelerates from standing
      state.speed = 0;
      return false;
    }
    const target = -c.maxSpeed * -th;
    const rate = state.speed > target ? c.accel : c.decel;
    const step = clamp(target - state.speed, -rate * dt, rate * dt);
    state.speed += step;
    return true;
  }

  const wants = th < 0 && !state.gallop && !state.settling && !state.backBlocked;
  if (state.speed === 0 && wants) {
    state.backHold += dt;
    if (state.backHold >= c.delayS) {
      state.speed = -Math.min(c.maxSpeed * -th, c.accel * dt);
      return true;
    }
    return false;
  }
  state.backHold = 0;
  return false;
}
