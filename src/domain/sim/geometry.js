// Geometry helpers for obstacle elements (see docs/specs/springreiten-trainer/architecture.md).
import { POLE_LENGTH } from './tuning.js';

/** Jump axis n of an element. */
export function axisOf(element) {
  return { x: Math.sin(element.rot), z: Math.cos(element.rot) };
}

/** Cross axis t (to the right when jumping in +n). */
export function crossAxisOf(element) {
  return { x: -Math.cos(element.rot), z: Math.sin(element.rot) };
}

/** Local coordinates of a point in the element system: along = along n, across = along t. */
export function toLocal(element, x, z) {
  const n = axisOf(element);
  const t = crossAxisOf(element);
  const dx = x - element.x;
  const dz = z - element.z;
  return { along: dx * n.x + dz * n.z, across: dx * t.x + dz * t.z };
}

/** Local element coordinates to world coordinates. */
export function fromLocal(element, along, across) {
  const n = axisOf(element);
  const t = crossAxisOf(element);
  return {
    x: element.x + along * n.x + across * t.x,
    z: element.z + along * n.z + across * t.z,
  };
}

export function forwardOf(heading) {
  return { x: Math.sin(heading), z: Math.cos(heading) };
}

/** Heading for a direction vector (inverse of forwardOf). */
export function headingOf(x, z) {
  return Math.atan2(x, z);
}

/** Angle wrapped to (−π, π]. */
export function wrapAngle(a) {
  let r = a % (2 * Math.PI);
  if (r <= -Math.PI) r += 2 * Math.PI;
  if (r > Math.PI) r -= 2 * Math.PI;
  return r;
}

/**
 * Approach info of a horse to an element (concept glossary "approach").
 * Returns null if the horse is not moving toward the element.
 * - dir: +1 = jump in direction +n, −1 = in direction −n
 * - distance: distance (m) from the leading edge (pole on the approach side), measured along n
 * - angle: deviation of the course from the perpendicular to the obstacle (rad, ≥ 0)
 * - crossing: lateral offset (m) at the point where the course meets the obstacle plane
 * - onLine: course hits the obstacle between the stands
 * - approaching: onLine && distance < approachDistance
 */
export function approachInfo(element, horse, approachDistance) {
  const local = toLocal(element, horse.x, horse.z);
  const f = forwardOf(horse.heading);
  const n = axisOf(element);
  const t = crossAxisOf(element);
  const fAlong = f.x * n.x + f.z * n.z;
  const fAcross = f.x * t.x + f.z * t.z;
  if (Math.abs(fAlong) < 1e-6) return null;
  const dir = local.along < 0 ? 1 : -1;
  // is the horse moving toward the plane?
  if (Math.sign(fAlong) !== dir) return null;
  const halfSpread = (element.spread || 0) / 2;
  const distance = Math.abs(local.along) - halfSpread;
  if (distance < -halfSpread) return null;
  const angle = Math.acos(Math.min(1, Math.abs(fAlong)));
  // lateral offset at the intersection with the element's center plane
  const travel = Math.abs(local.along) / Math.abs(fAlong);
  const crossing = local.across + fAcross * travel;
  const onLine = Math.abs(crossing) <= POLE_LENGTH / 2;
  return {
    dir,
    distance,
    angle,
    crossing,
    onLine,
    approaching: onLine && distance < approachDistance,
  };
}
