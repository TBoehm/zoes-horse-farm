import { describe, expect, it } from 'vitest';
import { createHeadLook, lookTarget, stepHeadLook } from './rider-look.js';

const DT = 1 / 60;

function run(look, state, seconds, calm = 0) {
  const steps = Math.round(seconds / DT);
  for (let i = 0; i < steps; i++) stepHeadLook(look, DT, state, calm);
  return look;
}

describe('lookTarget', () => {
  it('looks ahead when going straight', () => {
    const t = lookTarget({ turnRate: 0 });
    expect(t.yaw).toBe(0);
    expect(t.pitch).toBe(0);
  });

  it('turns the head into the curve (right turn = negative yaw, +X is left)', () => {
    expect(lookTarget({ turnRate: 0.8 }).yaw).toBeLessThan(-0.2);
    expect(lookTarget({ turnRate: -0.8 }).yaw).toBeGreaterThan(0.2);
  });

  it('clamps the yaw', () => {
    expect(lookTarget({ turnRate: 50 }).yaw).toBeCloseTo(-0.55, 9);
    expect(lookTarget({ turnRate: -50 }).yaw).toBeCloseTo(0.55, 9);
  });

  it('looks down at the fence on take-off and ahead in flight', () => {
    const takeoff = lookTarget({ jump: { phase: 'takeoff', progress: 0.75 } });
    const flight = lookTarget({ jump: { phase: 'flight', progress: 0.5 } });
    expect(takeoff.pitch).toBeGreaterThan(0.1);
    expect(flight.pitch).toBeLessThan(-0.05);
  });

  it('returns to level after the landing', () => {
    expect(lookTarget({ jump: { phase: 'landing', progress: 1 } }).pitch).toBeCloseTo(0, 6);
  });

  it('glances around only when calm', () => {
    let calmMax = 0;
    let busyMax = 0;
    for (let t = 0; t < 60; t += 0.25) {
      calmMax = Math.max(calmMax, Math.abs(lookTarget({}, 1, t).yaw));
      busyMax = Math.max(busyMax, Math.abs(lookTarget({}, 0, t).yaw));
    }
    expect(calmMax).toBeGreaterThan(0.03);
    expect(calmMax).toBeLessThanOrEqual(0.14 + 1e-9);
    expect(busyMax).toBe(0);
  });
});

describe('stepHeadLook', () => {
  it('eases into the curve without overshoot and back out', () => {
    const look = createHeadLook();
    const target = lookTarget({ turnRate: 0.8 }).yaw;
    let prev = 0;
    let maxStep = 0;
    for (let i = 0; i < 180; i++) {
      stepHeadLook(look, DT, { turnRate: 0.8 }, 0);
      maxStep = Math.max(maxStep, Math.abs(look.yaw.x - prev));
      prev = look.yaw.x;
      expect(look.yaw.x).toBeGreaterThanOrEqual(target - 1e-9);
    }
    expect(look.yaw.x).toBeCloseTo(target, 2);
    expect(maxStep).toBeLessThan(0.02); // < 1.2° per frame
    run(look, { turnRate: 0 }, 3);
    expect(Math.abs(look.yaw.x)).toBeLessThan(0.01);
  });

  it('does not snap when the jump phase changes', () => {
    const look = createHeadLook();
    let prev = 0;
    let maxStep = 0;
    for (let i = 0; i < 60; i++) {
      const s = i / 60;
      const jump =
        s < 0.2
          ? { phase: 'takeoff', progress: s / 0.2 }
          : { phase: 'flight', progress: (s - 0.2) / 0.55 };
      stepHeadLook(look, DT, { jump }, 0);
      maxStep = Math.max(maxStep, Math.abs(look.pitch.x - prev));
      prev = look.pitch.x;
    }
    expect(maxStep).toBeLessThan(0.02);
  });
});
