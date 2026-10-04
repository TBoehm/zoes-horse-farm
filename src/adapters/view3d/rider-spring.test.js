import { describe, expect, it } from 'vitest';
import { makeSpring, snapSpring, springStep } from './rider-spring.js';

function simulate(zeta, omega, dt, seconds, target = 1) {
  const s = makeSpring(0);
  const trace = [];
  const steps = Math.round(seconds / dt);
  for (let i = 0; i < steps; i++) trace.push(springStep(s, target, omega, dt, zeta));
  return { s, trace };
}

describe('springStep', () => {
  it('settles on the target for all damping regimes', () => {
    for (const zeta of [0.3, 1, 2.5]) {
      const { s } = simulate(zeta, 12, 1 / 60, 6);
      expect(s.x).toBeCloseTo(1, 3);
      expect(Math.abs(s.v)).toBeLessThan(1e-3);
    }
  });

  it('critically damped never overshoots', () => {
    const { trace } = simulate(1, 14, 1 / 60, 3);
    expect(Math.max(...trace)).toBeLessThanOrEqual(1 + 1e-9);
    for (let i = 1; i < trace.length; i++) expect(trace[i]).toBeGreaterThanOrEqual(trace[i - 1]);
  });

  it('under-damped overshoots a little and still settles', () => {
    const { trace } = simulate(0.4, 14, 1 / 60, 4);
    expect(Math.max(...trace)).toBeGreaterThan(1.05);
    expect(Math.abs(trace[trace.length - 1] - 1)).toBeLessThan(1e-3);
  });

  it('is independent of the step size (closed form)', () => {
    const a = simulate(0.5, 10, 1 / 120, 1).s;
    const b = simulate(0.5, 10, 1 / 30, 1).s;
    expect(a.x).toBeCloseTo(b.x, 6);
    expect(a.v).toBeCloseTo(b.v, 5);
  });

  it('stays stable for huge time steps', () => {
    for (const zeta of [0.2, 1, 3]) {
      const s = makeSpring(0);
      for (let i = 0; i < 5; i++) springStep(s, 2, 30, 1.5, zeta);
      expect(Number.isFinite(s.x)).toBe(true);
      expect(s.x).toBeCloseTo(2, 4);
    }
  });

  it('ignores a zero or negative time step and keeps a still spring still', () => {
    const s = makeSpring(0.3);
    expect(springStep(s, 5, 10, 0)).toBe(0.3);
    expect(springStep(s, 5, 10, -1)).toBe(0.3);
    expect(springStep(s, 0.3, 10, 0.5)).toBeCloseTo(0.3, 9);
  });

  it('snapSpring jumps without velocity', () => {
    const s = makeSpring(0);
    springStep(s, 1, 10, 0.05);
    snapSpring(s, 4);
    expect(s).toEqual({ x: 4, v: 0 });
  });
});
