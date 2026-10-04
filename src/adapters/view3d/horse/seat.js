// Rider seat model (pure, no three.js): pelvis rise/forward shift, torso lean and rein-hand
// position in rider space, blended from the horse's motion state.
import { smoothstep } from './math.js';
import { jumpParam } from './poses.js';
import { makeSpring, snapSpring, springStep } from '../rider-spring.js';

const KEYS = ['rise', 'forward', 'lean', 'handX', 'handY', 'handZ', 'footForward'];

const SIT = {
  rise: 0,
  forward: 0,
  lean: 0.1,
  handX: 0.09,
  handY: 0.2,
  handZ: 0.36,
  footForward: 0,
};
const LIGHT = {
  rise: 0.05,
  forward: 0.03,
  lean: 0.5,
  handX: 0.09,
  handY: 0.15,
  handZ: 0.43,
  footForward: 0,
};
const TWO_POINT = {
  rise: 0.12,
  forward: 0.12,
  lean: 1.25,
  handX: 0.1,
  handY: 0.1,
  handZ: 0.62,
  footForward: -0.03,
};
const BACK = {
  rise: 0,
  forward: -0.03,
  lean: -0.1,
  handX: 0.09,
  handY: 0.26,
  handZ: 0.3,
  footForward: 0.04,
};

function posting(phi) {
  // rise during one diagonal beat, sit during the other: once per stride
  return 0.5 - 0.5 * Math.cos(2 * Math.PI * phi);
}

function blendInto(out, src, w) {
  for (const k of KEYS) out[k] += src[k] * w;
}

function lerpTo(out, src, t) {
  if (t <= 0) return;
  for (const k of KEYS) out[k] += (src[k] - out[k]) * t;
}

/**
 * Crest release (0..1): the hands slide forward along the horse's neck while it stretches over the
 * fence (flight) and come back as it lands and the rider takes up the contact again.
 */
export function crestRelease(J, jumpWeight = 1) {
  return jumpWeight * smoothstep(0.7, 1.35, J) * (1 - smoothstep(1.85, 2.55, J));
}

/** Landing absorption (0..1): the seat sinks a little when the forehand touches down. */
function landingAbsorb(J, jumpWeight) {
  return jumpWeight * smoothstep(1.85, 2.15, J) * (1 - smoothstep(2.3, 2.9, J));
}

const RELEASE = { handZ: 0.1, handY: -0.045, handX: 0.01 };
const ABSORB = { rise: -0.04, forward: -0.015 };

/**
 * Splits the seat in a calm part (`base`: follows from gait weights, fold, release, stop – this is
 * what the seat filter smooths) and an immediate part (`osc`: the posting beat, the canter rhythm,
 * the balance against the horse's pitch – these follow the horse exactly). Both are added to get
 * the pose. ctx (optional) = horse motion: { weights, phi, jumpWeight, jumpJ, hopWeight,
 * stopWeight, pitch }. Without ctx the gait comes from state.gait.
 */
export function riderSeatParts(state = {}, ctx = null, base = {}, osc = {}) {
  const weights = ctx?.weights || {
    halt: 0,
    walk: 0,
    trot: 0,
    canter: 0,
    back: 0,
    [state.gait || 'halt']: 1,
  };
  const phi = ctx?.phi ?? 0;
  for (const k of KEYS) {
    base[k] = 0;
    osc[k] = 0;
  }
  blendInto(base, SIT, (weights.halt || 0) + (weights.walk || 0) + (weights.back || 0));
  blendInto(
    base,
    {
      rise: 0,
      forward: 0,
      lean: 0.25,
      handX: 0.09,
      handY: 0.19,
      handZ: 0.38,
      footForward: 0,
    },
    weights.trot || 0,
  );
  blendInto(base, LIGHT, weights.canter || 0);
  // the rhythmic parts are scaled like the base pose: they fade out in the two-point seat
  const r = posting(phi);
  const wt = weights.trot || 0;
  const wc = weights.canter || 0;
  osc.rise = 0.1 * r * wt + 0.012 * Math.sin(2 * Math.PI * phi) * wc;
  osc.forward = 0.07 * r * wt;
  osc.lean = 0.08 * r * wt;

  const J = ctx ? ctx.jumpJ : jumpParam(state.jump);
  const jumpW = ctx ? ctx.jumpWeight : state.jump ? 1 : 0;
  const fold = jumpW * smoothstep(0.15, 0.9, J) * (1 - smoothstep(2.3, 3.0, J));
  const hopW = ctx?.hopWeight ?? (state.hop ? 1 : 0);
  const hopFold = hopW * 0.5;
  const keep = 1 - Math.min(1, fold + hopFold);
  lerpTo(base, TWO_POINT, Math.min(1, fold + hopFold));
  // release and absorption act on top of the folded seat
  const release = crestRelease(J, jumpW) + 0.4 * hopW;
  const absorb = landingAbsorb(J, jumpW);
  base.handZ += RELEASE.handZ * release;
  base.handY += RELEASE.handY * release;
  base.handX += RELEASE.handX * release;
  base.rise += ABSORB.rise * absorb;
  base.forward += ABSORB.forward * absorb;
  const stop = ctx?.stopWeight ?? 0;
  lerpTo(base, BACK, stop);
  const stopKeep = 1 - Math.min(1, stop);
  for (const k of KEYS) osc[k] *= keep * stopKeep;

  base.release = release;
  base.horsePitch = ctx?.pitch ?? 0;
  // keep the upper body balanced against the horse's pitch (nose up → fold more)
  osc.lean -= base.horsePitch * 0.5;
  base.sway = 0.05 * (weights.walk || 0) * Math.sin(4 * Math.PI * phi);
  base.roll = 0;
  return base;
}

const scratchBase = {};
const scratchOsc = {};

/** Seat for the current frame, unfiltered (see riderSeatParts and createSeatFilter). */
export function riderSeat(state = {}, ctx = null, out = {}) {
  const osc = scratchOsc;
  riderSeatParts(state, ctx, out, osc);
  for (const k of KEYS) out[k] += osc[k];
  return out;
}

// How fast the calm part follows its target (rad/s of a critically damped spring): about 60-80 ms
// time constant, so a sudden change of gait weights, fold or stop turns into a quick, soft move.
const FILTER_OMEGA = {
  rise: 16,
  forward: 16,
  lean: 13,
  handX: 15,
  handY: 15,
  handZ: 15,
  footForward: 14,
};

/**
 * Stateful seat: the calm part of riderSeatParts goes through critically damped springs, the
 * immediate part is added unfiltered. Keeps every seat change (SIT ↔ posting ↔ LIGHT ↔ TWO_POINT ↔
 * BACK) free of jumps, whatever the horse state does from one frame to the next.
 */
export function createSeatFilter() {
  const springs = {};
  for (const k of KEYS) springs[k] = makeSpring(0);
  let started = false;
  return {
    step(dt, state, ctx, out = {}) {
      riderSeatParts(state, ctx, scratchBase, scratchOsc);
      for (const k of KEYS) {
        if (!started) snapSpring(springs[k], scratchBase[k]);
        else springStep(springs[k], scratchBase[k], FILTER_OMEGA[k], dt);
        out[k] = springs[k].x + scratchOsc[k];
      }
      started = true;
      out.release = scratchBase.release;
      out.horsePitch = scratchBase.horsePitch;
      out.sway = scratchBase.sway;
      out.roll = scratchBase.roll;
      return out;
    },
  };
}
