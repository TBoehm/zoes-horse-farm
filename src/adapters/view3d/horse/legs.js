// Hoof model of the four legs (pure, no three.js): one stride phase per leg, hoof paths from
// gait parameters that are blended over time, and planted hooves.
//
// Why not blend the poses of two gaits? A hoof that is in stance in one gait and in swing in the
// other would be averaged to a position that matches neither, and in stance it would slide over the
// ground. Here every leg has ONE path at any time:
//  - its phase q follows the stride phase of the commanded gait; when the commanded gait or the
//    canter lead changes the legs approach the new phases gradually (they speed up or slow down a
//    little, in stance and in swing), so every leg stays on a plausible path and nothing pops;
//  - the shape of the path (duty factor, lift, flexion, stride length) is the weighted mix of the
//    gait parameters; the mix is smooth because the gait weights are;
//  - in stance the hoof is anchored: its position relative to the body is where it touched down
//    minus the distance the body has travelled since, so it never slides, whatever the speed, the
//    cadence or the gait mix do; the swing path runs from where the hoof left the ground to where
//    it should touch down, so it is continuous at both ends;
//  - when the horse stops, legs in swing finish their step, legs in stance stay planted and are
//    brought square one at a time with a small step (like a horse shuffling its feet).
//
// Leg index: 0 = LF, 1 = RF, 2 = LH, 3 = RH. dz is the fore/aft offset of the hoof in model space.
import {
  GAITS,
  legPhase,
  liftScaleFor,
  offsetsFor,
  swingFlex,
  swingLift,
  swingPast,
  swingTravel,
} from './gaits.js';
import { clamp } from './math.js';

const MOVING_GAITS = ['walk', 'trot', 'canter', 'back'];

const PHASE = Object.freeze({
  gain: 5, // 1/s: how fast a leg approaches the phase of the commanded gait
  fast: 0.3, // a leg may run up to this share of the cadence faster …
  slow: 0.45, // … or (in swing only) slower than the cadence
});

// Square-up step of a leg that stands away from its neutral position when the horse has stopped
const SQUARE = Object.freeze({
  time: 0.42, // s
  minOffset: 0.05, // m: shorter offsets stay as they are
  maxSpeed: 0.15, // m/s: only when the horse really stands
  lift: 0.07,
  flex: 0.9,
  past: 0.7,
});

const FREEZE_SPEED = 0.1; // m/s: below this a stopped horse plants its legs
// Longest stance travel of a hoof (m): what the legs can reach with the IK of index.js. A faster
// gait shortens the duty factor instead of stretching the stride (real horses do the same).
const REACH_TRAVEL = 1.0;
const MAX_TRAVEL = REACH_TRAVEL * 1.1; // m: the planted hoof never goes further than this

const CONTACT_Y = 0.004; // m: lower than this the hoof counts as on the ground
const MIN_PEAK = 0.02; // m: a swing lower than this does not make a footfall (stepping in place)
const SHAPE_TAU = 0.1; // s: smoothing of the speed-dependent shape of the steps
const HOLD_TAU = 0.5; // s: release time of the step amplitude when the moving gaits fade out
const RB_EPS = 0.02; // keeps the rein-back share continuous when the moving gaits fade out
const HALT_SINK = 0.008; // fetlock drop of a standing horse

function createLeg() {
  return {
    // outputs (read by the adapter)
    dz: 0,
    y: 0,
    flex: 0,
    past: 0,
    sink: 0,
    stance: true,
    c: 1,
    // state
    q: 0.2, // phase of the leg: stance q < duty, swing duty ≤ q < 1
    inStance: true,
    anchor: 0, // dz at touchdown
    travel: 0, // distance the body has moved (forwards +) since touchdown
    from: 0, // dz at lift-off
    peak: 0, // highest lift of the current swing
    squaring: false,
    squareT: 0,
    squareFrom: 0,
  };
}

export function createLegModel() {
  return {
    legs: [0, 1, 2, 3].map(createLeg),
    fn: 1, // cadence of the commanded gait (strides/s)
    duty: 0.6,
    hold: 0,
    primed: false,
    stride: 0, // smoothed stride length (m)
    // amplitudes per unit of the moving gaits' share (last known)
    shape: { lift: [0, 0, 0, 0], flex: [0, 0, 0, 0], past: [0, 0, 0, 0] },
    // blended gait parameters (reused every frame)
    blend: {
      S: 0, // share of the moving gaits in the mix
      rb: 0, // share of the rein-back among them
      sink: 0,
      c: [0, 0, 0, 0],
      lift: [0, 0, 0, 0],
      flex: [0, 0, 0, 0],
      past: [0, 0, 0, 0],
    },
  };
}

/**
 * Mix of the gait parameters for gait weights `w` at speed v. Amplitudes (center, lift, flexion,
 * pastern) are weighted sums, so they fade out with the share of the moving gaits; duty factor
 * and cadence are normalised averages.
 */
export function blendGait(model, w, v, vf, dt = 0) {
  const P = model.blend;
  const N = model.shape;
  P.S = 0;
  P.sink = 0;
  for (let i = 0; i < 4; i++) P.c[i] = P.lift[i] = P.flex[i] = P.past[i] = 0;
  let duty = 0;
  let fn = 0;
  for (const g of MOVING_GAITS) {
    const wg = w[g];
    if (wg < 1e-4) continue;
    const G = GAITS[g];
    const ls = liftScaleFor(g, v);
    P.S += wg;
    const f = G.freq(vf);
    duty += wg * Math.min(G.duty(v), (REACH_TRAVEL * f) / Math.max(v, 1e-3));
    fn += wg * f;
    P.sink += wg * G.sink;
    for (let i = 0; i < 4; i++) {
      P.c[i] += wg * G.center[i];
      P.lift[i] += wg * G.lift[i] * ls;
      P.flex[i] += wg * G.flex[i] * ls;
      P.past[i] += wg * G.past[i] * ls;
    }
  }
  // Duty factor, step amplitude and stride length depend on the speed, and the sim may change the
  // speed from one frame to the next (a stop at the fence): smooth them, so that a step in
  // progress does not jump.
  const k = dt > 0 ? 1 - Math.exp(-dt / SHAPE_TAU) : 1;
  if (P.S >= 1e-4) {
    model.duty = model.primed ? model.duty + (duty / P.S - model.duty) * k : duty / P.S;
    model.fn = fn / P.S;
  }
  P.rb = w.back / (P.S + RB_EPS);
  // The amplitude of the steps fades out slower than the moving gaits do, so that a step in
  // progress when the horse stops is still lifted (and does not scrape over the ground).
  model.hold = Math.max(P.S, model.hold * Math.exp(-dt / HOLD_TAU));
  if (P.S >= 1e-3) {
    const kk = model.primed ? k : 1;
    for (let i = 0; i < 4; i++) {
      N.lift[i] += (P.lift[i] / P.S - N.lift[i]) * kk;
      N.flex[i] += (P.flex[i] / P.S - N.flex[i]) * kk;
      N.past[i] += (P.past[i] / P.S - N.past[i]) * kk;
    }
    model.primed = true;
  }
  for (let i = 0; i < 4; i++) {
    P.lift[i] = N.lift[i] * model.hold;
    P.flex[i] = N.flex[i] * model.hold;
    P.past[i] = N.past[i] * model.hold;
  }
  return model;
}

/**
 * Advances the legs by dt (call blendGait first). input: { w (gait weights), v (speed, ≥ 0),
 * vs (signed speed along the heading, − when backing), vf (speed for the cadence), moving (a gait
 * is commanded; false at halt), gait (commanded moving gait, for the phase offsets), lead
 * (+1 left, −1 right), phi (stride phase of the horse) }.
 * Touch-downs of real steps are pushed to `falls` (leg indices).
 */
export function stepLegs(model, input, dt, falls) {
  const { w, v, moving } = input;
  const P = model.blend; // blendGait() has been called for this frame
  const strideNow = P.S >= 1e-4 ? Math.min(REACH_TRAVEL, (model.duty * v) / model.fn) : 0;
  model.stride += (strideNow - model.stride) * (dt > 0 ? 1 - Math.exp(-dt / SHAPE_TAU) : 1);
  const stride = model.stride;
  const offsets = offsetsFor(input.gait, input.lead);
  const baseSink = w.halt * HALT_SINK;
  for (let i = 0; i < 4; i++) {
    const leg = model.legs[i];
    if (leg.squaring) stepSquare(leg, dt, baseSink);
    else stepCycle(model, leg, i, input, offsets, stride, baseSink, dt, falls);
    const lifted = leg.y >= CONTACT_Y;
    leg.stance = !lifted;
    leg.c = lifted ? 1 - Math.min(1, leg.y / 0.02) : 1;
  }
  // bring a leg that stands away from the neutral position square, one at a time
  if (!moving && v < SQUARE.maxSpeed && model.legs.every((l) => l.inStance && !l.squaring)) {
    let worst = -1;
    let best = SQUARE.minOffset;
    for (let i = 0; i < 4; i++) {
      const off = Math.abs(model.legs[i].dz);
      if (off > best) {
        best = off;
        worst = i;
      }
    }
    if (worst >= 0) {
      const leg = model.legs[worst];
      leg.squaring = true;
      leg.squareT = 0;
      leg.squareFrom = leg.dz;
    }
  }
}

function stepSquare(leg, dt, baseSink) {
  leg.squareT += dt;
  const u = clamp(leg.squareT / SQUARE.time, 0, 1);
  leg.dz = leg.squareFrom * (1 - swingTravel(u));
  leg.y = SQUARE.lift * swingLift(u);
  leg.flex = SQUARE.flex * swingFlex(u);
  leg.past = SQUARE.past * swingPast(u);
  leg.sink = baseSink;
  if (u >= 1) {
    leg.squaring = false;
    leg.inStance = true;
    leg.anchor = 0;
    leg.travel = 0;
    leg.q = 0.05;
    leg.dz = leg.y = leg.flex = leg.past = 0;
  }
}

function stepCycle(model, leg, i, input, offsets, stride, baseSink, dt, falls) {
  const P = model.blend;
  // rein-back: the cycle runs the other way (the hoof lands behind its neutral position)
  const sgn = 1 - 2 * P.rb;
  const { moving } = input;
  const d = model.duty;
  const fn = model.fn;
  const c = P.c[i];
  // the hoof stays where it is on the ground, so it moves back by what the body travels
  if (leg.inStance) leg.travel = clamp(leg.travel + input.vs * dt, -MAX_TRAVEL, MAX_TRAVEL);

  // 1. phase: follow the commanded gait; at halt only a step in progress is finished
  let rate = 0;
  if (moving) {
    let err = legPhase(input.phi, offsets[i]) - leg.q;
    err -= Math.round(err);
    // in stance a leg can only hurry (it would overextend if it waited), in swing it can also wait
    const corr = clamp(PHASE.gain * err, leg.inStance ? 0 : -PHASE.slow * fn, PHASE.fast * fn);
    rate = fn + corr;
  } else if (!leg.inStance || input.v > FREEZE_SPEED) {
    // a horse that is still gliding keeps stepping; at a stand a step in progress is finished
    rate = fn;
  }
  let q = leg.q + rate * dt;
  let wrapped = false;
  if (q >= 1) {
    q -= 1;
    wrapped = true;
  }
  leg.q = q;

  // 2. touch-down at the end of the swing, lift-off at the end of the stance
  if (!leg.inStance && wrapped) {
    leg.inStance = true;
    leg.anchor = c + (sgn * stride) / 2;
    leg.travel = 0;
    if (leg.peak >= MIN_PEAK && falls) falls.push(i);
  } else if (leg.inStance) {
    // lift-off at the end of the stance, or when the hoof is as far back as the leg reaches
    // (e.g. when the horse sets off faster than its legs were prepared for)
    const rear = sgn * (leg.anchor - leg.travel - c);
    if (q >= d || rear < -REACH_TRAVEL / 2) {
      leg.inStance = false;
      leg.from = leg.anchor - leg.travel;
      leg.peak = 0;
      if (q < d) leg.q = d;
    }
  }

  // 3. hoof path
  let dz;
  if (leg.inStance) {
    dz = leg.anchor - leg.travel;
    leg.y = leg.flex = leg.past = 0;
    leg.sink = baseSink + P.sink * Math.sin(Math.PI * clamp(q / d, 0, 1));
  } else {
    const u = clamp((q - d) / (1 - d), 0, 1);
    const target = c + (sgn * stride) / 2;
    dz = leg.from + (target - leg.from) * swingTravel(u);
    leg.y = P.lift[i] * swingLift(u);
    leg.flex = P.flex[i] * swingFlex(u);
    leg.past = P.past[i] * swingPast(u);
    leg.sink = baseSink;
    if (leg.y > leg.peak) leg.peak = leg.y;
  }
  leg.dz = dz;
}
