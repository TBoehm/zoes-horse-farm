// Damped spring (pure, no three.js). The step is the closed-form solution of the damped harmonic
// oscillator for a constant target, so it is stable for any time step (no blow-up at large dt,
// a hitch of a whole second cannot hurt it) and frame-rate independent. Used for the secondary
// motion of the horse (mane, tail, smoothing of parameters) and of the rider (hands, head,
// ponytail, seat). References: https://theorangeduck.com/page/spring-roll-call and
// https://www.ryanjuckett.com/damped-springs/
import { clamp } from './math.js';

/** Longest time step (s) that a spring integrates in one go; a longer frame is clamped to it. */
const MAX_SPRING_DT = 0.4;
/** Output limit of a spring state (guard against bad input, in the unit of the spring). */
const STATE_LIMIT = 50;

/** A spring state { x, v }: position (offset from rest) and velocity. */
export const createSpring = (x = 0, v = 0) => ({ x, v });

/**
 * Advances a spring towards `target`. omega: undamped angular frequency (rad/s), zeta: damping
 * ratio (1 = critical, < 1 swings over). Exact for a constant target and any dt ≥ 0.
 */
export function stepSpring(s, target, omega, zeta, dt) {
  if (!(dt > 0)) return s;
  const h = Math.min(dt, MAX_SPRING_DT);
  const e = s.x - target;
  const v = s.v;
  const w = Math.max(omega, 1e-6);
  const z = Math.max(zeta, 0);
  let ne;
  let nv;
  if (Math.abs(z - 1) < 1e-4) {
    const ex = Math.exp(-w * h);
    const j = v + w * e;
    ne = (e + j * h) * ex;
    nv = (v - j * w * h) * ex;
  } else if (z < 1) {
    const wd = w * Math.sqrt(1 - z * z);
    const ex = Math.exp(-z * w * h);
    const c = Math.cos(wd * h);
    const sn = Math.sin(wd * h);
    const c2 = (v + z * w * e) / wd;
    ne = ex * (e * c + c2 * sn);
    nv = ex * (v * c - ((z * w * v + w * w * e) / wd) * sn);
  } else {
    const r = w * Math.sqrt(z * z - 1);
    const r1 = -w * z + r;
    const r2 = -w * z - r;
    const c2 = (v - r1 * e) / (r2 - r1);
    const c1 = e - c2;
    const e1 = Math.exp(r1 * h);
    const e2 = Math.exp(r2 * h);
    ne = c1 * e1 + c2 * e2;
    nv = c1 * r1 * e1 + c2 * r2 * e2;
  }
  s.x = clamp(target + ne, -STATE_LIMIT, STATE_LIMIT);
  s.v = clamp(nv, -STATE_LIMIT * 10, STATE_LIMIT * 10);
  return s;
}

/** Puts the spring at value x without velocity (first frame, teleports). */
export function snapSpring(s, x) {
  s.x = x;
  s.v = 0;
  return s;
}
