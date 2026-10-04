// Where the rider looks (pure, no three.js): into the curve, down at the fence on take-off, ahead
// over the fence in flight, and a calm glance around at halt. The targets are smoothed with
// critically damped springs so the head never snaps.
import { clamp } from '../../shared/math.js';
import { smoothstep } from './horse/math.js';
import { jumpParam } from './horse/poses.js';
import { makeSpring, springStep } from './rider-spring.js';

const LOOK = {
  yawPerTurn: 0.4, // rad of head yaw per rad/s of turn rate
  yawMax: 0.55, // about 31°
  pitchMax: 0.3,
  takeoffDown: 0.14, // looking at the fence (nose down)
  flightUp: -0.1, // looking ahead over the fence
  idleYaw: 0.14,
  omegaYaw: 6.5,
  omegaPitch: 7.5,
};

/**
 * Head yaw (rad, + = to the left) and pitch (rad, + = nose down) the rider wants for a horse
 * state { turnRate (+ = right), jump }. `calm` (0..1) is the share of halt, `time` the clock.
 */
export function lookTarget(state = {}, calm = 0, time = 0, out = { yaw: 0, pitch: 0 }) {
  const turn = state.turnRate || 0;
  // a right turn (turnRate > 0) looks to the right = negative yaw
  let yaw = clamp(-turn * LOOK.yawPerTurn, -LOOK.yawMax, LOOK.yawMax);
  yaw += LOOK.idleYaw * calm * Math.sin(time * 0.31) * Math.sin(time * 0.17 + 1);
  let pitch = 0;
  if (state.jump) {
    const J = jumpParam(state.jump);
    pitch =
      LOOK.takeoffDown * smoothstep(0, 0.6, J) * (1 - smoothstep(0.85, 1.25, J)) +
      LOOK.flightUp * smoothstep(1.0, 1.6, J) * (1 - smoothstep(2.0, 2.8, J));
  }
  out.yaw = clamp(yaw, -LOOK.yawMax, LOOK.yawMax);
  out.pitch = clamp(pitch, -LOOK.pitchMax, LOOK.pitchMax);
  return out;
}

export function createHeadLook() {
  return { yaw: makeSpring(0), pitch: makeSpring(0), time: 0, target: { yaw: 0, pitch: 0 } };
}

/** Advances the smoothed look; the result is look.yaw.x / look.pitch.x. */
export function stepHeadLook(look, dt, state, calm) {
  look.time += dt;
  lookTarget(state, calm, look.time, look.target);
  springStep(look.yaw, look.target.yaw, LOOK.omegaYaw, dt);
  springStep(look.pitch, look.target.pitch, LOOK.omegaPitch, dt);
  return look;
}
