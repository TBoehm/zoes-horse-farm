import { describe, expect, it } from 'vitest';
import {
  PROGRESS_DEFAULTS,
  addJump,
  applyFinishedRide,
  resetProgress,
  sanitizeProgress,
} from './progress.js';

const fresh = () => sanitizeProgress(undefined);

function ride(courseId, total, timeCs, stars) {
  return {
    courseId,
    timeCs,
    faults: { knockdowns: total, refusals: 0, timeFaults: 0, total },
    stars: stars ?? (total === 0 ? 3 : total <= 4 ? 2 : 1),
    cleanOxer: false,
    cleanCombination: false,
  };
}

describe('sanitizeProgress', () => {
  it('returns defaults for missing or corrupt data', () => {
    for (const raw of [undefined, null, 'x', 42, [], true]) {
      expect(sanitizeProgress(raw)).toEqual(PROGRESS_DEFAULTS);
    }
  });

  it('keeps readable fields and replaces corrupt ones individually', () => {
    const p = sanitizeProgress({
      unlocked: 3,
      courses: { 1: { faults: 2, timeCs: 5000, stars: 2 } },
      jumps: 'viel',
      finishedRides: 7,
      badges: [],
    });
    expect(p).toEqual({
      unlocked: 3,
      courses: { 1: { faults: 2, timeCs: 5000, stars: 2 } },
      jumps: 0,
      finishedRides: 7,
      badges: {},
    });
  });

  it('clamps unlocked to 1..5 and rounds down', () => {
    expect(sanitizeProgress({ unlocked: 0 }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: -4 }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: 9 }).unlocked).toBe(5);
    expect(sanitizeProgress({ unlocked: 2.9 }).unlocked).toBe(2);
    expect(sanitizeProgress({ unlocked: NaN }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: '4' }).unlocked).toBe(1);
  });

  it('drops invalid entries of courses 1..5 (unknown keys are kept, see below)', () => {
    const p = sanitizeProgress({
      courses: {
        1: { faults: 0, timeCs: 100, stars: 3 },
        2: { faults: -1, timeCs: 100, stars: 2 },
        3: { faults: 1, timeCs: 'x', stars: 2 },
        4: { faults: 1, timeCs: 100, stars: 4 },
        5: null,
        6: { faults: 0, timeCs: 100, stars: 3 },
        0: { faults: 0, timeCs: 100, stars: 3 },
        foo: { faults: 0, timeCs: 100, stars: 3 },
      },
    });
    expect(Object.keys(p.courses).filter((k) => ['1', '2', '3', '4', '5'].includes(k))).toEqual([
      '1',
    ]);
  });

  it('validates badge dates but keeps unknown ids', () => {
    const p = sanitizeProgress({
      badges: {
        firstJump: '2026-10-03T10:00:00.000Z',
        jumpMouse: 'kein Datum',
        clean: 12345,
        future: 'beliebig',
      },
    });
    expect(p.badges).toEqual({ firstJump: '2026-10-03T10:00:00.000Z', future: 'beliebig' });
  });

  it('keeps unknown fields unchanged', () => {
    const p = sanitizeProgress({ jumps: 3, later: { a: [1, 2] }, note: 'hi' });
    expect(p.later).toEqual({ a: [1, 2] });
    expect(p.note).toBe('hi');
    expect(p.jumps).toBe(3);
  });

  it('rounds counters to whole, non-negative numbers', () => {
    const p = sanitizeProgress({ jumps: 4.7, finishedRides: -2 });
    expect(p.jumps).toBe(4);
    expect(p.finishedRides).toBe(0);
  });

  it('does not mutate the input and shares no objects with the defaults', () => {
    const raw = { courses: { 1: { faults: 0, timeCs: 1, stars: 3 } } };
    const copy = JSON.parse(JSON.stringify(raw));
    const p = sanitizeProgress(raw);
    expect(raw).toEqual(copy);
    expect(p.courses).not.toBe(raw.courses);
    expect(sanitizeProgress(undefined).courses).not.toBe(PROGRESS_DEFAULTS.courses);
  });

  it('keeps unknown keys in courses untouched (rule 47), but still cleans courses 1..5', () => {
    const future = { faults: 'x', note: [1, 2] };
    const p = sanitizeProgress({
      courses: {
        1: { faults: 2, timeCs: 5000, stars: 2 },
        2: { faults: -1, timeCs: 5000, stars: 2 },
        6: future,
        extra: 'text',
      },
    });
    expect(p.courses).toEqual({
      1: { faults: 2, timeCs: 5000, stars: 2 },
      6: future,
      extra: 'text',
    });
    expect(p.courses['6']).toBe(future);
  });

  it('keeps an unknown courses key named __proto__ as plain data', () => {
    const raw = JSON.parse(
      '{"courses":{"__proto__":{"x":1},"1":{"faults":0,"timeCs":1,"stars":3}}}',
    );
    const p = sanitizeProgress(raw);
    expect(Object.getPrototypeOf(p.courses)).toBe(Object.prototype);
    expect(Object.keys(p.courses).sort()).toEqual(['1', '__proto__']);
    expect({}.x).toBeUndefined();
  });

  it('survives keys such as __proto__ from JSON', () => {
    const raw = JSON.parse('{"badges":{"__proto__":{"x":1}},"__proto__":{"y":2}}');
    const p = sanitizeProgress(raw);
    expect(Object.getPrototypeOf(p)).toBe(Object.prototype);
    expect({}.y).toBeUndefined();
  });
});

describe('applyFinishedRide', () => {
  it('first finished ride is always a best and stores faults, time, stars', () => {
    const r = applyFinishedRide(fresh(), ride(1, 8, 7000));
    expect(r.isNewBest).toBe(true);
    expect(r.progress.courses['1']).toEqual({ faults: 8, timeCs: 7000, stars: 1 });
  });

  it('replaces the best only if better', () => {
    let p = applyFinishedRide(fresh(), ride(1, 4, 6000)).progress;
    const worse = applyFinishedRide(p, ride(1, 4, 6001));
    expect(worse.isNewBest).toBe(false);
    expect(worse.progress.courses['1']).toEqual({ faults: 4, timeCs: 6000, stars: 2 });
    const better = applyFinishedRide(worse.progress, ride(1, 4, 5999));
    expect(better.isNewBest).toBe(true);
    expect(better.progress.courses['1']).toMatchObject({ faults: 4, timeCs: 5999 });
    p = applyFinishedRide(better.progress, ride(1, 1, 9000)).progress;
    expect(p.courses['1']).toMatchObject({ faults: 1, timeCs: 9000 });
  });

  it('best stars are the maximum, even if the ride was worse', () => {
    let p = applyFinishedRide(fresh(), ride(1, 0, 6000)).progress;
    p = applyFinishedRide(p, ride(1, 9, 5000)).progress;
    expect(p.courses['1'].stars).toBe(3);
    expect(p.courses['1']).toMatchObject({ faults: 0, timeCs: 6000 });
    let q = applyFinishedRide(fresh(), ride(2, 9, 5000)).progress;
    q = applyFinishedRide(q, ride(2, 2, 9000)).progress;
    expect(q.courses['2'].stars).toBe(2);
  });

  it('best result and stars improve independently', () => {
    // faster with the same faults: stars stay
    let p = applyFinishedRide(fresh(), ride(3, 2, 6000)).progress;
    p = applyFinishedRide(p, ride(3, 2, 5000)).progress;
    expect(p.courses['3']).toEqual({ faults: 2, timeCs: 5000, stars: 2 });
  });

  it('every finished ride unlocks the next course (rule 37)', () => {
    const r = applyFinishedRide(fresh(), ride(1, 12, 9000));
    expect(r.unlockedCourse).toBe(2);
    expect(r.progress.unlocked).toBe(2);
  });

  it('unlocks nothing if the next course is already open', () => {
    const p = { ...fresh(), unlocked: 4 };
    const r = applyFinishedRide(p, ride(2, 0, 5000));
    expect(r.unlockedCourse).toBeNull();
    expect(r.progress.unlocked).toBe(4);
  });

  it('unlocks exactly one more when the newest course is reached', () => {
    const r = applyFinishedRide({ ...fresh(), unlocked: 3 }, ride(3, 0, 5000));
    expect(r.unlockedCourse).toBe(4);
    expect(r.progress.unlocked).toBe(4);
  });

  it('caps at 5', () => {
    const r = applyFinishedRide({ ...fresh(), unlocked: 5 }, ride(5, 0, 5000));
    expect(r.unlockedCourse).toBeNull();
    expect(r.progress.unlocked).toBe(5);
    const r4 = applyFinishedRide({ ...fresh(), unlocked: 4 }, ride(4, 0, 5000));
    expect(r4.unlockedCourse).toBe(5);
    expect(r4.progress.unlocked).toBe(5);
  });

  it('counts finished rides', () => {
    let p = fresh();
    for (let i = 0; i < 3; i++) p = applyFinishedRide(p, ride(1, 0, 5000 + i)).progress;
    expect(p.finishedRides).toBe(3);
  });

  it('does not mutate the input and leaves other fields untouched', () => {
    const p = { ...fresh(), jumps: 12, extra: { a: 1 }, badges: { firstJump: '2026-01-01' } };
    const snapshot = JSON.parse(JSON.stringify(p));
    const r = applyFinishedRide(p, ride(1, 0, 5000));
    expect(p).toEqual(snapshot);
    expect(r.progress).not.toBe(p);
    expect(r.progress.courses).not.toBe(p.courses);
    expect(r.progress.jumps).toBe(12);
    expect(r.progress.extra).toEqual({ a: 1 });
    expect(r.progress.badges).toEqual({ firstJump: '2026-01-01' });
  });

  it('ignores an invalid course number', () => {
    const p = fresh();
    const r = applyFinishedRide(p, ride(9, 0, 5000));
    expect(r).toEqual({ progress: p, isNewBest: false, unlockedCourse: null });
  });
});

describe('addJump', () => {
  it('increments only jumps, immutably', () => {
    const p = fresh();
    const q = addJump(p);
    expect(q.jumps).toBe(1);
    expect(p.jumps).toBe(0);
    expect(addJump(q).jumps).toBe(2);
  });
});

describe('resetProgress', () => {
  it('resets only the fields from rule 48', () => {
    const p = {
      unlocked: 4,
      courses: { 1: { faults: 0, timeCs: 5000, stars: 3 } },
      jumps: 120,
      finishedRides: 9,
      badges: { firstJump: '2026-10-03T10:00:00.000Z', future: 'x' },
      someFutureField: { keep: true },
    };
    const r = resetProgress(p);
    expect(r).toEqual({
      unlocked: 1,
      courses: {},
      jumps: 0,
      finishedRides: 0,
      badges: {},
      someFutureField: { keep: true },
    });
    expect(p.jumps).toBe(120);
  });

  it('creates no shared state with the defaults', () => {
    const r = resetProgress(fresh());
    r.courses['1'] = { faults: 0, timeCs: 1, stars: 3 };
    expect(PROGRESS_DEFAULTS.courses).toEqual({});
  });
});
