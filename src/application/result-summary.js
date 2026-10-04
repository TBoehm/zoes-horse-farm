// Display model of the results screen (rules 32, 33, 36): penalty points per fault kind, badges
// and whether a next course is offered. Texts stay in the UI (keys only).
import { KNOCKDOWN_FAULTS, REFUSAL_FAULTS } from '../domain/course/scoring.js';
import { describeBadge } from './badge-overview.js';
import { nextCourse } from './course-catalog.js';

/**
 * @param {object} store store port
 * @param {{ courseId: number, result: object, isNewBest: boolean, unlockedCourse: number|null,
 *   awarded?: string[] }} finished the `finished` command params of the ride session
 */
export function summarizeResult(store, { courseId, result, isNewBest, unlockedCourse, awarded }) {
  const f = result.faults;
  return {
    courseId,
    stars: result.stars,
    timeCs: result.timeCs,
    isNewBest: Boolean(isNewBest),
    unlockedCourse: unlockedCourse ?? null,
    rows: {
      knockdowns: {
        count: f.knockdowns,
        points: f.knockdowns * KNOCKDOWN_FAULTS,
        each: KNOCKDOWN_FAULTS,
      },
      refusals: { count: f.refusals, points: f.refusals * REFUSAL_FAULTS, each: REFUSAL_FAULTS },
      timeFaults: f.timeFaults,
      total: f.total,
    },
    badges: (awarded ?? [])
      .map(describeBadge)
      .filter(Boolean)
      .map(({ id, nameKey }) => ({ id, nameKey })),
    nextCourse: nextCourse(store, courseId),
  };
}
