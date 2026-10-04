// Gaits as pure functions (no three.js): footfall sequence, cadence, duty factor and hoof paths.
// Leg index: 0 = LF (left fore), 1 = RF, 2 = LH, 3 = RH.
// Cadence (riding theory): walk ≈ 55/min, trot ≈ 80/min, canter ≈ 100/min; speed rises mainly
// through stride length. The rein-back is a slow two-beat diagonal gait (the trot's footfall,
// backwards), here ≈ 33–57/min at the backing speeds of TUNING.reinBack.
// No foot sliding: hoof travel during stance L = duty · speed / frequency (the hoof rests
// relative to the ground).
import { TUNING } from '../../../domain/sim/tuning.js';
import { clamp, lerp, smoothstep } from './math.js';

// Speed thresholds come from the simulation tuning, so animation and simulation cannot drift apart.
const { walkMax, trotMin, trotMax, canterMin, canterMax, trotMedium } = TUNING.speeds;

// Reference speeds (m/s) at which the cadences above are tuned (see `freq` of each gait)
const WALK_REF_SPEED = 1.6;
const TROT_REF_SPEED = trotMedium;
const CANTER_REF_SPEED = 6;
const BACK_REF_SPEED = TUNING.reinBack.maxSpeed;

export const GAIT_KEYS = ['halt', 'walk', 'trot', 'canter', 'back'];

const powSafe = (x, e) => Math.pow(Math.max(x, 1e-4), e);

export const GAITS = {
  walk: {
    // four-beat: LH → LF → RH → RF
    offsets: [0.25, 0.75, 0, 0.5],
    duty: (v) => lerp(0.64, 0.58, clamp(v / walkMax, 0, 1)),
    freq: (v) => 0.92 * powSafe(Math.max(v, 0.25) / WALK_REF_SPEED, 0.35),
    lift: [0.1, 0.1, 0.09, 0.09],
    flex: [1.05, 1.05, 0.45, 0.45],
    past: [0.9, 0.9, 0.7, 0.7],
    center: [0.03, 0.03, 0.0, 0.0],
    sink: 0.015,
  },
  trot: {
    // two-beat diagonal: LF + RH, then RF + LH
    offsets: [0, 0.5, 0.5, 0],
    duty: (v) => lerp(0.44, 0.36, clamp((v - trotMin) / (trotMax - trotMin), 0, 1)),
    freq: (v) => 1.33 * powSafe(Math.max(v, 1.2) / TROT_REF_SPEED, 0.25),
    lift: [0.2, 0.2, 0.15, 0.15],
    flex: [1.75, 1.75, 0.8, 0.8],
    past: [1.4, 1.4, 1.1, 1.1],
    center: [0.07, 0.07, 0.02, 0.02],
    sink: 0.045,
  },
  back: {
    // rein-back: two-beat diagonal (LF + RH, then RF + LH), no suspension phase. `reverse`: the
    // hoof path runs forward relative to the body during stance (the body moves backwards).
    reverse: true,
    offsets: [0, 0.5, 0.5, 0],
    duty: () => 0.55,
    freq: (v) => 0.55 + 0.4 * clamp(v / BACK_REF_SPEED, 0, 1),
    lift: [0.13, 0.13, 0.1, 0.1],
    flex: [1.4, 1.4, 0.65, 0.65],
    past: [1.1, 1.1, 0.8, 0.8],
    center: [0.03, 0.03, 0.0, 0.0],
    sink: 0.02,
  },
  canter: {
    // left lead: RH → (LH + RF) → LF → suspension. Right lead mirrored.
    offsets: [0.47, 0.26, 0.22, 0],
    offsetsRight: [0.26, 0.47, 0, 0.22],
    duty: (v) => lerp(0.38, 0.3, clamp((v - canterMin) / (canterMax - canterMin), 0, 1)),
    freq: (v) => 1.67 * powSafe(Math.max(v, 3) / CANTER_REF_SPEED, 0.2),
    lift: [0.27, 0.27, 0.2, 0.2],
    flex: [1.95, 1.95, 1.0, 1.0],
    past: [1.5, 1.5, 1.2, 1.2],
    center: [0.1, 0.1, 0.05, 0.05],
    sink: 0.055,
  },
};

// Shapes of the swing phase (u = 0 lift-off … 1 touch-down, result 0..1): the hoof lifts and the
// joints fold early in the swing and the hoof is placed gently. `ease` keeps the slope at
// lift-off finite (a plain power curve starts vertically, which shows as a snap at 60 fps).
const ease = (u, a) => (u * (1 + a)) / (u + a);
/** Hoof height over the swing. */
export const swingLift = (u) => Math.sin(Math.PI * ease(u, 2));
// Smooth hump over u ∈ [0, 1] with its peak at u = p and zero slope at 0, p and 1
const hump = (u, p) =>
  u < p ? smoothstep(0, 1, u / p) : 1 - smoothstep(0, 1, Math.min(1, (u - p) / (1 - p)));
/** Carpus / hock flexion over the swing. */
export const swingFlex = (u) => hump(u, 0.38);
/** Pastern fold over the swing. */
export const swingPast = (u) => hump(u, 0.36);
/** Fore/aft travel over the swing (0 … 1, slow start and end). */
export const swingTravel = (u) => u - Math.sin(2 * Math.PI * u) / (2 * Math.PI);

/** Lift scales a bit with speed within the gait (slow walk: flatter). */
export function liftScaleFor(gait, v) {
  if (gait === 'walk') return lerp(0.45, 1, clamp(v / 1.4, 0, 1));
  if (gait === 'back') return lerp(0.5, 1, clamp(v / BACK_REF_SPEED, 0, 1));
  return 1;
}

/** Maximum hoof travel per stance phase (beyond that the leg would overextend). */
export const MAX_STANCE_TRAVEL = 1.15;

export const legPhase = (phi, offset) => (((phi - offset) % 1) + 1) % 1;

export function offsetsFor(gait, lead) {
  const g = GAITS[gait];
  return gait === 'canter' && lead < 0 ? g.offsetsRight : g.offsets;
}

/** Blended stride frequency (Hz) for weights {walk, trot, canter, back} at speed v (≥ 0). */
export function blendedFrequency(weights, v) {
  let f = 0;
  for (const k of ['walk', 'trot', 'canter', 'back']) {
    if (weights[k] > 0) f += weights[k] * GAITS[k].freq(v);
  }
  return f;
}

/**
 * Hoof path of one leg in one gait. f = (blended) stride frequency, v = speed.
 * Returns dz (fore/aft relative to neutral, model space), y (lift), flex (carpus/hock flexion),
 * past (pastern fold), sink (fetlock drop), stance (true during the stance phase).
 */
export function legSample(gait, leg, phi, v, f, lead = 1, out = {}) {
  const g = GAITS[gait];
  const d = g.duty(v);
  const p = legPhase(phi, offsetsFor(gait, lead)[leg]);
  const L = f > 1e-4 ? Math.min(MAX_STANCE_TRAVEL, (d * v) / f) : 0;
  const c = g.center[leg];
  const liftScale = liftScaleFor(gait, v);
  if (p < d) {
    const u = p / d;
    out.dz = c + L / 2 - L * u;
    out.y = 0;
    out.flex = 0;
    out.past = 0;
    out.sink = g.sink * Math.sin(Math.PI * u);
    out.stance = true;
  } else {
    const u = (p - d) / (1 - d);
    out.dz = c - L / 2 + L * swingTravel(u);
    out.y = g.lift[leg] * liftScale * swingLift(u);
    out.flex = g.flex[leg] * liftScale * swingFlex(u);
    out.past = g.past[leg] * liftScale * swingPast(u);
    out.sink = 0;
    out.stance = false;
  }
  // rein-back: the same cycle mirrored around the neutral position (forward in stance)
  if (g.reverse) out.dz = 2 * c - out.dz;
  return out;
}

/** Body motion per gait: bob, pitch, head nod, roll. */
export function bodySample(gait, phi, v, lead = 1, out = {}) {
  const TAU = Math.PI * 2;
  out.bob = 0;
  out.pitch = 0;
  out.neck = 0;
  out.roll = 0;
  if (gait === 'walk') {
    const a = clamp(v / 1.6, 0, 1);
    out.bob = -0.012 * a * (0.5 + 0.5 * Math.cos(2 * TAU * (phi - 0.12)));
    // head nods twice per cycle, low when a foreleg lands
    out.neck = 0.06 * a * Math.cos(2 * TAU * (phi - 0.3));
    out.roll = 0.018 * a * Math.sin(TAU * (phi - 0.1));
  } else if (gait === 'trot') {
    const d = GAITS.trot.duty(v);
    out.bob = -0.045 * (0.5 + 0.5 * Math.cos(2 * TAU * (phi - d / 2)));
    out.neck = 0.015 * Math.cos(2 * TAU * (phi - d / 2));
    out.roll = 0.006 * Math.sin(TAU * phi);
  } else if (gait === 'back') {
    // calm: small bob once per beat, the head follows the diagonal steps a little
    const a = clamp(v / BACK_REF_SPEED, 0, 1);
    out.bob = -0.012 * a * (0.5 + 0.5 * Math.cos(2 * TAU * (phi - 0.1)));
    out.neck = 0.03 * a * Math.cos(2 * TAU * (phi - 0.1));
    out.roll = 0.008 * a * Math.sin(TAU * phi);
  } else if (gait === 'canter') {
    out.bob = -0.065 * (0.5 + 0.5 * Math.cos(TAU * (phi - 0.36)));
    // rocking: nose up when the hindquarters land, nose down on the leading foreleg
    out.pitch = 0.07 * Math.sin(TAU * (phi - 0.33));
    out.neck = 0.11 * Math.sin(TAU * (phi - 0.28));
    out.roll = 0.012 * lead * Math.sin(TAU * (phi - 0.2));
  }
  return out;
}

/** Smooth approach of a value towards a target (exponential, frame-rate independent). */
export function approach(current, target, rate, dt) {
  return target + (current - target) * Math.exp(-rate * dt);
}

/** Weight with soft fade-in/out over progress 0..1. */
export function bump(p, inEnd, outStart) {
  return smoothstep(0, inEnd, p) * (1 - smoothstep(outStart, 1, p));
}
