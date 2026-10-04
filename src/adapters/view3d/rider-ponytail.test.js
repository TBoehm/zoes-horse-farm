import { describe, expect, it } from 'vitest';
import { PONY_SEGMENTS, createPonytail, ponytailTarget, stepPonytail } from './rider-ponytail.js';

const REST = { ax: 0, ay: 0, az: 0, vx: 0, vy: 0, vz: 0, gy: -1, gz: 0 };

const total = (p, axis) => p[axis].reduce((sum, s) => sum + s.x, 0);

function run(p, input, seconds, dt = 1 / 60) {
  const steps = Math.round(seconds / dt);
  for (let i = 0; i < steps; i++) stepPonytail(p, input, dt);
  return p;
}

describe('ponytailTarget', () => {
  it('hangs straight at rest', () => {
    const t = ponytailTarget(REST);
    expect(t.pitch).toBeCloseTo(0, 9);
    expect(t.yaw).toBeCloseTo(0, 9);
  });

  it('trails downwards when the head accelerates upwards and lifts when it falls', () => {
    expect(ponytailTarget({ ...REST, ay: 12 }).pitch).toBeLessThan(-0.05);
    expect(ponytailTarget({ ...REST, ay: -12 }).pitch).toBeGreaterThan(0.05);
  });

  it('streams backwards (lifts) when moving forward, not when moving backwards', () => {
    expect(ponytailTarget({ ...REST, vz: 5 }).pitch).toBeGreaterThan(0.1);
    expect(ponytailTarget({ ...REST, vz: -5 }).pitch).toBeCloseTo(0, 9);
  });

  it('swings to the opposite side of a sideways motion', () => {
    expect(ponytailTarget({ ...REST, ax: 8 }).yaw).toBeGreaterThan(0.05);
    expect(ponytailTarget({ ...REST, ax: -8 }).yaw).toBeLessThan(-0.05);
  });

  it('keeps hanging down when the head pitches forward', () => {
    const pitched = Math.PI / 4;
    const t = ponytailTarget({ ...REST, gy: -Math.cos(pitched), gz: Math.sin(pitched) });
    expect(t.pitch).toBeCloseTo(-0.7 * pitched, 6);
  });

  it('clamps absurd inputs (teleport, frame hitch)', () => {
    const t = ponytailTarget({ ...REST, ay: 1e6, az: 1e6, ax: 1e6, vz: 1e6, vx: 1e6 });
    expect(Math.abs(t.pitch)).toBeLessThanOrEqual(1);
    expect(Math.abs(t.yaw)).toBeLessThanOrEqual(1);
  });
});

describe('stepPonytail', () => {
  it('has one spring per segment and starts at rest', () => {
    const p = createPonytail();
    expect(p.pitch).toHaveLength(PONY_SEGMENTS);
    expect(total(p, 'pitch')).toBe(0);
  });

  it('settles on the target and stays there (damped, no endless wobble)', () => {
    const p = run(createPonytail(), { ...REST, vz: 4 }, 5);
    const want = ponytailTarget({ ...REST, vz: 4 }).pitch;
    expect(total(p, 'pitch')).toBeCloseTo(want, 3);
    expect(p.pitch.every((s) => Math.abs(s.v) < 1e-3)).toBe(true);
  });

  it('the tip lags behind the root of the chain', () => {
    const p = createPonytail();
    run(p, { ...REST, ay: 15 }, 0.06);
    const want = ponytailTarget({ ...REST, ay: 15 }).pitch;
    // after 60 ms the root segment has covered more of its way than the last one
    const frac = (i) => p.pitch[i].x / (want * 0.35);
    expect(Math.abs(frac(0))).toBeGreaterThan(Math.abs(frac(PONY_SEGMENTS - 1)) * 0.9);
    expect(Math.abs(p.pitch[PONY_SEGMENTS - 1].x)).toBeLessThan(Math.abs(want) * 0.35);
  });

  it('overshoots a little on a sudden stop and swings back', () => {
    const p = createPonytail();
    run(p, { ...REST, ax: 10 }, 1);
    const peak = total(p, 'yaw');
    run(p, REST, 0.2);
    expect(total(p, 'yaw')).toBeLessThan(peak);
    let min = 0;
    for (let i = 0; i < 90; i++) {
      stepPonytail(p, REST, 1 / 60);
      min = Math.min(min, total(p, 'yaw'));
    }
    expect(min).toBeLessThan(0); // swung past the rest position
    expect(min).toBeGreaterThan(-peak * 0.5);
  });

  it('is stable at a large time step', () => {
    const p = createPonytail();
    for (let i = 0; i < 10; i++) stepPonytail(p, { ...REST, ay: 30, vz: 9 }, 0.5);
    expect(Number.isFinite(total(p, 'pitch'))).toBe(true);
    expect(Math.abs(total(p, 'pitch'))).toBeLessThanOrEqual(1.01);
  });

  it('gives the same result at 30 and 120 fps', () => {
    const a = run(createPonytail(), { ...REST, ax: 6 }, 0.5, 1 / 30);
    const b = run(createPonytail(), { ...REST, ax: 6 }, 0.5, 1 / 120);
    expect(total(a, 'yaw')).toBeCloseTo(total(b, 'yaw'), 6);
  });
});
