// Pure mapping of a nipplejs stick position to steer/throttle (rule 10).
//
// Dead zone: hybrid "scaled radial followed by sloped scaled axial" dead zone
// (minimuino.github.io/thumbstick-deadzones; radial part after J. Sutphin, "Doing Thumbstick Dead
// Zones Right", gamedeveloper.com). The radial zone makes a small deflection in ANY direction
// neutral; the rest of the length is rescaled to 0..1, so the output starts at 0 without a jump.
// The axial zones act on the unit direction components: a hold near horizontal gives no throttle,
// a hold near vertical gives no steering. Without them the two controls bleed into each other
// (turning on the spot would start rein-back, steering at trot would brake, a thumb wobble on a
// straight approach would turn the horse).
//
// Steering gain/saturation: the sideways component is multiplied by a gain so that full lock is
// reached at TUNING.control.stickSteerFull (≤ 2/3) of the stick radius. A 45° forward hold then
// already gives full lock, as on a car-style touch steering control. Fully pulled down gives
// throttle of about -1 (exactly -1 on the vertical axis).
import { clamp } from '../../shared/math.js';
import { TUNING } from '../../domain/sim/tuning.js';

const STICK_DEAD_ZONE = TUNING.control.stickDeadZone;
// gain on the rescaled sideways component: raw deflection `stickSteerFull` maps to full lock
const STEER_GAIN = (1 - STICK_DEAD_ZONE) / (TUNING.control.stickSteerFull - STICK_DEAD_ZONE);

const AXIAL_THROTTLE = TUNING.control.stickAxialThrottle;
const AXIAL_STEER = TUNING.control.stickAxialSteer;

const NEUTRAL = Object.freeze({ steer: 0, throttle: 0 });

/** Axial dead zone of one unit direction component, rescaled so it starts at 0 at the edge. */
const axial = (component, zone) => {
  const rescaled = (Math.abs(component) - zone) / (1 - zone);
  // plain 0 inside the zone (no negative zero)
  return rescaled > 0 ? Math.sign(component) * rescaled : 0;
};

/**
 * @param {number} force stick deflection, 0..1 (values above 1 are clamped)
 * @param {number} radian angle, 0 = right, π/2 = up (nipplejs convention)
 * @returns {{ steer: number, throttle: number }} steer: +1 right; throttle: +1 faster
 */
export function mapStick(force, radian) {
  if (!Number.isFinite(force) || !Number.isFinite(radian)) return { ...NEUTRAL };
  const mag = clamp(force, 0, 1);
  if (mag < STICK_DEAD_ZONE) return { ...NEUTRAL };
  // scaled radial dead zone: length rescaled from [dead zone, 1] to [0, 1]
  const scaled = (mag - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE);
  return {
    steer: clamp(axial(Math.cos(radian), AXIAL_STEER) * scaled * STEER_GAIN, -1, 1),
    throttle: clamp(axial(Math.sin(radian), AXIAL_THROTTLE) * scaled, -1, 1),
  };
}

/** nipplejs ≥ 1 calls handlers with ONE argument `{ type, target, data }`. */
export function readStickEvent(evt) {
  const data = evt?.data;
  return mapStick(data?.force, data?.angle?.radian);
}
