// Pure mapping of a nipplejs stick position to steer/throttle (rule 10), with a dead zone.
import { clamp } from '../../shared/math.js';
import { TUNING } from '../../domain/sim/tuning.js';

const STICK_DEAD_ZONE = TUNING.control.stickDeadZone;

const NEUTRAL = Object.freeze({ steer: 0, throttle: 0 });

/** Dead zone per axis, then rescales the rest to 0..1 so the output starts at 0. */
function shape(v) {
  const a = Math.abs(v);
  return a < STICK_DEAD_ZONE ? 0 : Math.sign(v) * ((a - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE));
}

/**
 * @param {number} force stick deflection, 0..1 (values above 1 are clamped)
 * @param {number} radian angle, 0 = right, π/2 = up (nipplejs convention)
 * @returns {{ steer: number, throttle: number }} steer: +1 right; throttle: +1 faster
 */
export function mapStick(force, radian) {
  if (!Number.isFinite(force) || !Number.isFinite(radian) || force <= 0) return { ...NEUTRAL };
  const mag = clamp(force, 0, 1);
  return { steer: shape(Math.cos(radian) * mag), throttle: shape(Math.sin(radian) * mag) };
}

/** nipplejs ≥ 1 calls handlers with ONE argument `{ type, target, data }`. */
export function readStickEvent(evt) {
  const data = evt?.data;
  return mapStick(data?.force, data?.angle?.radian);
}
