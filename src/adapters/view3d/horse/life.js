// Life signs of the horse (pure, no three.js): spring-follow dynamics of tail, mane and forelock
// that are driven by the horse's real acceleration (starts and stops, turns, bobbing, landings),
// blinking, breathing with flaring nostrils, tail swishes and the shapes of the idle head
// gestures. index.js turns the result into bone rotations and shader uniforms.
import { clamp, smoothstep } from './math.js';
import { createBlinkScheduler, createRandomTimer } from './schedule.js';
import {
  createAccelEstimator,
  createHairChain,
  kickChain,
  stepAccelEstimator,
  stepHairChain,
} from './spring.js';

// Hair dynamics. Gains are radians of deflection per m/s² of body acceleration; the hair is
// under-damped (zeta < 1) so that it swings over and settles again. Later segments are softer.
const TAIL = Object.freeze({
  count: 5,
  omega0: 15,
  omegaTail: 8.5,
  zeta: 0.32,
  gainFwd: [0.012, 0.022, 0.027, 0.028, 0.028],
  gainUp: [0.006, 0.01, 0.01, 0.008, 0.006],
  gainLat: [0.01, 0.03, 0.038, 0.04, 0.04],
  windGain: [0.4, 0.7, 1, 1.2, 1.3],
  couple: 0.25,
});
const MANE = Object.freeze({
  count: 5,
  omega0: 14,
  omegaTail: 8,
  zeta: 0.34,
  gainFwd: [0.035, 0.05, 0.055, 0.055, 0.05],
  gainUp: [0.01, 0.014, 0.016, 0.016, 0.014],
  gainLat: [0.025, 0.035, 0.04, 0.04, 0.035],
  windGain: [0.5, 0.8, 1, 1.1, 1.1],
  couple: 0.3,
});
const FORELOCK = Object.freeze({
  count: 1,
  omega0: 13,
  omegaTail: 13,
  zeta: 0.3,
  gainFwd: [0.05],
  gainUp: [0.02],
  gainLat: [0.04],
  windGain: [1],
  couple: 0,
});

// Lever of the neck for the acceleration that the mane feels from the nodding of the head (m)
const NECK_LEVER = 0.9;
// A tail swish: angular velocity added to the sway of the tail segments (rad/s)
const SWISH = Object.freeze({ interval: [4, 10], velocity: 3.2, maxSpeed: 1.2 });
// Breathing rate (Hz): standing calmly … cantering (about one breath per stride)
const BREATH = Object.freeze({ idle: 0.26, walk: 0.5, trot: 0.9, canter: 1.55 });

/** options.rng: random numbers for blinking and tail swishes. */
export function createLife({ rng }) {
  return {
    body: createAccelEstimator(),
    neck: createAccelEstimator(),
    tail: createHairChain(TAIL),
    mane: createHairChain(MANE),
    forelock: createHairChain(FORELOCK),
    drive: { fwd: 0, up: 0, lat: 0 },
    maneDrive: { fwd: 0, up: 0, lat: 0 },
    wind: { pitch: 0, sway: 0 },
    time: 0,
    breathPhase: 0,
    breath: 0, // sin of the breathing cycle
    flare: 0, // nostril flare 0..1
    blinkClosure: 0, // eyelids 0 (open) … 1 (closed)
    blinker: createBlinkScheduler({ rng }),
    swish: createRandomTimer(rng, SWISH.interval),
    swishDir: 1,
  };
}

/**
 * Advances the life signs. input: {
 *   speed (m/s, signed), turnRate (rad/s, + = right), bodyY (height of the body, m),
 *   neckAngle (rad, + = down), weights (gait weights), alert (0..1: approaching a jump),
 *   exertion (0..1) }
 */
export function stepLife(life, dt, input) {
  if (!(dt > 0)) return life;
  life.time += dt;
  const t = life.time;
  const w = input.weights;

  // acceleration of the body and of the neck in the body frame
  stepAccelEstimator(
    life.body,
    { speed: input.speed, turnRate: input.turnRate, y: input.bodyY },
    dt,
  );
  stepAccelEstimator(life.neck, { speed: 0, turnRate: 0, y: input.neckAngle * NECK_LEVER }, dt);
  const d = life.drive;
  d.fwd = life.body.fwd;
  d.up = life.body.up;
  d.lat = life.body.lat;
  const md = life.maneDrive;
  md.fwd = d.fwd;
  md.up = d.up + life.neck.up;
  md.lat = d.lat;

  // a light breeze that never lets the hair rest completely
  life.wind.pitch = 0.025 * Math.sin(t * 1.3) + 0.015 * Math.sin(t * 2.9 + 1.7);
  life.wind.sway = 0.05 * Math.sin(t * 0.9 + 0.4) + 0.025 * Math.sin(t * 2.3);
  stepHairChain(life.tail, d, dt, life.wind);
  stepHairChain(life.mane, md, dt, life.wind);
  stepHairChain(life.forelock, md, dt, life.wind);

  // tail swish: only while the horse is calm
  const calm = w.halt + w.walk;
  if (life.swish.step(dt) && calm > 0.9 && Math.abs(input.speed) < SWISH.maxSpeed) {
    life.swishDir = -life.swishDir;
    kickChain(life.tail, 0, life.swishDir * SWISH.velocity);
  }

  // breathing: faster with the gait; in the canter it is tied to the stride
  const rate =
    w.halt * BREATH.idle +
    w.back * BREATH.walk +
    w.walk * BREATH.walk +
    w.trot * BREATH.trot +
    w.canter * BREATH.canter;
  life.breathPhase = (life.breathPhase + rate * dt) % 1;
  life.breath = Math.sin(2 * Math.PI * life.breathPhase);
  const effort = clamp(input.exertion ?? w.walk * 0.25 + w.trot * 0.6 + w.canter, 0, 1);
  life.flare = clamp(0.15 + 0.3 * (0.5 + 0.5 * life.breath) + 0.55 * effort, 0, 1);

  life.blinkClosure = life.blinker.step(dt, input.alert ?? 0);
  return life;
}

/**
 * Head and neck offsets of an idle gesture (g from the gesture scheduler): { yaw, pitch, neck }
 * in radians, added to the base pose; all zero at weight 0.
 */
export function gestureHead(g, out = { yaw: 0, pitch: 0, neck: 0 }) {
  out.yaw = out.pitch = out.neck = 0;
  if (!g || !g.id || g.weight <= 0) return out;
  const k = g.weight;
  if (g.id === 'shake') {
    // a quick shake from side to side that dies away
    const decay = 1 - smoothstep(0.2, g.duration, g.t);
    out.yaw = k * 0.32 * decay * Math.sin(2 * Math.PI * 3.4 * g.t);
    out.pitch = k * 0.08 * decay * Math.sin(2 * Math.PI * 6.8 * g.t);
    out.neck = k * 0.03;
  } else if (g.id === 'toss') {
    // the head flips up and comes down again
    const up = smoothstep(0, 0.28, g.t) * (1 - smoothstep(0.28, 0.9, g.t));
    out.pitch = -k * 0.5 * up;
    out.neck = -k * 0.2 * up;
    out.yaw = k * 0.06 * Math.sin(2 * Math.PI * 1.5 * g.t) * up;
  } else if (g.id === 'paw') {
    // looks down at the hoof
    out.neck = k * 0.08 * smoothstep(0, 0.4, g.t);
    out.pitch = k * 0.05;
  }
  return out;
}
