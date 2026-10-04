// Small signs of life for the rider (pure, no three.js): breathing with a little shoulder
// movement, and a pat on the horse's neck once it stands after a jump.
import { smoothstep } from './horse/math.js';
import { makeSpring, springStep } from './rider-spring.js';

const LIFE = {
  breathHz: 0.27,
  breathChest: 0.016, // rad of chest pitch
  shoulderHz: 0.11,
  shoulderRoll: 0.012, // rad, slow weight shift
  patDuration: 1.7, // s
  patCount: 3,
  patWindow: 8, // s after the landing in which a halt still earns a pat
  reachOmega: 18, // 1/s, how fast the hand follows the pat timeline
};

/** Chest pitch and chest roll (rad) for the clock `time`; calmer and larger at halt. */
export function breathing(time, calm = 0, out = { chest: 0, roll: 0 }) {
  const amp = 0.45 + 0.55 * calm;
  out.chest = LIFE.breathChest * amp * Math.sin(2 * Math.PI * LIFE.breathHz * time);
  out.roll = LIFE.shoulderRoll * calm * Math.sin(2 * Math.PI * LIFE.shoulderHz * time + 0.7);
  return out;
}

export function createPat() {
  return { armed: false, since: 0, t: -1, reach: 0, tap: 0, reachSpring: makeSpring(0) };
}

/**
 * Tracks "jumped, then came to a halt" and plays one pat per jump. `jumping` = the horse is in
 * a jump, `halted` = the horse stands (gait halt, halt weight ~1). Result: p.reach (0..1, hand
 * on the neck) and p.tap (0..1, the small up-and-down of the pat, fades with the reach); both
 * are zero otherwise.
 */
export function stepPat(p, dt, { jumping = false, halted = false } = {}) {
  if (jumping) {
    p.armed = true;
    p.since = 0;
    p.t = -1;
  } else if (p.armed) {
    p.since += dt;
    if (p.since > LIFE.patWindow) p.armed = false;
    else if (halted) {
      p.armed = false;
      p.t = 0;
    }
  }
  if (p.t >= 0) {
    // the player rides on again: the hand goes back (quickly, through the reach spring)
    if (!halted) p.t = -1;
    else {
      p.t += dt;
      if (p.t >= LIFE.patDuration) p.t = -1;
    }
  }
  let reach = 0;
  let tap = 0;
  if (p.t >= 0) {
    const u = p.t / LIFE.patDuration;
    reach = smoothstep(0, 0.2, u) * (1 - smoothstep(0.82, 1, u));
    tap = 0.5 - 0.5 * Math.cos(2 * Math.PI * LIFE.patCount * u);
  }
  // a cancelled pat must not make the hand jump: the reach always goes through a spring
  p.reach = springStep(p.reachSpring, reach, LIFE.reachOmega, dt);
  p.tap = tap * p.reach;
  return p;
}
