import { describe, expect, it } from 'vitest';
import { followHeading, headingLag } from './camera-math.js';

const DT = 1 / 60;

describe('followHeading (calm camera heading)', () => {
  it('moves towards the target without overshooting', () => {
    let h = 0;
    for (let i = 0; i < 120; i++) {
      h = followHeading(h, 1, 4, DT);
      expect(h).toBeGreaterThanOrEqual(0);
      expect(h).toBeLessThanOrEqual(1);
    }
    expect(h).toBeGreaterThan(0.99);
  });

  it('takes the short way around the circle (wrap at ±π)', () => {
    const h = followHeading(Math.PI - 0.05, -Math.PI + 0.05, 4, DT);
    // continues over +π instead of swinging back through 0
    expect(Math.abs(h)).toBeGreaterThan(Math.PI - 0.05);
  });

  it('keeps the result in (−π, π]', () => {
    let h = Math.PI - 0.01;
    for (let i = 0; i < 30; i++) {
      h = followHeading(h, -Math.PI + 0.3, 4, DT);
      expect(Math.abs(h)).toBeLessThanOrEqual(Math.PI + 1e-9);
    }
  });

  it('does not move at all when already on target or with dt = 0', () => {
    expect(followHeading(0.7, 0.7, 4, DT)).toBeCloseTo(0.7, 12);
    expect(followHeading(0.2, 1.5, 4, 0)).toBeCloseTo(0.2, 12);
  });

  it('limits the camera swing rate to maxRate (rad/s) on big heading jumps', () => {
    const h = followHeading(0, 2, 4, DT, 1.5);
    expect(Math.abs(h)).toBeLessThanOrEqual(1.5 * DT + 1e-12);
  });
});

describe('headingLag (steady-state lag behind a constant turn)', () => {
  it('equals turn rate / stiffness for an exponential follower', () => {
    expect(headingLag(2, 4)).toBeCloseTo(0.5, 12);
  });

  it('the follow camera stays calm: at the fastest turn the lag stays below 60 degrees', () => {
    // on-the-spot turn rate 2.7 rad/s with the follow-camera heading stiffness
    expect(headingLag(2.7, 4.5)).toBeLessThan((60 * Math.PI) / 180);
  });
});
