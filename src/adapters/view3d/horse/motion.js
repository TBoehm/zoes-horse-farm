// Horse motion state (pure, no three.js): gait blending, stride phase, hoof paths per leg, body
// motion, jump/hop/refusal weights, idle gestures and footfall events. The three.js adapter
// (index.js) turns this into bone rotations. Rule 24: everything here changes continuously – the
// gait weights are critically damped springs, the legs keep their own phase and plant their hooves
// (legs.js), jump and refusal weights follow smooth progress curves.
import { GAITS, GAIT_KEYS, approach, blendedFrequency, bodySample, bump } from './gaits.js';
import { allPlanted, blendGait, createLegModel, stepLegs } from './legs.js';
import { clamp, smoothstep } from './math.js';
import { jumpParam } from './poses.js';
import { createGestureScheduler } from './schedule.js';
import { createSpring, smoothTo, stepSpring } from './spring.js';

// Cross-fade of the gaits: a critically damped spring reaches 95 % after 4.7 / omega ≈ 0.47 s
const GAIT_OMEGA = 10;
// The hop pose follows its envelope through a critically damped spring (no quick onset)
const HOP_OMEGA = 18;
const MOVING_GAITS = ['walk', 'trot', 'canter', 'back'];

// Canter lead: a flying change when the turn clearly goes the other way for a while
const LEAD_CHANGE = Object.freeze({ minTurn: 0.3, delay: 0.4, blendOmega: 7 });

// Jump: weight of the jump pose as a function of the jump parameter J (0 take-off … 3 landed):
// it comes in during the first half of the take-off and goes out during the landing, so there is
// no snap at either end. The weight may not change faster than this per second (a jump that
// starts in the middle, e.g. after a restart).
const JUMP_IN = 0.5;
const JUMP_OUT = [2.4, 3.0];
const JUMP_SLEW = 11; // 14 made the quick tuck-in of a trot jump turn the forearm by 0.6 rad per frame
// Landing events: progress of the landing phase when the forehand and the hindquarters touch down
const LAND = Object.freeze({ front: 0.3, hind: 0.6, hindStrength: 0.65 });

// Idle gestures only while the horse stands still
const GESTURE_MAX_SPEED = 0.05;

/**
 * Body bend (rad) for a turn rate (rad/s, + = right): bends towards the inside, so + = to the
 * left (+X) is negative for a right turn. Gain tuned for the agile steering (SRT-009) so that
 * it is still graded at trot/canter and only saturates at the fastest turns on the spot.
 */
export function turnBend(turn) {
  return clamp(-turn * 0.15, -0.35, 0.35);
}

/**
 * Lean (rad, + = to the right) into a turn: the centripetal acceleration v·ω gives the physical
 * lean atan(v·ω / g); the game takes about a third of it (at most 0.3 rad ≈ 17°) because turns are
 * much tighter than in reality (SRT-009) and the full physical lean would look like falling over.
 */
export function turnLean(speed, turn) {
  return clamp(0.35 * Math.atan((speed * turn) / 9.81), -0.3, 0.3);
}

/** Weight of the jump pose for the jump parameter J. */
export function jumpWeightFor(J) {
  return smoothstep(0, JUMP_IN, J) * (1 - smoothstep(JUMP_OUT[0], JUMP_OUT[1], J));
}

/** options.rng: random numbers for the idle gestures (without it there are none). */
export function createMotion({ rng = null } = {}) {
  const model = createLegModel();
  return {
    weights: { halt: 1, walk: 0, trot: 0, canter: 0, back: 0 },
    springs: Object.fromEntries(GAIT_KEYS.map((k) => [k, createSpring(k === 'halt' ? 1 : 0)])),
    phi: 0,
    freq: 0,
    lead: 1, // +1 left lead, −1 right lead (the one the legs follow)
    leadBlend: 1, // lead as a smooth value, for body roll and rider
    leadTimer: 0,
    model,
    legs: model.legs,
    moving: false,
    body: { bob: 0, pitch: 0, neck: 0, roll: 0 },
    jumpWeight: 0,
    jumpJ: 0,
    hopWeight: 0,
    hopSpring: createSpring(),
    hopJ: 0,
    stopWeight: 0,
    runoutWeight: 0,
    runoutDir: 1,
    landing: { front: 0, hind: 0 }, // strength of a landing in this step (0 = none)
    landPrev: -1,
    bend: 0, // body bend, + = to the left (+X)
    lean: 0, // lean, + = to the right (rotation.z)
    speed: 0,
    time: 0,
    gesture: rng ? createGestureScheduler({ rng }) : null,
    falls: [], // reused result of stepMotion (no allocation per frame)
  };
}

const tmpBody = {};
const target = { halt: 0, walk: 0, trot: 0, canter: 0, back: 0 };
const legInput = { w: null, v: 0, vs: 0, vf: 0, moving: false, gait: 'walk', lead: 1, phi: 0 };

/**
 * Advance one time step. state = sim.horse. Returns the leg indices whose hoof touched down
 * during this step (0 LF, 1 RF, 2 LH, 3 RH). The array is reused by the next call (copy it to
 * keep it).
 */
export function stepMotion(m, dt, state) {
  const gait = GAIT_KEYS.includes(state.gait) ? state.gait : 'halt';
  // speed of the gait cycles (never negative); the rein-back has a negative sim speed
  const v = Math.abs(state.speed || 0);
  const turn = state.turnRate || 0;
  m.time += dt;
  m.speed = v;

  // gait weights; turning on the spot: walking steps without forward travel
  target.halt = target.walk = target.trot = target.canter = target.back = 0;
  const step = smoothstep(0.15, 0.6, Math.abs(turn));
  if (gait === 'halt') {
    target.walk = step;
    target.halt = 1 - step;
  } else {
    target[gait] = 1;
  }
  const moving = gait !== 'halt' || step > 0.5;
  m.moving = moving;

  // canter lead: chosen when striking off, changed on the fly when the turn goes the other way
  if (gait === 'canter') {
    const want = turn > 0.05 ? -1 : turn < -0.05 ? 1 : 0;
    if (m.weights.canter < 0.05) {
      if (want) m.lead = want;
      m.leadTimer = 0;
    } else if (want && want !== m.lead && Math.abs(turn) > LEAD_CHANGE.minTurn) {
      m.leadTimer += dt;
      if (m.leadTimer >= LEAD_CHANGE.delay) {
        m.lead = want;
        m.leadTimer = 0;
      }
    } else {
      m.leadTimer = Math.max(0, m.leadTimer - 2 * dt);
    }
  } else {
    m.leadTimer = 0;
  }
  const leadS = m.leadSpring || (m.leadSpring = createSpring(m.leadBlend));
  smoothTo(leadS, m.lead, LEAD_CHANGE.blendOmega, dt);
  m.leadBlend = Math.abs(leadS.x - m.lead) < 1e-3 ? m.lead : leadS.x;

  let sum = 0;
  for (const k of GAIT_KEYS) {
    const s = m.springs[k];
    stepSpring(s, target[k], GAIT_OMEGA, 1, dt);
    if (s.x < 0) s.x = 0;
    sum += s.x;
  }
  for (const k of GAIT_KEYS) {
    const s = m.springs[k];
    s.x /= sum;
    m.weights[k] = s.x;
  }
  const w = m.weights;

  // stride phase: cadence of the commanded gait (calm walk cadence when turning at halt); at halt
  // it fades with the moving gaits
  const vf = gait === 'halt' ? Math.max(v, 0.9 * Math.abs(turn)) : v;
  blendGait(m.model, w, v, vf, dt);
  m.freq = moving ? m.model.fn : blendedFrequency(w, vf);
  m.phi = (m.phi + m.freq * dt) % 1;

  // jump, hop, refusal
  if (state.jump) {
    m.jumpJ = jumpParam(state.jump);
    const wanted = jumpWeightFor(m.jumpJ);
    const maxStep = JUMP_SLEW * dt;
    m.jumpWeight += clamp(wanted - m.jumpWeight, -maxStep, maxStep);
  } else {
    // interrupted jump (restart): fade the pose out
    m.jumpWeight = approach(m.jumpWeight, 0, 6, dt);
    if (m.jumpWeight < 1e-3) {
      m.jumpWeight = 0;
      m.jumpJ = 0;
    }
  }
  let hopTarget = 0;
  if (state.hop) {
    const p = clamp(state.hop.progress ?? 0, 0, 1);
    m.hopJ = 3 * p;
    hopTarget = bump(p, 0.15, 0.75);
  }
  smoothTo(m.hopSpring, hopTarget, HOP_OMEGA, dt);
  m.hopWeight = clamp(m.hopSpring.x, 0, 1);
  const ref = state.refusal;
  const stopT = ref && ref.type === 'stop' ? bump(ref.progress ?? 0, 0.12, 0.6) : 0;
  m.stopWeight = approach(m.stopWeight, stopT, 12, dt);
  if (ref && ref.type === 'runout') {
    if (m.runoutWeight < 0.02 && Math.abs(turn) > 0.01) m.runoutDir = turn > 0 ? -1 : 1;
    m.runoutWeight = approach(m.runoutWeight, bump(ref.progress ?? 0, 0.2, 0.7), 8, dt);
  } else {
    m.runoutWeight = approach(m.runoutWeight, 0, 8, dt);
  }

  // landing: the forehand and then the hindquarters touch down (dust, sound)
  m.landing.front = m.landing.hind = 0;
  if (state.jump && state.jump.phase === 'landing') {
    const p = clamp(state.jump.progress ?? 0, 0, 1);
    if (m.landPrev >= 0) {
      if (m.landPrev < LAND.front && p >= LAND.front) m.landing.front = 1;
      if (m.landPrev < LAND.hind && p >= LAND.hind) m.landing.hind = LAND.hindStrength;
    }
    m.landPrev = p;
  } else {
    m.landPrev = -1;
  }

  // turns: bend into the turn, lean inwards (centripetal)
  m.bend = approach(m.bend, turnBend(turn), 4, dt);
  m.lean = approach(m.lean, turnLean(state.speed || 0, turn), 4, dt);

  // hoof paths and ground contact per leg
  const falls = m.falls;
  falls.length = 0;
  legInput.w = w;
  legInput.v = v;
  legInput.vs = state.speed || 0;
  legInput.vf = vf;
  legInput.moving = moving;
  legInput.gait = gait === 'halt' ? 'walk' : gait;
  legInput.lead = m.lead;
  legInput.phi = m.phi;
  stepLegs(m.model, legInput, dt, falls);
  const quiet = gait === 'halt' || m.jumpWeight > 0.3 || m.hopWeight > 0.4;
  if (quiet) falls.length = 0;

  // idle gestures (hoof scrape) only while the horse stands still
  if (m.gesture) {
    const calm =
      !moving &&
      !(state.graze > 0.05) &&
      v < GESTURE_MAX_SPEED &&
      w.halt > 0.95 &&
      m.jumpWeight < 0.01 &&
      m.hopWeight < 0.01 &&
      m.stopWeight < 0.01 &&
      m.runoutWeight < 0.01 &&
      allPlanted(m.legs);
    const g = m.gesture.step(dt, calm);
    if (g.id === 'paw' && g.weight > 0) scrapeHoof(m.legs[g.leg], g);
  }

  // body
  const B = m.body;
  B.bob = 0;
  B.pitch = 0;
  B.neck = 0;
  B.roll = 0;
  for (const g of MOVING_GAITS) {
    const wg = w[g];
    if (wg < 1e-4) continue;
    bodySample(g, m.phi, g === 'walk' && gait === 'halt' ? 0.3 : v, m.leadBlend, tmpBody);
    B.bob += wg * tmpBody.bob;
    B.pitch += wg * tmpBody.pitch;
    B.neck += wg * tmpBody.neck;
    B.roll += wg * tmpBody.roll;
  }
  return falls;
}

/**
 * Hoof scrape of a foreleg: the leg reaches forward, drags back twice along the ground and is
 * set down again. g.weight (0..1, smooth) scales the whole motion, so it starts and ends without
 * a pop.
 */
export function scrapeHoof(leg, g) {
  const reach = smoothstep(0, 0.4, g.t) * (1 - smoothstep(g.duration - 0.45, g.duration, g.t));
  const drag = 0.5 + 0.5 * Math.cos(2 * Math.PI * 1.7 * (g.t - 0.4)); // 1 = forward, 0 = back
  const k = g.weight * reach;
  leg.dz += k * (0.16 + 0.2 * drag);
  leg.y += k * (0.05 + 0.07 * drag);
  leg.flex += k * (0.9 + 0.3 * drag);
  leg.past += k * 0.7;
}

/** Base neck carriage per gait (+ = lower/forward). */
export function neckCarriage(weights) {
  return (
    weights.halt * 0.04 +
    weights.walk * 0.12 +
    weights.trot * 0.0 +
    weights.canter * -0.05 +
    weights.back * 0.1
  );
}

export { GAITS };
