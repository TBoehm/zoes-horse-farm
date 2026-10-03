import { describe, expect, it } from 'vitest';
import { finishRide, recordJump, resetProgress } from './progress-service.js';
import { fakeStore, fixedClock } from './test-ports.js';

const NOW = '2026-01-02T03:04:05.000Z';
const clean = (courseId, timeCs = 5000) => ({
  courseId,
  timeCs,
  faults: { knockdowns: 0, refusals: 0, timeFaults: 0, total: 0 },
  stars: 3,
  cleanOxer: false,
  cleanCombination: false,
});

describe('recordJump', () => {
  it('counts the jump, saves it at once and awards "first jump"', () => {
    const store = fakeStore();
    expect(recordJump(store, fixedClock())).toEqual(['firstJump']);
    expect(store.data.progress.jumps).toBe(1);
    expect(store.data.progress.badges.firstJump).toBe(NOW);
  });

  it('awards each badge only once', () => {
    const store = fakeStore();
    recordJump(store, fixedClock());
    expect(recordJump(store, fixedClock())).toEqual([]);
    expect(store.data.progress.jumps).toBe(2);
  });

  it('awards the jump mouse with the 100th jump', () => {
    const store = fakeStore({ progress: { jumps: 99, badges: { firstJump: NOW } } });
    expect(recordJump(store, fixedClock())).toEqual(['jumpMouse']);
  });
});

describe('finishRide', () => {
  it('saves the result, reports a new best and unlocks the next course', () => {
    const store = fakeStore();
    const out = finishRide(store, fixedClock(), clean(1));
    expect(out.isNewBest).toBe(true);
    expect(out.unlockedCourse).toBe(2);
    expect(store.data.progress.unlocked).toBe(2);
    expect(store.data.progress.finishedRides).toBe(1);
    expect(store.data.progress.courses['1']).toEqual({ faults: 0, timeCs: 5000, stars: 3 });
  });

  it('awards end-of-ride badges with the clock time', () => {
    const store = fakeStore();
    const out = finishRide(store, fixedClock(), clean(1));
    expect(out.awarded).toContain('clean');
    for (const id of out.awarded) expect(store.data.progress.badges[id]).toBe(NOW);
  });

  it('keeps the better old result and reports no new best', () => {
    const store = fakeStore({
      progress: { unlocked: 2, courses: { 1: { faults: 0, timeCs: 4000, stars: 3 } } },
    });
    const out = finishRide(store, fixedClock(), clean(1, 4500));
    expect(out.isNewBest).toBe(false);
    expect(out.unlockedCourse).toBeNull();
    expect(store.data.progress.courses['1'].timeCs).toBe(4000);
  });
});

describe('resetProgress', () => {
  it('deletes only the progress fields and leaves other sections alone (rule 48)', () => {
    const store = fakeStore({
      horse: { name: 'Blitz', nameAnswered: true },
      progress: { unlocked: 3, jumps: 12, finishedRides: 4, badges: { firstJump: NOW } },
    });
    resetProgress(store);
    expect(store.data.progress).toMatchObject({
      unlocked: 1,
      jumps: 0,
      finishedRides: 0,
      courses: {},
      badges: {},
    });
    expect(store.data.horse.name).toBe('Blitz');
  });
});
