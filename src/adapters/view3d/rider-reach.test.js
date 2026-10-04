import { describe, expect, it } from 'vitest';
import { softReach } from './rider-reach.js';

describe('softReach', () => {
  const l1 = 0.25;
  const l2 = 0.31;

  it('leaves comfortable distances untouched', () => {
    for (const d of [0.1, 0.3, 0.4, 0.47]) expect(softReach(d, l1, l2)).toBeCloseTo(d, 9);
  });

  it('never reaches full extension, however far the target is', () => {
    for (const d of [0.56, 0.7, 2, 100]) {
      expect(softReach(d, l1, l2)).toBeLessThan(l1 + l2);
      expect(softReach(d, l1, l2)).toBeGreaterThan(0.5);
    }
  });

  it('is monotonic and continuous (no kink where the compression starts)', () => {
    let prev = softReach(0, l1, l2);
    for (let d = 0.01; d < 1; d += 0.01) {
      const r = softReach(d, l1, l2);
      expect(r).toBeGreaterThanOrEqual(prev - 1e-12);
      expect(r - prev).toBeLessThan(0.0101);
      prev = r;
    }
  });

  it('keeps the limb from folding completely', () => {
    expect(softReach(0, l1, l2)).toBeGreaterThanOrEqual(Math.abs(l1 - l2));
  });
});
