// Random timing of small life signs (pure, no three.js): blinking, idle gestures at halt (head
// shake, toss, hoof scrape) and tail swishes. The random numbers come from an injected rng so that
// tests are deterministic.
//
// Real horses blink about 8–19 times a minute (Merkel et al., Sci. Rep. 2020); a blink takes about
// 0.12–0.15 s and attention (an approaching jump) lowers the rate.
import { clamp, smoothstep } from './math.js';

const BLINK = Object.freeze({
  interval: [3, 8], // s between blinks
  duration: [0.12, 0.15], // s from open to open again
  doubleChance: 0.15, // share of blinks followed by a second one
  doubleGap: [0.12, 0.3], // s between the two blinks of a double blink
  closeShare: 0.4, // the lid closes faster than it opens
  alertFactor: 1.5, // intervals get longer while the horse is alert
});

const between = (rng, [a, b]) => a + (b - a) * rng();

/** Eyelid closure 0 (open) … 1 (closed) for a blink at progress p ∈ [0, 1]. */
export function blinkCurve(p, closeShare = BLINK.closeShare) {
  if (p <= 0 || p >= 1) return 0;
  return p < closeShare ? smoothstep(0, closeShare, p) : 1 - smoothstep(closeShare, 1, p);
}

/**
 * Blink scheduler. step(dt, alert = 0) returns the closure of the eyelids (0..1); `alert` (0..1)
 * lengthens the intervals. Options can override BLINK values (tests).
 */
export function createBlinkScheduler({ rng, ...options }) {
  const cfg = { ...BLINK, ...options };
  let wait = between(rng, cfg.interval) * 0.5; // the first blink comes sooner
  let blinkT = -1; // elapsed time of the running blink, −1 = none
  let duration = 0;
  let pending = 0; // blinks still to come in a double blink
  let gap = 0;
  const api = {
    closure: 0,
    step(dt, alert = 0) {
      if (blinkT >= 0) {
        blinkT += dt;
        if (blinkT >= duration) {
          blinkT = -1;
          if (pending > 0) {
            pending -= 1;
            gap = between(rng, cfg.doubleGap);
          } else {
            wait = between(rng, cfg.interval) * (1 + (cfg.alertFactor - 1) * clamp(alert, 0, 1));
          }
        }
      } else if (gap > 0) {
        gap -= dt;
        if (gap <= 0) {
          gap = 0;
          blinkT = 0;
          duration = between(rng, cfg.duration);
        }
      } else {
        wait -= dt;
        if (wait <= 0) {
          blinkT = 0;
          duration = between(rng, cfg.duration);
          pending = rng() < cfg.doubleChance ? 1 : 0;
        }
      }
      api.closure = blinkT >= 0 ? blinkCurve(blinkT / duration, cfg.closeShare) : 0;
      return api.closure;
    },
  };
  return api;
}

/** Idle gestures at halt: id, duration range (s) and relative probability. */
export const IDLE_GESTURES = Object.freeze([
  Object.freeze({ id: 'shake', duration: [1.1, 1.6], chance: 1 }),
  Object.freeze({ id: 'toss', duration: [0.9, 1.3], chance: 1 }),
  Object.freeze({ id: 'paw', duration: [1.6, 2.4], chance: 1.2 }),
]);

const GESTURE = Object.freeze({
  interval: [6, 14], // s of standing still before the next gesture
  fadeIn: 0.25, // s
  fadeOut: 0.35, // s
  gateRate: 10, // 1/s, how fast a gesture is taken back when the horse has to move
});

/**
 * Gesture scheduler. step(dt, allowed) advances the timer only while `allowed` (the horse stands
 * still); if `allowed` ends during a gesture it fades out quickly and the gesture is dropped.
 * The result (a reused object) is { id | null, t (s since the start), duration, weight 0..1,
 * leg (0 or 1, the foreleg of a hoof scrape) }. weight is the envelope times the gate: it never
 * jumps.
 */
export function createGestureScheduler({ rng, gestures = IDLE_GESTURES, ...options }) {
  const cfg = { ...GESTURE, ...options };
  const total = gestures.reduce((a, g) => a + g.chance, 0);
  let wait = between(rng, cfg.interval) * 0.6;
  const out = { id: null, t: 0, duration: 0, weight: 0, leg: 0 };
  let gate = 0;
  let spec = null;
  const pick = () => {
    let r = rng() * total;
    for (const g of gestures) {
      r -= g.chance;
      if (r <= 0) return g;
    }
    return gestures[gestures.length - 1];
  };
  return {
    state: out,
    step(dt, allowed) {
      // the gate follows `allowed` smoothly (exact exponential), so weight never jumps
      gate = (allowed ? 1 : 0) + (gate - (allowed ? 1 : 0)) * Math.exp(-cfg.gateRate * dt);
      if (!spec) {
        if (allowed) {
          wait -= dt;
          if (wait <= 0) {
            spec = pick();
            out.id = spec.id;
            out.t = 0;
            out.duration = between(rng, spec.duration);
            out.leg = rng() < 0.5 ? 0 : 1;
          }
        }
        out.weight = 0;
        return out;
      }
      out.t += dt;
      const env =
        smoothstep(0, cfg.fadeIn, out.t) *
        (1 - smoothstep(out.duration - cfg.fadeOut, out.duration, out.t));
      out.weight = env * gate;
      const finished = out.t >= out.duration;
      const dropped = !allowed && gate < 0.01;
      if (finished || dropped) {
        spec = null;
        out.id = null;
        out.weight = 0;
        wait = between(rng, cfg.interval) * (dropped ? 0.5 : 1);
      }
      return out;
    },
  };
}

/** Timer with a random interval: step(dt) returns true when it fires and re-arms itself. */
export function createRandomTimer(rng, [min, max]) {
  let wait = min + (max - min) * rng();
  return {
    step(dt) {
      wait -= dt;
      if (wait > 0) return false;
      wait = min + (max - min) * rng();
      return true;
    },
  };
}
