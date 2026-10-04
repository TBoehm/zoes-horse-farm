import { describe, expect, it } from 'vitest';
import { createJointLimiter, limitJoint, snapJoint } from './joint-limit.js';

const DT = 1 / 60;
const SPEED = 20; // rad/s (0.33 rad per frame)
const ACCEL = 900; // rad/s² (0.25 rad per frame and frame)

/** Runs the limiter over `targets`; returns the output angles. */
function run(targets, dt = DT, speed = SPEED, accel = ACCEL) {
  const s = createJointLimiter();
  return targets.map((x) => limitJoint(s, x, dt, speed, accel));
}

const frames = (n) => Array.from({ length: n }, (_, i) => i);

describe('joint limiter', () => {
  it('takes the first target as it is', () => {
    expect(run([1.5])[0]).toBe(1.5);
  });

  it('leaves smooth motion untouched, without lag', () => {
    const targets = frames(120).map((i) => 0.8 * Math.sin(i * 0.08));
    const out = run(targets);
    out.forEach((x, i) => expect(x).toBeCloseTo(targets[i], 9));
  });

  it('spreads a one-frame V-kink so that the velocity changes by at most the limit', () => {
    // 0.2 rad per frame, then at once 0.2 back: a change of the velocity by 0.4 rad per frame
    let x = 0;
    const targets = [];
    for (let i = 0; i < 40; i++) {
      x += i < 20 ? 0.2 : -0.2;
      targets.push(x);
    }
    const out = run(targets);
    let worst = 0;
    for (let i = 2; i < out.length; i++) {
      worst = Math.max(worst, Math.abs(out[i] - 2 * out[i - 1] + out[i - 2]));
    }
    expect(worst).toBeLessThanOrEqual(ACCEL * DT * DT + 1e-9);
    // the second difference of the raw signal was 0.4
    expect(Math.abs(targets[20] - 2 * targets[19] + targets[18])).toBeGreaterThan(0.35);
    // and it catches up with the target afterwards
    expect(Math.abs(out[out.length - 1] - targets[targets.length - 1])).toBeLessThan(0.02);
  });

  it('never turns faster than the speed limit', () => {
    const out = run([0, 0, 0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3]);
    for (let i = 1; i < out.length; i++) {
      expect(Math.abs(out[i] - out[i - 1])).toBeLessThanOrEqual(SPEED * DT + 1e-9);
    }
    expect(out[out.length - 1]).toBeCloseTo(3, 6);
  });

  it('settles on a step without overshooting by more than a hair', () => {
    const out = run([0, ...new Array(60).fill(1)]);
    expect(Math.max(...out)).toBeLessThan(1.02);
    expect(out[out.length - 1]).toBeCloseTo(1, 4);
  });

  it('holds the angle for a step of no time and ignores bad time steps', () => {
    const s = createJointLimiter();
    limitJoint(s, 0.4, DT, SPEED, ACCEL);
    expect(limitJoint(s, 2, 0, SPEED, ACCEL)).toBe(0.4);
    expect(limitJoint(s, 2, NaN, SPEED, ACCEL)).toBe(0.4);
  });

  it('limits per second, so that a longer frame may turn further', () => {
    const slow = run([0, 5, 5, 5], 1 / 30);
    const fast = run([0, 5, 5, 5], 1 / 120);
    expect(slow[1]).toBeGreaterThan(fast[1]);
  });

  it('snaps to a value without velocity', () => {
    const s = createJointLimiter();
    limitJoint(s, 0, DT, SPEED, ACCEL);
    limitJoint(s, 1, DT, SPEED, ACCEL);
    snapJoint(s, 0.3);
    expect(s.v).toBe(0);
    expect(limitJoint(s, 0.3, DT, SPEED, ACCEL)).toBeCloseTo(0.3, 9);
  });
});
