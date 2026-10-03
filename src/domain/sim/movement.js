// Tempo, Gangart, Lenkung und Umzäunung (Konzept Regeln 8, 9, 10, 24).
import { ARENA } from './tuning.js';
import { forwardOf, headingOf, wrapAngle } from './geometry.js';

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

/** Gangart aus Tempo; mit Galopp immer Galopp (Regel 9). */
export function gaitForSpeed(speed, gallop, speeds) {
  if (gallop) return 'canter';
  if (speed < speeds.haltBelow) return 'halt';
  if (speed <= speeds.walkMax) return 'walk';
  return 'trot';
}

/**
 * Tempo stufenlos nach throttle (−1..1, Rate proportional zur Auslenkung).
 * state: { speed, gallop, settling } – wird verändert.
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
      // Angaloppieren: sanft auf mindestens canterMin
      v = Math.min(s.canterMin, v + c.canterDepart * dt + Math.max(0, delta));
    } else {
      v = clamp(v + delta, s.canterMin, s.canterMax);
    }
  } else {
    if (v > s.trotMax) state.settling = true;
    if (state.settling) {
      // nach Galopp-Ende sanft zurück in den Arbeitstrab
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

/** Höchste Wendegeschwindigkeit (rad/s) bei diesem Tempo; Radius v/ω wächst mit dem Tempo. */
export function maxTurnRate(speed, tuning) {
  const c = tuning.control;
  return c.turnInPlace / (1 + Math.max(0, speed) / c.turnSpeedRef);
}

/** Kurvenradius (m) bei vollem Lenkeinschlag. */
export function turnRadius(speed, tuning) {
  return speed / maxTurnRate(speed, tuning);
}

/**
 * Lenkung: steer −1..1 (rechts +). horse.turnRate ist rad/s, positiv = Rechtskurve;
 * die Blickrichtung ändert sich um −turnRate · dt (h wächst nach links).
 */
export function updateSteering(horse, steer, dt, tuning) {
  const target = clamp(steer || 0, -1, 1) * maxTurnRate(horse.speed, tuning);
  const k = 1 - Math.exp(-tuning.control.turnResponse * dt);
  horse.turnRate += (target - horse.turnRate) * k;
  horse.heading = wrapAngle(horse.heading - horse.turnRate * dt);
}

/** Bewegt das Pferd entlang der Blickrichtung. */
export function advance(horse, speed, dt) {
  const f = forwardOf(horse.heading);
  horse.x += f.x * speed * dt;
  horse.z += f.z * speed * dt;
}

/** Grenzen des Bezugspunkts auf dem Reitplatz. */
export function arenaBounds(tuning) {
  const r = tuning.horse.radius;
  return { maxX: ARENA.width / 2 - r, maxZ: ARENA.length / 2 - r };
}

/**
 * Umzäunung (Regel 24). Liefert { frontal, slid, normal } für die getroffene Wand oder null.
 * Klemmt die Position immer in den Platz. Frontal: nur gemeldet, der Aufrufer stoppt.
 * Schräg: Blickrichtung parallel zur Wand, Tempo unverändert.
 */
export function applyFence(horse, tuning, { allowStop = true } = {}) {
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
    // parallel zur Wand, Richtung möglichst nah an der bisherigen
    const tx = -w.nz;
    const tz = w.nx;
    const sign = f.x * tx + f.z * tz >= 0 ? 1 : -1;
    horse.heading = headingOf(tx * sign, tz * sign);
    if (!result) result = { frontal: false, slid: true, normal: { x: w.nx, z: w.nz } };
  }
  return result;
}
