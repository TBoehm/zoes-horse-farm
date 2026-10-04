// Rider seat model (pure, no three.js): pelvis rise/forward shift, torso lean and rein-hand
// position in rider space, blended from the horse's motion state.
import { smoothstep } from './math.js';
import { jumpParam } from './poses.js';

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
 * Seat for the current frame. ctx (optional) = horse motion: { weights, phi, jumpWeight, jumpJ,
 * hopWeight, stopWeight, pitch }. Without ctx the gait comes from state.gait.
 */
export function riderSeat(state = {}, ctx = null, out = {}) {
  const weights = ctx?.weights || {
    halt: 0,
    walk: 0,
    trot: 0,
    canter: 0,
    [state.gait || 'halt']: 1,
  };
  const phi = ctx?.phi ?? 0;
  for (const k of KEYS) out[k] = 0;
  blendInto(out, SIT, (weights.halt || 0) + (weights.walk || 0));
  const r = posting(phi);
  blendInto(
    out,
    {
      rise: 0.1 * r,
      forward: 0.07 * r,
      lean: 0.25 + 0.08 * r,
      handX: 0.09,
      handY: 0.19,
      handZ: 0.38,
      footForward: 0,
    },
    weights.trot || 0,
  );
  blendInto(
    out,
    { ...LIGHT, rise: LIGHT.rise + 0.012 * Math.sin(2 * Math.PI * phi) },
    weights.canter || 0,
  );
  const J = ctx ? ctx.jumpJ : jumpParam(state.jump);
  const jumpW = ctx ? ctx.jumpWeight : state.jump ? 1 : 0;
  const fold = jumpW * smoothstep(0.15, 0.9, J) * (1 - smoothstep(2.3, 3.0, J));
  const hopFold = (ctx?.hopWeight ?? (state.hop ? 1 : 0)) * 0.5;
  lerpTo(out, TWO_POINT, Math.min(1, fold + hopFold));
  lerpTo(out, BACK, ctx?.stopWeight ?? 0);
  out.horsePitch = ctx?.pitch ?? 0;
  // keep the upper body balanced against the horse's pitch (nose up → fold more)
  out.lean -= out.horsePitch * 0.5;
  out.sway = 0.05 * (weights.walk || 0) * Math.sin(4 * Math.PI * phi);
  out.roll = 0;
  return out;
}
