// Pure mapping of a nipplejs stick position to steer/throttle (rule 10).
//
// Dead zone: scaled radial dead zone (J. Sutphin, "Doing Thumbstick Dead Zones Right",
// gamedeveloper.com; see also minimuino.github.io/thumbstick-deadzones). It acts on the stick
// vector's length, not per axis, so a small deflection in ANY direction is neutral and the
// direction of a diagonal hold is preserved. The remaining range is rescaled to 0..1, so the
// output starts at 0 without a jump at the dead-zone edge.
//
// Steering gain/saturation: the rescaled sideways component is multiplied by a gain so that full
// lock is reached at TUNING.control.stickSteerFull (≤ 2/3) of the stick radius. A 45° forward
// hold then already gives (nearly) full lock, as on a car-style touch steering control. Throttle
// stays the plain rescaled vertical component: fully pulled down gives exactly -1.
import { clamp } from '../../shared/math.js';
import { TUNING } from '../../domain/sim/tuning.js';

const STICK_DEAD_ZONE = TUNING.control.stickDeadZone;
// gain on the rescaled sideways component: raw deflection `stickSteerFull` maps to full lock
const STEER_GAIN = (1 - STICK_DEAD_ZONE) / (TUNING.control.stickSteerFull - STICK_DEAD_ZONE);

const NEUTRAL = Object.freeze({ steer: 0, throttle: 0 });

/**
 * @param {number} force stick deflection, 0..1 (values above 1 are clamped)
 * @param {number} radian angle, 0 = right, π/2 = up (nipplejs convention)
 * @returns {{ steer: number, throttle: number }} steer: +1 right; throttle: +1 faster
 */
export function mapStick(force, radian) {
  if (!Number.isFinite(force) || !Number.isFinite(radian)) return { ...NEUTRAL };
  const mag = clamp(force, 0, 1);
  if (mag < STICK_DEAD_ZONE) return { ...NEUTRAL };
  // scaled radial dead zone: same direction, length rescaled from [dead zone, 1] to [0, 1]
  const scaled = (mag - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE);
  return {
    steer: clamp(Math.cos(radian) * scaled * STEER_GAIN, -1, 1),
    throttle: clamp(Math.sin(radian) * scaled, -1, 1),
  };
}

/** nipplejs ≥ 1 calls handlers with ONE argument `{ type, target, data }`. */
export function readStickEvent(evt) {
  const data = evt?.data;
  return mapStick(data?.force, data?.angle?.radian);
}
