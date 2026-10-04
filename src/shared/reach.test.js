import { describe, expect, it } from 'vitest';
import { softReach } from './reach.js';

describe('softReach', () => {
  const dMax = 1;

  it('is the hard clamp without a soft zone', () => {
    expect(softReach(0.5, dMax, 0)).toBe(0.5);
    expect(softReach(3, dMax, 0)).toBe(dMax);
  });

  it('keeps distances short of the soft zone as they are', () => {
    for (const d of [0.1, 0.5, 0.9]) expect(softReach(d, dMax, 0.1)).toBe(d);
  });

  it('never reaches the full stretch, however far the target is', () => {
    for (const d of [0.95, 1, 1.2, 1.5]) expect(softReach(d, dMax, 0.1)).toBeLessThan(dMax);
    expect(softReach(1e6, dMax, 0.1)).toBeLessThanOrEqual(dMax);
  });

  it('is monotonic and has no kink where the compression starts', () => {
    let prev = softReach(0, dMax, 0.1);
    let prevSlope = 1;
    for (let d = 0.001; d < 2; d += 0.001) {
      const r = softReach(d, dMax, 0.1);
      const slope = (r - prev) / 0.001;
      expect(slope).toBeGreaterThanOrEqual(0);
      // the slope falls gradually from 1 (no jump at the start of the zone)
      expect(slope).toBeLessThanOrEqual(prevSlope + 1e-6);
      expect(prevSlope - slope).toBeLessThan(0.02);
      prev = r;
      prevSlope = slope;
    }
  });
});
