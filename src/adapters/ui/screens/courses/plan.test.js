import { describe, expect, it } from 'vitest';
import { COURSES } from '../../../../domain/course/courses.js';
import { POLE_LENGTH } from '../../../../domain/sim/tuning.js';
import { crossingArrow, flagPoints } from './plan.js';

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

describe('flag points of an obstacle (red on the right of the rider, white on the left)', () => {
  const at = (rot) => ({ x: 2, z: -5, rot });

  it('jumping towards +z (rot 0) the right-hand side is −x', () => {
    const { red, white } = flagPoints(at(0));
    expect(red.x).toBeLessThan(2);
    expect(white.x).toBeGreaterThan(2);
    expect(red.z).toBeCloseTo(-5);
    expect(white.z).toBeCloseTo(-5);
  });

  it('jumping towards +x (rot π/2) the right-hand side is +z', () => {
    const { red, white } = flagPoints(at(Math.PI / 2));
    expect(red.z).toBeGreaterThan(-5);
    expect(white.z).toBeLessThan(-5);
    expect(red.x).toBeCloseTo(2);
  });

  it('jumping towards −z (rot π) the right-hand side is +x', () => {
    const { red, white } = flagPoints(at(Math.PI));
    expect(red.x).toBeGreaterThan(2);
    expect(white.x).toBeLessThan(2);
  });

  it('the flags stand just outside the pole ends, symmetric to the element', () => {
    const { red, white } = flagPoints(at(0.7));
    const dRed = Math.hypot(red.x - 2, red.z + 5);
    const dWhite = Math.hypot(white.x - 2, white.z + 5);
    expect(dRed).toBeCloseTo(dWhite);
    expect(dRed).toBeGreaterThan(POLE_LENGTH / 2);
  });

  it('every course element has the red flag on the right of the rider', () => {
    for (const course of COURSES) {
      for (const obstacle of course.obstacles) {
        for (const el of obstacle.elements) {
          const { red } = flagPoints(el);
          // right-hand vector of a rider facing n = (sin, cos): n × up = (−cos, sin)
          const right = [-Math.cos(el.rot), Math.sin(el.rot)];
          expect((red.x - el.x) * right[0] + (red.z - el.z) * right[1]).toBeGreaterThan(0);
        }
      }
    }
  });
});
