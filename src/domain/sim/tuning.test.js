import { describe, expect, it } from 'vitest';
import { TUNING } from './tuning.js';

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

  it('keeps the original game values', () => {
    expect(TUNING.control.stickDeadZone).toBe(0.12);
    expect(TUNING.rebuildDelayS).toBe(3);
    expect(TUNING.missingHintS).toBe(5);
    expect(TUNING.refusal.minStopRoom).toBe(0.05);
    expect(TUNING.jump.lastPoint.maxShareOfNear).toBe(0.9);
  });
});
