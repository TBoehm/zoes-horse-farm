import { describe, expect, it } from 'vitest';
import { COURSES } from '../../../../domain/course/courses.js';
import { crossingArrow } from './plan.js';

describe('crossing arrow of the start and finish line', () => {
  const line = { a: [0, 0], b: [0, 6], dir: [1, 0] };

  it('starts and ends on the middle of the line, pointing in the riding direction', () => {
    const { from, to } = crossingArrow(line, 4);
    // the middle of the line is (0, 3); riding direction +x
    expect(from[0]).toBeCloseTo(-2);
    expect(from[1]).toBeCloseTo(3);
    expect(to[0]).toBeCloseTo(2);
    expect(to[1]).toBeCloseTo(3);
  });

  it('follows the direction of every start and finish line of the courses', () => {
    for (const course of COURSES) {
      for (const l of [course.start, course.finish]) {
        const { from, to } = crossingArrow(l, 4);
        const dx = to[0] - from[0];
        const dz = to[1] - from[1];
        expect(Math.hypot(dx, dz)).toBeCloseTo(4);
        expect(dx * l.dir[0] + dz * l.dir[1]).toBeCloseTo(4);
        // crosses the line at right angles
        const lx = l.b[0] - l.a[0];
        const lz = l.b[1] - l.a[1];
        expect(dx * lx + dz * lz).toBeCloseTo(0);
      }
    }
  });
});
