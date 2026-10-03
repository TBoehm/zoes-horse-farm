// Rideability of the courses and the free layout with the real simulation (concept rules 15–22,
// 25, 31, 33, 41; SRT-004 "course 1 can be done at trot without time faults").
// The autopilot is deterministic; the sloppy variant jitters the jump timing with a seeded rng.
import { describe, expect, it } from 'vitest';
import { COURSES, FREE_LAYOUT } from '../../src/domain/course/courses.js';
import { rideCourse, rideFreeObstacle } from './autopilot.js';

const jumpsOf = (course) => course.obstacles.reduce((n, o) => n + o.elements.length, 0);

function summary(id, ride) {
  return `course ${id}: finished=${ride.finished} t=${ride.timeS}s allowed=${ride.allowedS}s ${JSON.stringify(ride.result?.faults)} refusals=${ride.refusals} knockdowns=${ride.knockdowns}`;
}

describe('course 1 at trot', () => {
  const ride = rideCourse(1);

  it('finishes with 0 faults and without time faults (rule 33)', () => {
    expect(ride.finished, summary(1, ride)).toBe(true);
    expect(ride.result.faults, summary(1, ride)).toEqual({
      knockdowns: 0,
      refusals: 0,
      timeFaults: 0,
      total: 0,
    });
    expect(ride.result.stars).toBe(3);
    expect(ride.timeS).toBeLessThanOrEqual(ride.allowedS);
  });

  it('jumps every obstacle exactly once', () => {
    expect(ride.jumps).toBe(jumpsOf(COURSES[0]));
  });
});

describe.each(COURSES.filter((c) => c.id > 1))('course $id at medium canter', (course) => {
  const ride = rideCourse(course.id);

  it('has no knockdown and no refusal and finishes within the allowed time', () => {
    expect(ride.finished, summary(course.id, ride)).toBe(true);
    expect(ride.knockdowns, summary(course.id, ride)).toBe(0);
    expect(ride.refusals, summary(course.id, ride)).toBe(0);
    expect(ride.timeS, summary(course.id, ride)).toBeLessThanOrEqual(ride.allowedS);
    expect(ride.result.faults.total).toBe(0);
    expect(ride.result.stars).toBe(3);
  });

  it('jumps each element once', () => {
    expect(ride.jumps).toBe(jumpsOf(course));
  });
});

describe('combination of course 5', () => {
  it('is jumped a then b at canter with both landed and no refusal', () => {
    const course = COURSES.find((c) => c.id === 5);
    const combo = course.obstacles.find((o) => o.elements.length === 2);
    const ride = rideCourse(5);
    const landed = ride.events.filter((e) => e.type === 'landed').map((e) => e.elementId);
    const [a, b] = combo.elements.map((e) => e.id);
    expect(landed).toContain(a);
    expect(landed.indexOf(b)).toBe(landed.indexOf(a) + 1);
    expect(ride.events.filter((e) => e.type === 'refusal')).toEqual([]);
  });
});

describe('free layout (rule 41)', () => {
  const cases = FREE_LAYOUT.obstacles.flatMap((obstacle) =>
    [1, -1].map((dir) => ({ obstacle, dir, name: `${obstacle.elements[0].id} (${dir})` })),
  );

  it.each(cases)('jumps $name cleanly with the autopilot', ({ obstacle, dir }) => {
    const ride = rideFreeObstacle(obstacle, dir);
    expect(ride.finished).toBe(true);
    expect(ride.refusals).toBe(0);
    expect(ride.knockdowns).toBe(0);
    const expected = obstacle.elements.map((e) => e.id);
    expect(ride.jumped).toEqual(dir > 0 ? expected : [...expected].reverse());
  });
});

describe('sloppy rider (rule 15: forgiving for a 9-year-old)', () => {
  it.each([1, 2, 3, 4, 5, 6, 7, 8])(
    'course 1 at trot with timing jitter ±0.15 s still finishes (seed %i)',
    (seed) => {
      const ride = rideCourse(1, { jitterS: 0.15, seed });
      expect(ride.finished, summary(1, ride)).toBe(true);
      expect(ride.refusals, summary(1, ride)).toBe(0);
      expect(ride.result.faults.knockdowns, summary(1, ride)).toBe(0);
      expect(ride.timeS).toBeLessThanOrEqual(ride.allowedS);
    },
  );
});

describe('autopilot sanity (negative controls)', () => {
  it('runs into a refusal when it trots a course with verticals (rule 16)', () => {
    const ride = rideCourse(2, { canter: false, speed: 3.2, maxT: 80 });
    expect(ride.refusals).toBeGreaterThan(0);
    expect(ride.finished).toBe(false);
  });

  it('knocks poles or refuses when the timing is far off (jitter ±0.3 s on course 5)', () => {
    const faults = [1, 2, 3, 4, 5].map((seed) => {
      const ride = rideCourse(5, { jitterS: 0.3, seed });
      return ride.knockdowns + ride.refusals;
    });
    expect(Math.max(...faults)).toBeGreaterThan(0);
  });
});
