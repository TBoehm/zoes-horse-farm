import { describe, expect, it } from 'vitest';
import { COMBI_DISTANCE, TUNING } from './tuning.js';

describe('tuning values the other layers read', () => {
  it('has the control, timing and guard values as finite positive numbers', () => {
    const values = [
      TUNING.control.stickDeadZone,
      TUNING.control.gallopEndTrotUp,
      TUNING.rebuildDelayS,
      TUNING.missingHintS,
      TUNING.refusal.minStopRoom,
      TUNING.fence.releaseGap,
      TUNING.jump.window.heightRef,
      TUNING.jump.lastPoint.maxShareOfNear,
    ];
    for (const v of values) {
      expect(Number.isFinite(v)).toBe(true);
      expect(v).toBeGreaterThan(0);
    }
  });

  it('keeps the stick dead zone small and the near-edge share below 1', () => {
    expect(TUNING.control.stickDeadZone).toBeLessThan(0.5);
    expect(TUNING.jump.lastPoint.maxShareOfNear).toBeLessThanOrEqual(1);
  });

  it('has the course-building values as positive numbers', () => {
    const c = TUNING.course;
    for (const v of [c.stride, c.takeoffLanding, c.landingFree, c.oxerSpread.tall]) {
      expect(Number.isFinite(v)).toBe(true);
      expect(v).toBeGreaterThan(0);
    }
    const heights = c.oxerSpread.byMaxHeight.map((e) => e.maxHeight);
    expect([...heights].sort((a, b) => a - b)).toEqual(heights);
  });

  it('derives the combination distance from the course-building values', () => {
    expect(COMBI_DISTANCE).toBeCloseTo(2 * TUNING.course.takeoffLanding + TUNING.course.stride, 9);
  });

  it('reads the sim step limit from tuning', () => {
    expect(TUNING.sim.maxDt).toBeGreaterThan(0);
    expect(TUNING.sim.maxDt).toBeLessThanOrEqual(0.25);
  });
});
