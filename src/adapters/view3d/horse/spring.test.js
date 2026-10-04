import { describe, expect, it } from 'vitest';
import {
  ACCEL_LIMITS,
  createAccelEstimator,
  createHairChain,
  createSpring,
  kickChain,
  resetChain,
  smoothTo,
  softClamp,
  stepAccelEstimator,
  stepHairChain,
  stepSpring,
} from './spring.js';

const TAIL_CFG = {
  count: 5,
  omega0: 16,
  omegaTail: 9,
  zeta: 0.35,
  gainFwd: [0.02, 0.03, 0.04, 0.04, 0.04],
  gainUp: [0.01, 0.01, 0.005, 0.0, 0.0],
  gainLat: [0.02, 0.03, 0.04, 0.04, 0.04],
  couple: 0.3,
};

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

  it('smoothTo reaches about 95 % after 4.7 / omega', () => {
    const s = createSpring();
    const omega = 10;
    for (let t = 0; t < 4.74 / omega; t += 1 / 600) smoothTo(s, 1, omega, 1 / 600);
    expect(s.x).toBeGreaterThan(0.94);
    expect(s.x).toBeLessThan(0.97);
  });

  it('softClamp is linear near zero and saturates', () => {
    expect(softClamp(0.1, 10)).toBeCloseTo(0.1, 3);
    expect(softClamp(1000, 10)).toBeCloseTo(10, 6);
    expect(softClamp(-1000, 10)).toBeCloseTo(-10, 6);
  });
});

describe('hair chain', () => {
  const run = (chain, drive, seconds, dt = 1 / 60) => {
    for (let t = 0; t < seconds; t += dt) stepHairChain(chain, drive, dt);
  };

  it('stays at rest without drive', () => {
    const c = createHairChain(TAIL_CFG);
    run(c, { fwd: 0, up: 0, lat: 0 }, 2);
    for (const s of c.segments) {
      expect(s.pitch.x).toBe(0);
      expect(s.sway.x).toBe(0);
    }
  });

  it('forward acceleration swings the hair back (+ pitch), later segments lag', () => {
    const c = createHairChain(TAIL_CFG);
    run(c, { fwd: 4, up: 0, lat: 0 }, 0.08);
    expect(c.segments[0].pitch.x).toBeGreaterThan(0);
    // the tip has not caught up yet: it moved less than the root segment
    expect(c.segments[4].pitch.x).toBeLessThan(c.segments[0].pitch.x);
  });

  it('turning swings the hair to the outside (sideways)', () => {
    const c = createHairChain(TAIL_CFG);
    run(c, { fwd: 0, up: 0, lat: 5 }, 0.3);
    expect(c.segments[2].sway.x).toBeGreaterThan(0.02);
    expect(Math.abs(c.segments[2].pitch.x)).toBeLessThan(0.01);
  });

  it('swings after the drive stops (overshoot) and settles to rest', () => {
    const c = createHairChain(TAIL_CFG);
    run(c, { fwd: 6, up: 0, lat: 0 }, 0.4);
    const peak = c.segments[2].pitch.x;
    expect(peak).toBeGreaterThan(0);
    let crossed = false;
    for (let t = 0; t < 1.5; t += 1 / 60) {
      stepHairChain(c, { fwd: 0, up: 0, lat: 0 }, 1 / 60);
      if (c.segments[2].pitch.x < -0.005) crossed = true;
    }
    expect(crossed).toBe(true);
    run(c, { fwd: 0, up: 0, lat: 0 }, 6);
    for (const s of c.segments) {
      expect(Math.abs(s.pitch.x)).toBeLessThan(1e-3);
      expect(Math.abs(s.sway.x)).toBeLessThan(1e-3);
    }
  });

  it('stays bounded with extreme drive and long frames', () => {
    const c = createHairChain(TAIL_CFG);
    for (let i = 0; i < 200; i++) {
      stepHairChain(c, { fwd: 1e4, up: -1e4, lat: 1e4 }, i % 3 === 0 ? 0.5 : 1 / 60);
      for (const s of c.segments) {
        expect(Number.isFinite(s.pitch.x) && Number.isFinite(s.sway.x)).toBe(true);
        expect(Math.abs(s.pitch.x)).toBeLessThan(2.5);
        expect(Math.abs(s.sway.x)).toBeLessThan(2.5);
      }
    }
  });

  it('kick adds motion that fades out; reset clears it', () => {
    const c = createHairChain(TAIL_CFG);
    kickChain(c, 0, 2);
    run(c, { fwd: 0, up: 0, lat: 0 }, 0.1);
    expect(Math.abs(c.segments[3].sway.x)).toBeGreaterThan(0.01);
    run(c, { fwd: 0, up: 0, lat: 0 }, 8);
    expect(Math.abs(c.segments[3].sway.x)).toBeLessThan(1e-3);
    kickChain(c, 1, 1);
    resetChain(c);
    expect(c.segments[3].pitch.v).toBe(0);
  });
});

describe('acceleration estimator', () => {
  it('reports forward acceleration while speeding up and zero at constant speed', () => {
    const est = createAccelEstimator();
    let speed = 0;
    for (let i = 0; i < 60; i++) {
      speed += 3 / 60;
      stepAccelEstimator(est, { speed, turnRate: 0, y: 0 }, 1 / 60);
    }
    expect(est.fwd).toBeGreaterThan(2);
    expect(est.fwd).toBeLessThan(3.2);
    for (let i = 0; i < 60; i++) stepAccelEstimator(est, { speed, turnRate: 0, y: 0 }, 1 / 60);
    expect(Math.abs(est.fwd)).toBeLessThan(0.05);
  });

  it('centripetal acceleration follows speed times turn rate', () => {
    const est = createAccelEstimator();
    for (let i = 0; i < 60; i++) stepAccelEstimator(est, { speed: 5, turnRate: 1, y: 0 }, 1 / 60);
    expect(est.lat).toBeGreaterThan(4);
  });

  it('vertical acceleration of a bounce is bounded and does not need a smooth input', () => {
    const est = createAccelEstimator();
    let peak = 0;
    for (let i = 0; i < 240; i++) {
      const y = i === 100 ? 0.5 : 0; // a landing-like step in height
      stepAccelEstimator(est, { speed: 0, turnRate: 0, y }, 1 / 60);
      peak = Math.max(peak, Math.abs(est.up));
    }
    expect(peak).toBeLessThanOrEqual(ACCEL_LIMITS.up);
    expect(peak).toBeGreaterThan(1);
  });

  it('ignores a zero or negative time step', () => {
    const est = createAccelEstimator();
    stepAccelEstimator(est, { speed: 1, turnRate: 0, y: 0 }, 0);
    expect(est.primed).toBe(false);
  });
});
