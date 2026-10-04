import { describe, expect, it } from 'vitest';
import {
  approachInfo,
  axisOf,
  crossAxisOf,
  forwardOf,
  fromLocal,
  headingOf,
  movedBackwards,
  toLocal,
  wrapAngle,
} from './geometry.js';
import { POLE_LENGTH } from './tuning.js';

const DEG = Math.PI / 180;
const APPROACH = 12;
const HALF_POLE = POLE_LENGTH / 2;

// rot 0: jump axis +z, cross axis −x (to the right when jumping in +z)
const vertical = { id: 'v', kind: 'vertical', height: 0.6, spread: 0, x: 0, z: 0, rot: 0 };
const oxer = { id: 'o', kind: 'oxer', height: 0.8, spread: 0.8, x: 0, z: 0, rot: 0 };
const horseAt = (x, z, heading = 0) => ({ x, z, heading });

describe('approachInfo', () => {
  it('straight toward the element: direction +1, distance, angle 0, on the line', () => {
    const info = approachInfo(vertical, horseAt(0, -8), APPROACH);
    expect(info).toMatchObject({ dir: 1, distance: 8, angle: 0, crossing: 0, onLine: true });
    expect(info.approaching).toBe(true);
  });

  it('works from the other side: direction −1', () => {
    const info = approachInfo(vertical, horseAt(0, 8, Math.PI), APPROACH);
    expect(info).toMatchObject({ dir: -1, distance: 8, onLine: true });
    expect(info.angle).toBeCloseTo(0, 9);
  });

  it('is null for a heading parallel to the obstacle', () => {
    expect(approachInfo(vertical, horseAt(0, -5, Math.PI / 2), APPROACH)).toBeNull();
    expect(approachInfo(vertical, horseAt(0, -5, -Math.PI / 2), APPROACH)).toBeNull();
  });

  it('is null while moving away from the element', () => {
    expect(approachInfo(vertical, horseAt(0, -5, Math.PI), APPROACH)).toBeNull();
    expect(approachInfo(vertical, horseAt(0, 5, 0), APPROACH)).toBeNull();
  });

  it('measures the distance of an oxer from the leading pole (half the spread less)', () => {
    const near = approachInfo(oxer, horseAt(0, -8), APPROACH);
    expect(near.distance).toBeCloseTo(8 - 0.4, 9);
    const far = approachInfo(oxer, horseAt(0, 8, Math.PI), APPROACH);
    expect(far.distance).toBeCloseTo(8 - 0.4, 9);
    expect(far.dir).toBe(-1);
  });

  it('reports the angle to the perpendicular of the obstacle', () => {
    const info = approachInfo(vertical, horseAt(0, -8, 20 * DEG), APPROACH);
    expect(info.angle).toBeCloseTo(20 * DEG, 9);
    const mirrored = approachInfo(vertical, horseAt(0, -8, -20 * DEG), APPROACH);
    expect(mirrored.angle).toBeCloseTo(20 * DEG, 9);
  });

  it('the angle is never negative and stays defined for a (nearly) frontal course', () => {
    const info = approachInfo(vertical, horseAt(0, -8, 1e-9), APPROACH);
    expect(info.angle).toBeGreaterThanOrEqual(0);
    expect(Number.isNaN(info.angle)).toBe(false);
  });

  it('on the line: just inside the stands, not just beyond them', () => {
    // cross axis is −x: x = +offset is local across = −offset
    const inside = approachInfo(vertical, horseAt(HALF_POLE - 0.01, -8), APPROACH);
    const beyond = approachInfo(vertical, horseAt(HALF_POLE + 0.01, -8), APPROACH);
    expect(inside.onLine).toBe(true);
    expect(beyond.onLine).toBe(false);
    expect(beyond.crossing).toBeCloseTo(-(HALF_POLE + 0.01), 9);
    const left = approachInfo(vertical, horseAt(-(HALF_POLE + 0.01), -8), APPROACH);
    expect(left.onLine).toBe(false);
    expect(left.crossing).toBeCloseTo(HALF_POLE + 0.01, 9);
  });

  it('computes the crossing point from the course, not from the horse position', () => {
    // starts on the line but drifts out: 10 m before the plane at 12° drift
    const drifting = approachInfo(vertical, horseAt(0, -10, 12 * DEG), APPROACH);
    expect(drifting.crossing).toBeCloseTo(-10 * Math.tan(12 * DEG), 9);
    expect(drifting.onLine).toBe(false);
    // starts off the line but steers back onto it
    const steering = approachInfo(vertical, horseAt(3, -10, -12 * DEG), APPROACH);
    expect(steering.onLine).toBe(true);
  });

  it('approaching needs the line and a distance strictly below the approach distance', () => {
    expect(approachInfo(vertical, horseAt(0, -(APPROACH - 0.01)), APPROACH).approaching).toBe(true);
    expect(approachInfo(vertical, horseAt(0, -APPROACH), APPROACH).approaching).toBe(false);
    const closeButOff = approachInfo(vertical, horseAt(HALF_POLE + 0.5, -3), APPROACH);
    expect(closeButOff.onLine).toBe(false);
    expect(closeButOff.approaching).toBe(false);
  });

  it('respects the element rotation', () => {
    const turned = { ...vertical, x: 4, z: 4, rot: Math.PI / 2 };
    // jump axis is +x: a horse at x = −2 heading +x (heading π/2) approaches over 6 m
    const info = approachInfo(turned, horseAt(-2, 4, Math.PI / 2), APPROACH);
    expect(info).toMatchObject({ dir: 1, onLine: true });
    expect(info.distance).toBeCloseTo(6, 9);
    expect(info.angle).toBeCloseTo(0, 6);
  });
});

describe('wrapAngle', () => {
  it('leaves angles inside (−π, π] alone', () => {
    expect(wrapAngle(0)).toBe(0);
    expect(wrapAngle(1)).toBeCloseTo(1, 12);
    expect(wrapAngle(-1)).toBeCloseTo(-1, 12);
  });

  it('keeps π and maps −π to π (upper bound included, lower excluded)', () => {
    expect(wrapAngle(Math.PI)).toBeCloseTo(Math.PI, 12);
    expect(wrapAngle(-Math.PI)).toBeCloseTo(Math.PI, 12);
  });

  it('wraps just beyond the bounds to the other side', () => {
    expect(wrapAngle(Math.PI + 0.1)).toBeCloseTo(-Math.PI + 0.1, 12);
    expect(wrapAngle(-Math.PI - 0.1)).toBeCloseTo(Math.PI - 0.1, 12);
  });

  it('wraps full turns and multiples', () => {
    expect(wrapAngle(2 * Math.PI)).toBeCloseTo(0, 12);
    expect(wrapAngle(-2 * Math.PI)).toBeCloseTo(0, 12);
    expect(wrapAngle(3 * Math.PI)).toBeCloseTo(Math.PI, 12);
    expect(wrapAngle(-3 * Math.PI)).toBeCloseTo(Math.PI, 12);
    expect(wrapAngle(7 * Math.PI + 0.5)).toBeCloseTo(-Math.PI + 0.5, 12);
  });
});

describe('element coordinates', () => {
  const el = { x: 3, z: -2, rot: 0.7 };

  it('axes are unit vectors, perpendicular to each other', () => {
    const n = axisOf(el);
    const t = crossAxisOf(el);
    expect(Math.hypot(n.x, n.z)).toBeCloseTo(1, 12);
    expect(Math.hypot(t.x, t.z)).toBeCloseTo(1, 12);
    expect(n.x * t.x + n.z * t.z).toBeCloseTo(0, 12);
  });

  it('toLocal and fromLocal are inverse', () => {
    const local = toLocal(el, 5.5, 1.25);
    const world = fromLocal(el, local.along, local.across);
    expect(world.x).toBeCloseTo(5.5, 12);
    expect(world.z).toBeCloseTo(1.25, 12);
  });

  it('forwardOf and headingOf are inverse', () => {
    for (const h of [0, 1, -2.5, Math.PI / 2]) {
      const f = forwardOf(h);
      expect(headingOf(f.x, f.z)).toBeCloseTo(h, 12);
    }
  });
});

describe('movedBackwards', () => {
  // heading 0 looks to +z
  it('is true when the displacement points against the heading', () => {
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0, z: -0.1 }, 0)).toBe(true);
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0.1, z: 0 }, -Math.PI / 2)).toBe(true);
  });

  it('is false when moving forward, sideways or standing', () => {
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0, z: 0.1 }, 0)).toBe(false);
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0.1, z: 0 }, 0)).toBe(false);
    expect(movedBackwards({ x: 1, z: 2 }, { x: 1, z: 2 }, 0)).toBe(false);
  });

  it('is false without a usable heading', () => {
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0, z: -1 }, undefined)).toBe(false);
    expect(movedBackwards({ x: 0, z: 0 }, { x: 0, z: -1 }, Number.NaN)).toBe(false);
  });
});
