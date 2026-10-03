import { describe, expect, it } from 'vitest';
import { TUNING } from '../sim/tuning.js';
import {
  allowedTime,
  formatSeconds,
  formatTime,
  idealLine,
  idealLineLength,
  isBetterResult,
  starsFor,
  timeFaults,
  toCentiseconds,
} from './scoring.js';
import { COURSES } from './courses.js';

const el = (id, x, z) => ({ id, kind: 'cross', height: 0.4, spread: 0, x, z, rot: 0 });

// Start center (0,0) → (0,30) → (40,30) → finish center (40,0): 30 + 40 + 30 = 100 m
const square = {
  id: 2,
  pace: 'canter',
  start: { a: [-3, 0], b: [3, 0], dir: [0, 1] },
  finish: { a: [37, 0], b: [43, 0], dir: [0, -1] },
  obstacles: [
    { number: 1, elements: [el('x1', 0, 30)], directed: true },
    { number: 2, elements: [el('x2a', 40, 30), el('x2b', 40, 20)], directed: true },
  ],
};

describe('Ideal line', () => {
  it('connects start center, all element centers (including a and b) and finish center', () => {
    expect(idealLineLength(square)).toBeCloseTo(30 + 40 + 10 + 20, 6);
  });

  it('includes the turn waypoints (track) per leg', () => {
    const withTrack = { ...square, track: [[], [[20, 40]], []] };
    const detour = 2 * Math.hypot(20, 10);
    expect(idealLineLength(withTrack)).toBeCloseTo(30 + detour + 10 + 20, 6);
    expect(idealLine(withTrack)[2]).toEqual([20, 40]);
  });
});

describe('allowed time (rule 33)', () => {
  it('is ideal line / medium canter speed × 1.5, rounded up', () => {
    const len = idealLineLength(square);
    expect(allowedTime(square)).toBe(Math.ceil((len / TUNING.speeds.canterMedium) * 1.5));
  });

  it('uses the medium trot speed for courses with pace "trot" (course 1)', () => {
    const trot = { ...square, pace: 'trot' };
    const len = idealLineLength(trot);
    expect(allowedTime(trot)).toBe(Math.ceil((len / TUNING.speeds.trotMedium) * 1.5));
  });

  it('does not round round values up to the next second', () => {
    // 100 m / 6 m/s × 1.5 = 25 s
    expect(allowedTime(square, 6)).toBe(25);
    expect(allowedTime(square, 5.9)).toBe(26);
  });

  it('is computed in COURSES, not hard-coded; course 1 at trot', () => {
    for (const c of COURSES) expect(c.allowedTimeS).toBe(allowedTime(c));
    expect(COURSES[0].pace).toBe('trot');
    expect(COURSES.slice(1).every((c) => c.pace === 'canter')).toBe(true);
    const p1 = COURSES[0];
    expect(p1.allowedTimeS).toBe(Math.ceil((idealLineLength(p1) / TUNING.speeds.trotMedium) * 1.5));
  });
});

describe('Time faults (rule 33)', () => {
  it.each([
    [-5000, 0],
    [0, 0],
    [10, 1],
    [3990, 1],
    [4000, 1],
    [4010, 2],
    [8000, 2],
    [8010, 3],
    [60000, 15],
  ])('%i ms over the allowed time → %i points', (overMs, faults) => {
    expect(timeFaults(overMs)).toBe(faults);
  });

  it('computes in hundredths (rounding noise does not count)', () => {
    expect(timeFaults(4000.0000001)).toBe(1);
    expect(timeFaults(0.4)).toBe(0);
  });
});

describe('Hundredths', () => {
  it('truncates like a stopwatch, without rounding noise', () => {
    expect(toCentiseconds(48279)).toBe(4827);
    expect(toCentiseconds(48269.99999999)).toBe(4827);
    expect(toCentiseconds(0)).toBe(0);
  });
});

describe('Stars (rule 36)', () => {
  it.each([
    [0, 3],
    [1, 2],
    [4, 2],
    [5, 1],
    [8, 1],
    [40, 1],
  ])('%i faults → %i stars', (faults, stars) => {
    expect(starsFor(faults)).toBe(stars);
  });
});

describe('Best result (glossary)', () => {
  it('first finished ride is always a best result', () => {
    expect(isBetterResult({ faults: 12, timeCs: 9999 }, null)).toBe(true);
    expect(isBetterResult({ faults: 12, timeCs: 9999 }, undefined)).toBe(true);
  });

  it('fewer faults wins, even with a longer time', () => {
    expect(isBetterResult({ faults: 0, timeCs: 9000 }, { faults: 4, timeCs: 4000 })).toBe(true);
    expect(isBetterResult({ faults: 8, timeCs: 3000 }, { faults: 4, timeCs: 4000 })).toBe(false);
  });

  it('with equal faults the time decides, to the hundredth', () => {
    expect(isBetterResult({ faults: 4, timeCs: 4826 }, { faults: 4, timeCs: 4827 })).toBe(true);
    expect(isBetterResult({ faults: 4, timeCs: 4827 }, { faults: 4, timeCs: 4827 })).toBe(false);
    expect(isBetterResult({ faults: 4, timeCs: 4828 }, { faults: 4, timeCs: 4827 })).toBe(false);
  });

  it('also understands ride results with itemized faults', () => {
    const result = {
      faults: { knockdowns: 0, refusals: 0, timeFaults: 1, total: 1 },
      timeCs: 5000,
    };
    expect(isBetterResult(result, { faults: 4, timeCs: 3000 })).toBe(true);
    expect(isBetterResult({ faults: 4, timeCs: 3000 }, result)).toBe(false);
  });
});

describe('Time format', () => {
  it('m:ss,hh', () => {
    expect(formatTime(4827)).toBe('0:48,27');
    expect(formatTime(6250)).toBe('1:02,50');
    expect(formatTime(5)).toBe('0:00,05');
    expect(formatTime(0)).toBe('0:00,00');
  });

  it('ss,hh s', () => {
    expect(formatSeconds(4827)).toBe('48,27 s');
    expect(formatSeconds(6250)).toBe('62,50 s');
    expect(formatSeconds(7)).toBe('0,07 s');
  });
});
