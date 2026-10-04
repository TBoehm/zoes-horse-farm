// Behaviour of a grazing horse (pure, no three.js): grazes with the head down for a long time,
// now and then lifts the head and looks around, and walks a few slow steps to a new spot inside
// the paddock. Randomness comes from an injected rng.
//
// Positions are world x/z; the heading follows the simulation: the forward direction is
// (sin h, cos h) and h grows to the left, so a positive turn rate (right turn) lowers it.
import { clamp, smoothstep } from './math.js';
import { createSpring, smoothTo } from './spring.js';

export const GRAZER_STATES = Object.freeze({
  graze: 'graze',
  look: 'look',
  turn: 'turn',
  walk: 'walk',
});

/** Technical values of the paddock behaviour (look, not game play). */
export const GRAZING = Object.freeze({
  grazeTime: [12, 30], // s with the head down before something happens
  lookTime: [2.5, 6], // s with the head up
  lookThenMove: 0.7, // chance that a look ends with a few steps (otherwise back to the grass)
  stepDistance: [2.5, 6], // m to the next spot
  walkSpeed: 0.85, // m/s, a slow walk
  walkAccel: 0.8, // m/s²
  turnRate: 0.9, // rad/s while turning on the spot
  steerGain: 1.6, // turn rate per rad of heading error while walking
  maxSteer: 0.5, // rad/s
  arrive: 0.3, // m: the target is reached
  creepSpeed: 0.25, // m/s: slowest walk before the spot
  headOmega: 2.6, // 1/s, how fast the head goes down / up (95 % after ≈ 1.8 s)
  margin: 1.4, // m: targets keep this far from the fence
  separation: 3, // m: the horses keep this far from each other when they pick a spot
  candidates: 8,
});

/** Area { x, z, width, depth, rotation }: a rectangle around (x, z), turned about Y. */
export function toLocal(area, x, z, out = { x: 0, z: 0 }) {
  const dx = x - area.x;
  const dz = z - area.z;
  const c = Math.cos(area.rotation || 0);
  const s = Math.sin(area.rotation || 0);
  // inverse of the rotation about Y (three.js: x' = x c + z s, z' = −x s + z c)
  out.x = dx * c - dz * s;
  out.z = dx * s + dz * c;
  return out;
}

export function toWorld(area, lx, lz, out = { x: 0, z: 0 }) {
  const c = Math.cos(area.rotation || 0);
  const s = Math.sin(area.rotation || 0);
  out.x = area.x + lx * c + lz * s;
  out.z = area.z - lx * s + lz * c;
  return out;
}

/** Is (x, z) inside the area, `margin` m away from the border? */
export function insideArea(area, x, z, margin = 0) {
  const p = toLocal(area, x, z);
  return Math.abs(p.x) <= area.width / 2 - margin && Math.abs(p.z) <= area.depth / 2 - margin;
}

const between = (rng, [a, b]) => a + (b - a) * rng();
const wrapAngle = (a) => a - Math.PI * 2 * Math.round(a / (Math.PI * 2));
const headingTo = (dx, dz) => Math.atan2(dx, dz);

/**
 * A point in the area for the next grazing spot: `distance` away from `from` in a random
 * direction when it fits, as far from the other horses as possible (several candidates).
 * others: [{ x, z }] positions the horse should keep away from.
 */
export function pickSpot(rng, area, from, others = [], tuning = GRAZING) {
  let best = null;
  let bestScore = -Infinity;
  for (let i = 0; i < tuning.candidates; i++) {
    const dist = between(rng, tuning.stepDistance);
    const a = rng() * Math.PI * 2;
    const raw = { x: from.x + Math.sin(a) * dist, z: from.z + Math.cos(a) * dist };
    // clamp into the area (keeps the margin to the fence)
    const p = toLocal(area, raw.x, raw.z);
    p.x = clamp(p.x, -area.width / 2 + tuning.margin, area.width / 2 - tuning.margin);
    p.z = clamp(p.z, -area.depth / 2 + tuning.margin, area.depth / 2 - tuning.margin);
    const spot = toWorld(area, p.x, p.z);
    const moved = Math.hypot(spot.x - from.x, spot.z - from.z);
    let gap = Infinity;
    for (const o of others) gap = Math.min(gap, Math.hypot(spot.x - o.x, spot.z - o.z));
    // far from the others (up to the separation), and a real step
    const score = Math.min(gap, tuning.separation) + (moved > 1.2 ? 1 : 0) + rng() * 0.5;
    if (score > bestScore) {
      bestScore = score;
      best = spot;
    }
  }
  return best;
}

/** A grazer at (x, z) heading h. rng decides how long the first graze lasts. */
export function createGrazer({ x, z, heading = 0, rng }) {
  return {
    x,
    z,
    heading,
    state: GRAZER_STATES.graze,
    timer: between(rng, GRAZING.grazeTime) * rng(), // not all horses start at the same moment
    speed: 0,
    turnRate: 0,
    headDown: createSpring(1), // 1 = head on the grass
    graze: 1,
    target: null,
    steps: 0,
  };
}

/**
 * Advances a grazer. others: [{ x, z }] of the other horses (for the choice of the next spot).
 * After the step g.speed (m/s), g.turnRate (rad/s, + = right) and g.graze (0..1) are what
 * horse.update() needs.
 */
export function stepGrazer(g, dt, area, others, rng, tuning = GRAZING) {
  const S = GRAZER_STATES;
  let wantHeadDown = 0;
  let wantSpeed = 0;
  let turn = 0;

  switch (g.state) {
    case S.graze:
      wantHeadDown = 1;
      g.timer -= dt;
      if (g.timer <= 0) startLook(g, rng, tuning);
      break;
    case S.look:
      g.timer -= dt;
      if (g.timer <= 0) {
        if (rng() < tuning.lookThenMove) {
          g.target = pickSpot(rng, area, g, others, tuning);
          g.state = S.turn;
        } else {
          startGraze(g, rng, tuning);
        }
      }
      break;
    case S.turn: {
      // turn on the spot (with the head up) until the spot is ahead
      const err = wrapAngle(headingTo(g.target.x - g.x, g.target.z - g.z) - g.heading);
      if (Math.abs(err) < 0.1) {
        g.state = S.walk;
      } else {
        turn = -Math.sign(err) * tuning.turnRate * smoothstep(0.05, 0.5, Math.abs(err) + 0.1);
      }
      break;
    }
    case S.walk: {
      const dx = g.target.x - g.x;
      const dz = g.target.z - g.z;
      const dist = Math.hypot(dx, dz);
      if (dist < tuning.arrive) {
        g.target = null;
        startGraze(g, rng, tuning);
        break;
      }
      const err = wrapAngle(headingTo(dx, dz) - g.heading);
      turn = clamp(-err * tuning.steerGain, -tuning.maxSteer, tuning.maxSteer);
      // slow down for the last metre
      wantSpeed = Math.max(tuning.creepSpeed, tuning.walkSpeed * smoothstep(0.2, 1.4, dist));
      break;
    }
  }

  // speed and turn rate change gradually
  const accel = tuning.walkAccel * dt;
  g.speed += clamp(wantSpeed - g.speed, -accel * 1.5, accel);
  if (g.speed < 1e-3 && wantSpeed === 0) g.speed = 0;
  g.turnRate += (turn - g.turnRate) * (1 - Math.exp(-6 * dt));
  if (Math.abs(g.turnRate) < 1e-3 && turn === 0) g.turnRate = 0;
  g.heading = wrapAngle(g.heading - g.turnRate * dt);
  g.x += Math.sin(g.heading) * g.speed * dt;
  g.z += Math.cos(g.heading) * g.speed * dt;

  smoothTo(g.headDown, wantHeadDown, tuning.headOmega, dt);
  g.graze = clamp(g.headDown.x, 0, 1);
  return g;
}

function startLook(g, rng, tuning) {
  g.state = GRAZER_STATES.look;
  g.timer = between(rng, tuning.lookTime);
}

function startGraze(g, rng, tuning) {
  g.state = GRAZER_STATES.graze;
  g.timer = between(rng, tuning.grazeTime);
}
