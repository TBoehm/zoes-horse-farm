// Damped springs and spring chains (pure, no three.js). Used for the secondary motion of mane,
// forelock and tail and for smoothing parameters.
//
// The step is the closed-form solution of the damped harmonic oscillator for a constant target, so
// it is stable for any time step (no blow-up at large dt) and frame-rate independent. Reference:
// https://theorangeduck.com/page/spring-roll-call and https://www.ryanjuckett.com/damped-springs/
import { clamp } from './math.js';

/** Longest time step the secondary motion integrates in one go (s); longer frames are clamped. */
export const MAX_SPRING_DT = 0.1;
/** Output limit of a spring state (guard against bad input, in the unit of the spring). */
const STATE_LIMIT = 50;
/** Largest equilibrium offset of a hair segment (rad); keeps the hair from folding over. */
const MAX_OFFSET = 1.1;

/** A spring state { x, v }: position (offset from rest) and velocity. */
export const createSpring = (x = 0, v = 0) => ({ x, v });

/**
 * Advances a spring towards `target`. omega: undamped angular frequency (rad/s), zeta: damping
 * ratio (1 = critical, < 1 swings over). Exact for a constant target and any dt ≥ 0.
 */
export function stepSpring(s, target, omega, zeta, dt) {
  if (!(dt > 0)) return s;
  const h = Math.min(dt, MAX_SPRING_DT * 4);
  const e = s.x - target;
  const v = s.v;
  const w = Math.max(omega, 1e-6);
  const z = Math.max(zeta, 0);
  let ne;
  let nv;
  if (Math.abs(z - 1) < 1e-4) {
    const ex = Math.exp(-w * h);
    const j = v + w * e;
    ne = (e + j * h) * ex;
    nv = (v - j * w * h) * ex;
  } else if (z < 1) {
    const wd = w * Math.sqrt(1 - z * z);
    const ex = Math.exp(-z * w * h);
    const c = Math.cos(wd * h);
    const sn = Math.sin(wd * h);
    const c2 = (v + z * w * e) / wd;
    ne = ex * (e * c + c2 * sn);
    nv = ex * (v * c - ((z * w * v + w * w * e) / wd) * sn);
  } else {
    const r = w * Math.sqrt(z * z - 1);
    const r1 = -w * z + r;
    const r2 = -w * z - r;
    const c2 = (v - r1 * e) / (r2 - r1);
    const c1 = e - c2;
    const e1 = Math.exp(r1 * h);
    const e2 = Math.exp(r2 * h);
    ne = c1 * e1 + c2 * e2;
    nv = c1 * r1 * e1 + c2 * r2 * e2;
  }
  s.x = clamp(target + ne, -STATE_LIMIT, STATE_LIMIT);
  s.v = clamp(nv, -STATE_LIMIT * 10, STATE_LIMIT * 10);
  return s;
}

/** Critically damped smoothing of a value towards a target (≈ 95 % after 4.7 / omega seconds). */
export function smoothTo(s, target, omega, dt) {
  return stepSpring(s, target, omega, 1, dt);
}

/** Soft limit: ≈ x for small values, saturates at ±max (keeps spikes from kicking the hair). */
export function softClamp(x, max) {
  return max * Math.tanh(x / max);
}

/**
 * Chain of hanging hair segments (tail, mane, forelock). Each segment k has two angles: `pitch`
 * (about the lateral axis, fore/aft) and `sway` (sideways). The inertial force of the body
 * acceleration pushes them away from their rest pose; later segments have a lower natural
 * frequency (they lag more) and also feel the angular acceleration of their parent segment, so a
 * motion runs through the chain like a wave.
 * cfg: { count, omega0, omegaTail (frequency of the last segment), zeta,
 *   gainFwd[k], gainUp[k], gainLat[k] (rad per m/s² of body acceleration), couple (0..1) }
 */
export function createHairChain(cfg) {
  const n = cfg.count;
  const seg = (k) => ({
    pitch: createSpring(),
    sway: createSpring(),
    omega: n > 1 ? cfg.omega0 + ((cfg.omegaTail - cfg.omega0) * k) / (n - 1) : cfg.omega0,
    accP: 0,
    accS: 0,
  });
  return { cfg, segments: Array.from({ length: n }, (_, k) => seg(k)) };
}

/**
 * Steps a hair chain. drive = { fwd, up, lat } is the body acceleration in the body frame (m/s²,
 * fwd = forward, up = vertical, lat = towards +X), wind = { pitch, sway } extra offsets (rad).
 * Hair swings against the acceleration: + fwd pushes the segments back (+ pitch).
 */
export function stepHairChain(chain, drive, dt, wind = null) {
  const { cfg, segments } = chain;
  if (!(dt > 0)) return chain;
  let parentAccP = 0;
  let parentAccS = 0;
  for (let k = 0; k < segments.length; k++) {
    const s = segments[k];
    const wp = wind ? wind.pitch * (cfg.windGain?.[k] ?? 1) : 0;
    const ws = wind ? wind.sway * (cfg.windGain?.[k] ?? 1) : 0;
    // equilibrium offset of the spring for the (constant during this step) inertial force
    const w2 = s.omega * s.omega;
    const tp = clamp(
      cfg.gainFwd[k] * drive.fwd + cfg.gainUp[k] * drive.up - (cfg.couple * parentAccP) / w2 + wp,
      -MAX_OFFSET,
      MAX_OFFSET,
    );
    const ts = clamp(
      cfg.gainLat[k] * drive.lat - (cfg.couple * parentAccS) / w2 + ws,
      -MAX_OFFSET,
      MAX_OFFSET,
    );
    const vp = s.pitch.v;
    const vs = s.sway.v;
    stepSpring(s.pitch, tp, s.omega, cfg.zeta, dt);
    stepSpring(s.sway, ts, s.omega, cfg.zeta, dt);
    // angular acceleration of this segment feeds the next one
    s.accP = (s.pitch.v - vp) / dt;
    s.accS = (s.sway.v - vs) / dt;
    parentAccP = s.accP;
    parentAccS = s.accS;
  }
  return chain;
}

/** Kicks a chain (e.g. a tail swish): adds an angular velocity to the sway of all segments. */
export function kickChain(chain, pitchV, swayV) {
  chain.segments.forEach((s, k) => {
    const f = 1 + 0.25 * k;
    s.pitch.v += pitchV * f;
    s.sway.v += swayV * f;
  });
}

/** Moves a chain back to rest. */
export function resetChain(chain) {
  for (const s of chain.segments) {
    s.pitch.x = s.pitch.v = s.sway.x = s.sway.v = 0;
    s.accP = s.accS = 0;
  }
}

/**
 * Body acceleration estimator (pure, no allocation): feeds on the body kinematics of a frame and
 * returns smoothed acceleration { fwd, up, lat } in the body frame.
 * input: { speed (m/s along the heading, − when backing), turnRate (rad/s, + = right turn),
 *   y (height of the body above its rest height, m) }
 */
export function createAccelEstimator() {
  return {
    primed: false,
    speed: 0, // low-pass filtered speed
    prevSpeed: 0,
    prevTurn: 0,
    prevY: 0,
    vy: 0,
    fwd: 0,
    up: 0,
    lat: 0,
    turnAcc: 0,
  };
}

/** Per-frame estimate; the smoothing time constants are in seconds. */
export function stepAccelEstimator(est, input, dt, limits = ACCEL_LIMITS) {
  if (!(dt > 0)) return est;
  const speed = input.speed || 0;
  const turn = input.turnRate || 0;
  const y = input.y || 0;
  if (!est.primed) {
    est.primed = true;
    est.speed = speed;
    est.prevSpeed = speed;
    est.prevTurn = turn;
    est.prevY = y;
    return est;
  }
  const k = 1 - Math.exp(-dt / limits.smooth);
  // The speed is filtered before it is differentiated: a stop that the sim does in one frame
  // (frontal fence) becomes a short strong deceleration instead of a spike of one frame, which
  // keeps its impulse (the change of speed) and lets the hair swing.
  est.speed += (speed - est.speed) * (1 - Math.exp(-dt / limits.speedTau));
  const aFwd = (est.speed - est.prevSpeed) / dt;
  const vy = (y - est.prevY) / dt;
  const aUp = (vy - est.vy) / dt;
  const turnAcc = (turn - est.prevTurn) / dt;
  est.prevSpeed = est.speed;
  est.prevTurn = turn;
  est.prevY = y;
  est.vy = vy;
  est.fwd += (softClamp(aFwd, limits.fwd) - est.fwd) * k;
  est.up += (softClamp(aUp, limits.up) - est.up) * k;
  est.turnAcc += (softClamp(turnAcc, limits.turnAcc) - est.turnAcc) * k;
  // centripetal acceleration towards the inside of the turn: + = to the right (−X)
  est.lat += (softClamp(speed * turn, limits.lat) - est.lat) * k;
  return est;
}

export const ACCEL_LIMITS = Object.freeze({
  smooth: 0.05, // s, smoothing of the accelerations
  speedTau: 0.1, // s, smoothing of the speed before it is differentiated
  fwd: 10,
  up: 60,
  lat: 10,
  turnAcc: 25,
});
