// Pure helpers of the camera rig (no three.js).
import { clamp } from '../../shared/math.js';

const TWO_PI = Math.PI * 2;

/** Wraps an angle into (−π, π]. */
function wrap(angle) {
  let a = angle % TWO_PI;
  if (a > Math.PI) a -= TWO_PI;
  else if (a <= -Math.PI) a += TWO_PI;
  return a;
}

/**
 * Eases a heading (radians) towards `target` like an exponential follower (`stiffness` in 1/s),
 * along the short way around the circle. `maxRate` (rad/s, optional) caps the swing speed.
 * Used so that fast horse turns do not whip the camera around.
 */
export function followHeading(current, target, stiffness, dt, maxRate = Infinity) {
  const diff = wrap(target - current);
  const k = 1 - Math.exp(-stiffness * dt);
  const maxStep = maxRate * dt;
  return wrap(current + clamp(diff * k, -maxStep, maxStep));
}

/** Steady-state angle (rad) the exponential heading follower lags behind a constant turn rate. */
export function headingLag(turnRate, stiffness) {
  return Math.abs(turnRate) / stiffness;
}
