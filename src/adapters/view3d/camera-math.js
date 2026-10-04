// Pure helpers of the camera rig (no three.js).
import { clamp } from '../../shared/math.js';
import { TUNING } from '../../domain/sim/tuning.js';

// Camera heading constants (technical, no game play): the camera heading trails the horse so that
// quick turns sweep the view calmly. At a constant turn rate ω an exponential follower lags by
// ω / stiffness: 2.7 rad/s (fastest player turn, TUNING.control.turnInPlace, also while backing)
// gives about 34° for the follow camera and 13° for the rider view.
export const FOLLOW_HEADING_STIFFNESS = 4.5;
export const RIDER_HEADING_STIFFNESS = 12;
// Cap of the follow camera's swing rate (rad/s). It must stay above the fastest turn the player
// can make: below it the lag would grow without bound during a long spin on the spot and the
// camera would whip around through 180° once the cap is lifted. It only limits sudden jumps of
// the horse heading (refusal evasion, fence slide). Derived from the tuning so that it follows.
export const FOLLOW_HEADING_MAX_RATE = TUNING.control.turnInPlace * 1.2;

const TWO_PI = Math.PI * 2;

/** Wraps an angle into (−π, π]. */
export function wrapAngle(angle) {
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
  const diff = wrapAngle(target - current);
  const k = 1 - Math.exp(-stiffness * dt);
  const maxStep = maxRate * dt;
  return wrapAngle(current + clamp(diff * k, -maxStep, maxStep));
}
