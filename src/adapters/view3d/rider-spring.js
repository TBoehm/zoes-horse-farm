// Damped springs for the rider's secondary motion (pure, no three.js). The step is the closed-form
// solution of x'' = -2ζω·x' - ω²·(x - target), so it is stable for any time step (a hitch of a
// whole second cannot blow it up) and does not depend on the frame rate.
// Reference: https://www.ryanjuckett.com/damped-springs/ , https://theorangeduck.com/page/spring-roll-call

const NEAR_CRITICAL = 1e-4;

/** A spring state { x, v } at rest at value x. */
export function makeSpring(x = 0) {
  return { x, v: 0 };
}

/**
 * Moves the spring towards `target` by dt seconds. omega = natural angular frequency (rad/s),
 * zeta = damping ratio (1 = critically damped, no overshoot; < 1 overshoots and wobbles).
 * Returns the new value.
 */
export function springStep(s, target, omega, dt, zeta = 1) {
  if (!(dt > 0)) return s.x;
  if (!(omega > 1e-6)) {
    s.x += s.v * dt;
    return s.x;
  }
  const x0 = s.x - target;
  const v0 = s.v;
  let x;
  let v;
  if (Math.abs(zeta - 1) < NEAR_CRITICAL) {
    const e = Math.exp(-omega * dt);
    const c = v0 + omega * x0;
    x = (x0 + c * dt) * e;
    v = (v0 - c * omega * dt) * e;
  } else if (zeta > 1) {
    const root = omega * Math.sqrt(zeta * zeta - 1);
    const z1 = -omega * zeta - root;
    const z2 = -omega * zeta + root;
    const e1 = Math.exp(z1 * dt);
    const e2 = Math.exp(z2 * dt);
    const c2 = (v0 - z1 * x0) / (z2 - z1);
    const c1 = x0 - c2;
    x = c1 * e1 + c2 * e2;
    v = c1 * z1 * e1 + c2 * z2 * e2;
  } else {
    const decay = omega * zeta;
    const wd = omega * Math.sqrt(1 - zeta * zeta);
    const e = Math.exp(-decay * dt);
    const cos = Math.cos(wd * dt);
    const sin = Math.sin(wd * dt);
    const k = (v0 + decay * x0) / wd;
    x = e * (x0 * cos + k * sin);
    v = e * (-decay * (x0 * cos + k * sin) + wd * (k * cos - x0 * sin));
  }
  s.x = target + x;
  s.v = v;
  return s.x;
}

/** Puts the spring at value x without velocity (first frame, teleports). */
export function snapSpring(s, x) {
  s.x = x;
  s.v = 0;
}
