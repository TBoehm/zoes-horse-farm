import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import {
  IDLE_GESTURES,
  blinkCurve,
  createBlinkScheduler,
  createGestureScheduler,
  createRandomTimer,
} from './schedule.js';

const DT = 1 / 60;

/** Runs a blink scheduler and returns [{ start, length, peak }] for every closure. */
function blinks(seed, seconds, alert = 0, options = {}) {
  const s = createBlinkScheduler({ rng: createRng(seed), ...options });
  const list = [];
  let cur = null;
  for (let t = 0; t < seconds; t += DT) {
    const c = s.step(DT, alert);
    if (c > 0) {
      if (!cur) cur = { start: t, length: 0, peak: 0 };
      cur.length += DT;
      cur.peak = Math.max(cur.peak, c);
    } else if (cur) {
      list.push(cur);
      cur = null;
    }
  }
  return list;
}

describe('blink curve', () => {
  it('opens, closes fully and opens again; closing is faster than opening', () => {
    expect(blinkCurve(0)).toBe(0);
    expect(blinkCurve(1)).toBe(0);
    expect(blinkCurve(0.4)).toBeCloseTo(1, 6);
    expect(blinkCurve(0.2)).toBeGreaterThan(0);
    expect(blinkCurve(0.2)).toBeLessThan(1);
    // closing takes 40 % of the blink, opening 60 %: at 80 % the lid is still more than half open
    expect(blinkCurve(0.1)).toBeGreaterThan(blinkCurve(0.9) - 1e-9 - 0.0);
    expect(blinkCurve(0.7)).toBeGreaterThan(0.5);
  });
});

describe('blink scheduler', () => {
  it('is deterministic for a seed', () => {
    expect(blinks(5, 60)).toEqual(blinks(5, 60));
    expect(blinks(5, 60)).not.toEqual(blinks(6, 60));
  });

  it('blinks every 3-8 s on average, each blink lasts about 0.12-0.15 s and closes fully', () => {
    const list = blinks(1, 600);
    // double blinks are two blinks 0.12-0.3 s apart: count the gaps between "groups"
    const starts = list.map((b) => b.start);
    const groups = starts.filter((s, i) => i === 0 || s - starts[i - 1] > 0.5);
    const perMinute = (groups.length / 600) * 60;
    expect(perMinute).toBeGreaterThan(7);
    expect(perMinute).toBeLessThan(21);
    for (const b of list) {
      expect(b.peak).toBeGreaterThan(0.95);
      expect(b.length).toBeGreaterThan(0.09);
      expect(b.length).toBeLessThan(0.2);
    }
    for (let i = 1; i < groups.length; i++) {
      expect(groups[i] - groups[i - 1]).toBeGreaterThan(2.9);
    }
  });

  it('sometimes blinks twice in a row', () => {
    const list = blinks(2, 900);
    const doubles = list.filter((b, i) => i > 0 && b.start - list[i - 1].start < 0.6).length;
    expect(doubles).toBeGreaterThan(3);
    expect(doubles).toBeLessThan(list.length / 2);
  });

  it('blinks less while the horse is alert', () => {
    const calm = blinks(3, 900, 0).length;
    const alert = blinks(3, 900, 1).length;
    expect(alert).toBeLessThan(calm);
  });

  it('never moves the lid by a large step in one frame', () => {
    const s = createBlinkScheduler({ rng: createRng(8) });
    let prev = 0;
    let max = 0;
    for (let t = 0; t < 120; t += DT) {
      const c = s.step(DT);
      max = Math.max(max, Math.abs(c - prev));
      prev = c;
    }
    expect(max).toBeLessThan(0.6);
  });
});

describe('gesture scheduler', () => {
  function play(seed, seconds, allowedAt = () => true) {
    const g = createGestureScheduler({ rng: createRng(seed) });
    const events = [];
    let cur = null;
    let prevW = 0;
    let maxStep = 0;
    for (let t = 0; t < seconds; t += DT) {
      const s = g.step(DT, allowedAt(t));
      maxStep = Math.max(maxStep, Math.abs(s.weight - prevW));
      prevW = s.weight;
      if (s.id) {
        if (!cur) cur = { id: s.id, start: t, leg: s.leg, peak: 0 };
        cur.peak = Math.max(cur.peak, s.weight);
      } else if (cur) {
        events.push(cur);
        cur = null;
      }
    }
    return { events, maxStep };
  }

  it('plays every kind of gesture at halt, spaced out, and fades smoothly', () => {
    const { events, maxStep } = play(4, 900);
    expect(new Set(events.map((e) => e.id))).toEqual(new Set(IDLE_GESTURES.map((g) => g.id)));
    for (let i = 1; i < events.length; i++) {
      expect(events[i].start - events[i - 1].start).toBeGreaterThan(5);
    }
    for (const e of events) expect(e.peak).toBeGreaterThan(0.95);
    expect(maxStep).toBeLessThan(0.1);
  });

  it('never starts a gesture while the horse is not allowed to (moving, jumping)', () => {
    const { events } = play(4, 300, () => false);
    expect(events).toHaveLength(0);
  });

  it('takes a running gesture back smoothly when the horse has to move, and drops it', () => {
    const g = createGestureScheduler({ rng: createRng(11), interval: [0.5, 0.5] });
    let t = 0;
    while (t < 20 && !g.step(DT, true).id) t += DT;
    while (g.state.t < 0.6) g.step(DT, true);
    expect(g.state.weight).toBeGreaterThan(0.9);
    let prev = g.state.weight;
    let frames = 0;
    while (g.state.id && frames < 600) {
      const w = g.step(DT, false).weight;
      expect(Math.abs(w - prev)).toBeLessThan(0.25);
      prev = w;
      frames++;
    }
    expect(g.state.id).toBe(null);
    expect(frames).toBeLessThan(40);
    expect(g.state.weight).toBe(0);
  });

  it('timer only counts down while allowed', () => {
    const g = createGestureScheduler({ rng: createRng(1), interval: [10, 10] });
    for (let t = 0; t < 8; t += DT) g.step(DT, true);
    for (let t = 0; t < 30; t += DT) g.step(DT, false);
    expect(g.state.id).toBe(null);
    let started = false;
    for (let t = 0; t < 10; t += DT) started = g.step(DT, true).id !== null || started;
    expect(started).toBe(true);
  });
});

describe('random timer', () => {
  it('fires within its interval and re-arms', () => {
    const timer = createRandomTimer(createRng(3), [4, 10]);
    const times = [];
    for (let t = 0; t < 200; t += DT) if (timer.step(DT)) times.push(t);
    expect(times.length).toBeGreaterThan(15);
    for (let i = 1; i < times.length; i++) {
      const gap = times[i] - times[i - 1];
      expect(gap).toBeGreaterThan(3.9);
      expect(gap).toBeLessThan(10.1);
    }
  });
});
