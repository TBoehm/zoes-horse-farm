// Speed, gait, steering and fencing (concept rules 8, 9, 10, 24).
import { clamp } from '../../shared/math.js';
import { ARENA } from './tuning.js';
import { forwardOf, headingOf, wrapAngle } from './geometry.js';

/** Gait from speed; with gallop it is always canter (rule 9). */
export function gaitForSpeed(speed, gallop, speeds) {
  if (gallop) return 'canter';
  if (speed < speeds.haltBelow) return 'halt';
  if (speed <= speeds.walkMax) return 'walk';
  return 'trot';
}

/**
 * Continuously variable speed by throttle (−1..1, rate proportional to deflection).
 * state: { speed, gallop, settling } – is mutated. `settling` is set by the caller when the gallop
 * ends below trotMin (ease up to trot) and by this function when it ends above trotMax.
 */
export function updateSpeed(state, throttle, dt, tuning) {
  const s = tuning.speeds;
  const c = tuning.control;
  const th = clamp(throttle || 0, -1, 1);
  let v = state.speed;
  const delta = th >= 0 ? th * c.speedUp * dt : th * c.slowDown * dt;
  if (state.gallop) {
    state.settling = false;
    if (v < s.canterMin) {
      // Striking off into canter: smoothly up to at least canterMin
      v = Math.min(s.canterMin, v + c.canterDepart * dt + Math.max(0, delta));
    } else {
      v = clamp(v + delta, s.canterMin, s.canterMax);
    }
  } else {
    if (v > s.trotMax) state.settling = true;
    if (state.settling && v < s.trotMin) {
      // The gallop ended during the strike-off: ease up to trot instead of falling to a walk
      // (rule 9). Braking (S) takes over at once.
      if (th < 0) {
        state.settling = false;
        v = clamp(v + delta, 0, s.trotMax);
        if (v < s.haltBelow) v = 0;
      } else {
        v = Math.min(s.trotMin, v + c.gallopEndTrotUp * dt + Math.max(0, delta));
        if (v >= s.trotMin) state.settling = false;
      }
    } else if (state.settling) {
      // after the gallop ends, smoothly back to working trot
      v = Math.max(s.trotMedium, v - c.settleDecel * dt + Math.min(0, delta));
      if (v <= s.trotMedium + 1e-9) state.settling = false;
    } else {
      v = clamp(v + delta, 0, s.trotMax);
      if (th <= 0 && v < s.haltBelow) v = 0;
    }
  }
  state.speed = v;
  return v;
}

/** Maximum turn rate (rad/s) at this speed; radius v/ω grows with speed. */
export function maxTurnRate(speed, tuning) {
  const c = tuning.control;
  return c.turnInPlace / (1 + Math.max(0, speed) / c.turnSpeedRef);
}

/**
 * Steering: steer −1..1 (right +). horse.turnRate is rad/s, positive = right turn;
 * the heading changes by −turnRate · dt (h grows to the left).
 */
export function updateSteering(horse, steer, dt, tuning) {
  const target = clamp(steer || 0, -1, 1) * maxTurnRate(horse.speed, tuning);
  const k = 1 - Math.exp(-tuning.control.turnResponse * dt);
  horse.turnRate += (target - horse.turnRate) * k;
  horse.heading = wrapAngle(horse.heading - horse.turnRate * dt);
}

/** Moves the horse along its heading. */
export function advance(horse, speed, dt) {
  const f = forwardOf(horse.heading);
  horse.x += f.x * speed * dt;
  horse.z += f.z * speed * dt;
}

/** Limits of the reference point in the arena. */
export function arenaBounds(tuning) {
  const r = tuning.horse.radius;
  return { maxX: ARENA.width / 2 - r, maxZ: ARENA.length / 2 - r };
}

/**
 * The hindquarters (a rear point behind the reference point) must not poke through the fence
 * either: the horse is pushed inward by the overshoot, e.g. when it turns around at the wall.
 */
function keepRearInside(horse, tuning) {
  const f = forwardOf(horse.heading);
  const length = tuning.horse.rearLength;
  const maxX = ARENA.width / 2 - tuning.horse.rearMargin;
  const maxZ = ARENA.length / 2 - tuning.horse.rearMargin;
  const rx = horse.x - f.x * length;
  const rz = horse.z - f.z * length;
  if (rx > maxX) horse.x -= rx - maxX;
  else if (rx < -maxX) horse.x -= rx + maxX;
  if (rz > maxZ) horse.z -= rz - maxZ;
  else if (rz < -maxZ) horse.z -= rz + maxZ;
}

/**
 * Fencing (rule 24). Returns { frontal, slid, normal } for the wall hit, or null.
 * Always clamps the position into the arena (reference point and hindquarters).
 * Frontal: only reported, the caller stops. Oblique: the heading eases parallel to the wall
 * (with `dt`, at fence.slideTurnRate; without it at once), speed unchanged.
 */
export function applyFence(horse, tuning, { allowStop = true, dt } = {}) {
  const { maxX, maxZ } = arenaBounds(tuning);
  const cosFrontal = Math.cos(tuning.fence.frontalAngle);
  let result = null;
  const walls = [
    { over: horse.x - maxX, nx: 1, nz: 0 },
    { over: -maxX - horse.x, nx: -1, nz: 0 },
    { over: horse.z - maxZ, nx: 0, nz: 1 },
    { over: -maxZ - horse.z, nx: 0, nz: -1 },
  ];
  for (const w of walls) {
    if (w.over <= 0) continue;
    horse.x -= w.nx * w.over;
    horse.z -= w.nz * w.over;
    const f = forwardOf(horse.heading);
    const into = f.x * w.nx + f.z * w.nz;
    if (into <= 0) continue;
    if (allowStop && into >= cosFrontal) {
      result = { frontal: true, slid: false, normal: { x: w.nx, z: w.nz } };
      continue;
    }
    // parallel to the wall, direction as close as possible to the previous one
    const tx = -w.nz;
    const tz = w.nx;
    const sign = f.x * tx + f.z * tz >= 0 ? 1 : -1;
    const target = headingOf(tx * sign, tz * sign);
    if (dt > 0) {
      const maxStep = tuning.fence.slideTurnRate * dt;
      horse.heading = wrapAngle(
        horse.heading + clamp(wrapAngle(target - horse.heading), -maxStep, maxStep),
      );
    } else {
      horse.heading = target;
    }
    if (!result) result = { frontal: false, slid: true, normal: { x: w.nx, z: w.nz } };
  }
  keepRearInside(horse, tuning);
  return result;
}
