import { describe, expect, it } from 'vitest';
import { breathing, createPat, stepPat } from './rider-life.js';

const DT = 1 / 60;

describe('breathing', () => {
  it('moves gently and periodically', () => {
    const values = [];
    for (let t = 0; t < 8; t += 0.05) values.push(breathing(t, 1).chest);
    expect(Math.max(...values)).toBeGreaterThan(0.01);
    expect(Math.max(...values)).toBeLessThan(0.03);
    expect(Math.min(...values)).toBeLessThan(-0.01);
  });

  it('is calmer in motion: no weight shift, smaller breath', () => {
    let calm = 0;
    let busy = 0;
    let busyRoll = 0;
    for (let t = 0; t < 10; t += 0.05) {
      calm = Math.max(calm, Math.abs(breathing(t, 1).chest));
      busy = Math.max(busy, Math.abs(breathing(t, 0).chest));
      busyRoll = Math.max(busyRoll, Math.abs(breathing(t, 0).roll));
    }
    expect(busy).toBeLessThan(calm);
    expect(busyRoll).toBe(0);
  });
});

describe('stepPat', () => {
  const run = (p, seconds, flags) => {
    let peak = 0;
    for (let i = 0; i < Math.round(seconds / DT); i++) {
      stepPat(p, DT, flags);
      peak = Math.max(peak, p.reach);
    }
    return peak;
  };

  it('does nothing without a jump', () => {
    const p = createPat();
    expect(run(p, 5, { halted: true })).toBe(0);
  });

  it('pats once when the horse stands after a jump, then stops', () => {
    const p = createPat();
    run(p, 0.5, { jumping: true });
    expect(run(p, 3, { halted: true })).toBeGreaterThan(0.9);
    run(p, 0.5, { halted: true });
    expect(p.reach).toBeLessThan(0.01);
    expect(run(p, 3, { halted: true })).toBeLessThan(1e-6);
  });

  it('does not pat when the horse keeps going or stops long after the jump', () => {
    const p = createPat();
    run(p, 0.5, { jumping: true });
    expect(run(p, 12, {})).toBe(0);
    expect(run(p, 3, { halted: true })).toBeLessThan(1e-6);
  });

  it('takes the hand back quickly and smoothly when the ride continues', () => {
    const p = createPat();
    run(p, 0.2, { jumping: true });
    run(p, 0.6, { halted: true });
    expect(p.reach).toBeGreaterThan(0.5);
    let prev = p.reach;
    let maxStep = 0;
    for (let i = 0; i < 30; i++) {
      stepPat(p, DT, {});
      maxStep = Math.max(maxStep, Math.abs(p.reach - prev));
      prev = p.reach;
    }
    expect(maxStep).toBeLessThan(0.12); // no jump
    expect(p.reach).toBeLessThan(0.05); // but gone within half a second
  });

  it('reach rises and falls smoothly', () => {
    const p = createPat();
    run(p, 0.2, { jumping: true });
    let prev = 0;
    let maxStep = 0;
    for (let i = 0; i < 150; i++) {
      stepPat(p, DT, { halted: true });
      maxStep = Math.max(maxStep, Math.abs(p.reach - prev));
      prev = p.reach;
    }
    expect(maxStep).toBeLessThan(0.1);
  });
});
