// Ponytail secondary motion (pure, no three.js): a short chain of damped angular springs that
// lags behind the head. The input is the motion of the head in the head's own frame (+Z forward,
// +Y up, +X to the left), so bob, acceleration, turns and posting all reach the ponytail without
// the rider code knowing about gaits.
import { clamp } from '../../shared/math.js';
import { createSpring, stepSpring } from '../../shared/spring.js';

/** Bones of the chain (the ponytail hangs from the head bone). */
export const PONY_SEGMENTS = 3;

const TUNING = {
  // share of the total deflection each segment takes (they add up along the chain)
  share: [0.3, 0.35, 0.35],
  // natural frequency (rad/s) per segment: the tip follows the slowest
  omega: [17, 12, 8.5],
  zeta: 0.42,
  // pitch (about X, + = tail swings up): rad per m/s² and per m/s
  accelUp: 0.018, // head accelerating upwards → tail trails downwards
  accelForward: 0.01,
  windForward: 0.04, // moving forward → tail streams backwards (lifts)
  // yaw (about Y, + = tail swings to −X)
  accelSide: 0.04,
  windSide: 0.06,
  // gravity: how much of the head's pitch the tail compensates to keep hanging down
  hang: 0.7,
  limit: 1.0, // total deflection (rad) per axis
  accelMax: 40, // m/s² (ignores teleports and one-frame spikes)
  speedMax: 14, // m/s
};

export function createPonytail() {
  return {
    pitch: Array.from({ length: PONY_SEGMENTS }, () => createSpring(0)),
    yaw: Array.from({ length: PONY_SEGMENTS }, () => createSpring(0)),
  };
}

/** Deflection (rad) the ponytail is pulled towards for a head-motion input. */
export function ponytailTarget(input, out = { pitch: 0, yaw: 0 }) {
  const T = TUNING;
  const ay = clamp(input.ay, -T.accelMax, T.accelMax);
  const az = clamp(input.az, -T.accelMax, T.accelMax);
  const ax = clamp(input.ax, -T.accelMax, T.accelMax);
  const vz = clamp(input.vz, -T.speedMax, T.speedMax);
  const vx = clamp(input.vx, -T.speedMax, T.speedMax);
  // head pitched forward by θ: local gravity = (0, −cos θ, sin θ)
  const headPitch = Math.atan2(input.gz ?? 0, -(input.gy ?? -1));
  const pitch =
    -T.accelUp * ay + T.accelForward * az + T.windForward * Math.max(0, vz) - T.hang * headPitch;
  const yaw = T.accelSide * ax + T.windSide * vx;
  out.pitch = clamp(pitch, -T.limit, T.limit);
  out.yaw = clamp(yaw, -T.limit, T.limit);
  return out;
}

const target = { pitch: 0, yaw: 0 };

/** One step of the chain; the angles of segment i are p.pitch[i].x and p.yaw[i].x (rad). */
export function stepPonytail(p, input, dt) {
  ponytailTarget(input, target);
  for (let i = 0; i < PONY_SEGMENTS; i++) {
    const w = TUNING.omega[i];
    stepSpring(p.pitch[i], target.pitch * TUNING.share[i], w, TUNING.zeta, dt);
    stepSpring(p.yaw[i], target.yaw * TUNING.share[i], w, TUNING.zeta, dt);
  }
  return p;
}
