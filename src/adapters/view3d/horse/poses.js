// Key poses for jump, hop and refusal (pure, no three.js).
// A pose vector holds body/neck values and, per leg, the hoof offset relative to the rest position
// in the local frame of the leg's parent bone (i.e. body-relative), plus flexions.
import { clamp } from '../../../shared/math.js';

export const POSE_KEYS = [
  'pitch', // body pitch, + = nose down (rad)
  'dy', // extra body height (m)
  'bend', // back arch (bascule), + = round back
  'neck', // neck, + = forward/down
  'head', // head at the poll, + = nose towards the chest
  'tail', // tail lift
  'pivot', // z of the ground pivot for pitch (m)
];
const LEG_KEYS = ['dz', 'dy', 'flex', 'past'];
export const LEG_OFFSET = POSE_KEYS.length;
export const POSE_SIZE = POSE_KEYS.length + 4 * LEG_KEYS.length;

function pose(base, legs) {
  const v = new Array(POSE_SIZE).fill(0);
  POSE_KEYS.forEach((k, i) => (v[i] = base[k] ?? 0));
  legs.forEach((l, i) => {
    for (let j = 0; j < 4; j++) v[LEG_OFFSET + i * 4 + j] = l[j];
  });
  return v;
}

// Legs: [LF, RF, LH, RH] each [dz, dy, flex, past]. Approach on the left lead; landing first on
// the non-leading (RF), then on the leading foreleg (LF).
export const JUMP_KEYS = [
  // J = 0: approach (neutral)
  pose({ pivot: -0.6 }, [
    [0, 0, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
  ]),
  // 0.5: take-off – forehand lifts, hind legs step far under
  pose({ pitch: -0.22, neck: -0.12, head: 0.05, tail: 0.2, pivot: -0.3 }, [
    [0.22, 0.4, 1.7, 0.9],
    [0.26, 0.3, 1.4, 0.7],
    [0.36, 0.0, 0.05, 0.0],
    [0.3, 0.0, 0.05, 0.0],
  ]),
  // 1.0: hindquarters push off, forelegs tightly folded
  pose({ pitch: -0.4, bend: 0.02, neck: 0.12, head: 0.08, tail: 0.4, pivot: -0.3 }, [
    [0.26, 0.66, 2.6, 1.3],
    [0.3, 0.62, 2.5, 1.25],
    [-0.08, 0.0, -0.15, -0.25],
    [-0.12, 0.0, -0.15, -0.25],
  ]),
  // 1.5: flight – bascule, neck long forward/down, hind legs trailing
  pose({ pitch: 0.04, bend: 0.14, neck: 0.42, head: 0.12, tail: 0.6, pivot: 0.1 }, [
    [0.18, 0.7, 2.8, 1.4],
    [0.21, 0.68, 2.75, 1.35],
    [-0.36, 0.3, 0.5, 0.6],
    [-0.4, 0.27, 0.5, 0.6],
  ]),
  // 2.0: forelegs extend for landing, hind legs folded
  pose({ pitch: 0.24, bend: 0.05, neck: 0.02, head: 0.02, tail: 0.5, pivot: 0.65 }, [
    [0.2, 0.12, 0.15, 0.15],
    [0.1, 0.02, 0.0, 0.0],
    [0.3, 0.3, 0.7, 0.8],
    [0.26, 0.28, 0.7, 0.8],
  ]),
  // 2.5: forehand landed, hind legs come forward under the body
  pose({ pitch: 0.1, bend: -0.02, neck: -0.18, head: -0.08, tail: 0.3, pivot: 0.65 }, [
    [0.06, 0.0, 0, 0],
    [-0.14, 0.0, 0, 0],
    [0.36, 0.16, 0.5, 0.5],
    [0.3, 0.22, 0.6, 0.5],
  ]),
  // 3.0: canter away (neutral)
  pose({ pivot: 0.65 }, [
    [0, 0, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
  ]),
];

// refusal "stop": abrupt braking, forelegs braced, haunches lowered, head up
export const STOP_POSE = pose(
  { pitch: -0.1, dy: -0.05, neck: -0.5, head: -0.25, tail: 0.1, pivot: 0.7 },
  [
    [0.24, 0, -0.1, -0.15],
    [0.2, 0, -0.1, -0.15],
    [0.34, 0, 0.25, 0.1],
    [0.3, 0, 0.25, 0.1],
  ],
);

/** Phase + progress → continuous jump parameter J ∈ [0, 3]. */
export function jumpParam(jump) {
  if (!jump) return 0;
  const p = clamp(jump.progress ?? 0, 0, 1);
  if (jump.phase === 'takeoff') return p;
  if (jump.phase === 'flight') return 1 + p;
  return 2 + p;
}

/** Catmull-Rom over evenly spaced key poses (spacing 0.5 in J). */
export function samplePoses(keys, J, out = new Array(POSE_SIZE)) {
  const x = clamp(J / 0.5, 0, keys.length - 1);
  const i = Math.min(keys.length - 2, Math.floor(x));
  const t = x - i;
  const k0 = keys[Math.max(0, i - 1)];
  const k1 = keys[i];
  const k2 = keys[i + 1];
  const k3 = keys[Math.min(keys.length - 1, i + 2)];
  const t2 = t * t;
  const t3 = t2 * t;
  for (let j = 0; j < POSE_SIZE; j++) {
    out[j] =
      0.5 *
      (2 * k1[j] +
        (-k0[j] + k2[j]) * t +
        (2 * k0[j] - 5 * k1[j] + 4 * k2[j] - k3[j]) * t2 +
        (-k0[j] + 3 * k1[j] - 3 * k2[j] + k3[j]) * t3);
  }
  return out;
}
