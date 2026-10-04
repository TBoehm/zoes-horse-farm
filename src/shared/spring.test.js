import { describe, expect, it } from 'vitest';
import { createSpring, snapSpring, stepSpring } from './spring.js';

describe('stepSpring', () => {
  it('settles to the target for every damping regime', () => {
    for (const zeta of [0.2, 0.7, 1, 1.5, 4]) {
      const s = createSpring(1, 0);
      for (let i = 0; i < 600; i++) stepSpring(s, 0.25, 12, zeta, 1 / 60);
      expect(s.x).toBeCloseTo(0.25, 4);
      expect(Math.abs(s.v)).toBeLessThan(1e-3);
    }
  });

  it('critical damping does not overshoot', () => {
    const s = createSpring(0, 0);
    let max = 0;
    for (let i = 0; i < 300; i++) {
      stepSpring(s, 1, 10, 1, 1 / 60);
      max = Math.max(max, s.x);
    }
    expect(max).toBeLessThanOrEqual(1 + 1e-9);
  });

  it('under-damped spring overshoots and rings', () => {
    const s = createSpring(0, 0);
    let max = 0;
    for (let i = 0; i < 300; i++) {
      stepSpring(s, 1, 10, 0.3, 1 / 60);
      max = Math.max(max, s.x);
    }
    expect(max).toBeGreaterThan(1.2);
  });

  it('is exact: one big step equals many small steps (constant target)', () => {
    for (const zeta of [0.3, 1, 2]) {
      const a = createSpring(0.5, 2);
      const b = createSpring(0.5, 2);
      stepSpring(a, 0, 9, zeta, 0.2);
      for (let i = 0; i < 20; i++) stepSpring(b, 0, 9, zeta, 0.01);
      expect(a.x).toBeCloseTo(b.x, 6);
      expect(a.v).toBeCloseTo(b.v, 6);
    }
  });

  it('does not blow up at huge steps, stiff springs or odd input', () => {
    const s = createSpring(1, 1);
    for (const dt of [0.5, 5, 1e3, 1e-9, 0, -1]) {
      stepSpring(s, 0, 500, 0.1, dt);
      expect(Number.isFinite(s.x)).toBe(true);
      expect(Number.isFinite(s.v)).toBe(true);
      expect(Math.abs(s.x)).toBeLessThan(60);
    }
  });

  it('gives a frame of a whole second the same care: stable, and it settles', () => {
    for (const zeta of [0.2, 1, 3]) {
      const s = createSpring(0);
      for (let i = 0; i < 8; i++) stepSpring(s, 2, 30, zeta, 1.5);
      expect(Number.isFinite(s.x)).toBe(true);
      expect(s.x).toBeCloseTo(2, 3);
    }
  });

  it('ignores a zero or negative time step and keeps a still spring still', () => {
    const s = createSpring(0.3);
    expect(stepSpring(s, 5, 10, 1, 0)).toBe(s);
    expect(stepSpring(s, 5, 10, 1, -1)).toBe(s);
    expect(s.x).toBe(0.3);
    stepSpring(s, 0.3, 10, 1, 0.5);
    expect(s.x).toBeCloseTo(0.3, 9);
  });

  it('is independent of the frame rate', () => {
    const a = createSpring(0);
    const b = createSpring(0);
    for (let i = 0; i < 120; i++) stepSpring(a, 1, 10, 0.5, 1 / 120);
    for (let i = 0; i < 30; i++) stepSpring(b, 1, 10, 0.5, 1 / 30);
    expect(a.x).toBeCloseTo(b.x, 6);
    expect(a.v).toBeCloseTo(b.v, 5);
  });

  it('snapSpring jumps without velocity', () => {
    const s = createSpring(0);
    stepSpring(s, 1, 10, 1, 0.05);
    snapSpring(s, 4);
    expect(s).toEqual({ x: 4, v: 0 });
  });
});
