import { describe, expect, it } from 'vitest';
import { summarizeResult } from './result-summary.js';
import { KNOCKDOWN_FAULTS, REFUSAL_FAULTS } from '../domain/course/scoring.js';
import { fakeStore } from '../../tests/support/test-ports.js';

const params = (over = {}) => ({
  courseId: 1,
  result: {
    courseId: 1,
    timeCs: 4827,
    faults: { knockdowns: 2, refusals: 1, timeFaults: 3, total: 15 },
    stars: 1,
  },
  isNewBest: true,
  unlockedCourse: 2,
  awarded: ['clean', 'unknownBadge'],
  ...over,
});

describe('summarizeResult', () => {
  it('turns fault counts into penalty points', () => {
    const { rows } = summarizeResult(fakeStore({ progress: { unlocked: 2 } }), params());
    expect(rows.knockdowns).toEqual({ count: 2, points: 2 * KNOCKDOWN_FAULTS });
    expect(rows.refusals).toEqual({ count: 1, points: REFUSAL_FAULTS });
    expect(rows.timeFaults).toBe(3);
    expect(rows.total).toBe(15);
  });

  it('passes through time, stars, new best and unlocked course', () => {
    const s = summarizeResult(fakeStore({ progress: { unlocked: 2 } }), params());
    expect(s).toMatchObject({ courseId: 1, timeCs: 4827, stars: 1, isNewBest: true });
    expect(s.unlockedCourse).toBe(2);
  });

  it('describes the awarded badges and skips unknown ids', () => {
    const { badges } = summarizeResult(fakeStore({ progress: { unlocked: 2 } }), params());
    expect(badges).toEqual([{ id: 'clean', nameKey: 'badge.clean.name' }]);
  });

  it('offers the next course only when it is open', () => {
    expect(summarizeResult(fakeStore({ progress: { unlocked: 2 } }), params()).nextCourse).toBe(2);
    expect(
      summarizeResult(fakeStore({ progress: { unlocked: 1 } }), params()).nextCourse,
    ).toBeNull();
  });
});
