import { describe, expect, it } from 'vitest';
import { createRng } from './rng.js';

describe('createRng', () => {
  it('liefert für denselben Seed dieselbe Folge in [0, 1)', () => {
    const a = createRng(42);
    const b = createRng(42);
    for (let i = 0; i < 100; i++) {
      const v = a();
      expect(v).toBe(b());
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });

  it('liefert für verschiedene Seeds verschiedene Folgen', () => {
    expect(createRng(1)()).not.toBe(createRng(2)());
  });

  it('ist grob gleichverteilt', () => {
    const rng = createRng(7);
    let sum = 0;
    for (let i = 0; i < 10000; i++) sum += rng();
    expect(sum / 10000).toBeGreaterThan(0.48);
    expect(sum / 10000).toBeLessThan(0.52);
  });
});
