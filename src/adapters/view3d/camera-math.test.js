import { describe, expect, it } from 'vitest';
import {
  FOLLOW_HEADING_MAX_RATE,
  FOLLOW_HEADING_STIFFNESS,
  followHeading,
  RIDER_HEADING_STIFFNESS,
} from './camera-math.js';
import { TUNING } from '../../domain/sim/tuning.js';
import { wrapAngle } from '../../domain/sim/geometry.js';

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

describe('camera heading constants (SRT-009: on-the-spot turns)', () => {
  const MAX_TURN = TUNING.control.turnInPlace; // fastest the player can turn the horse (rad/s)

  /** Holds a constant turn rate for `seconds` and returns the largest lag (rad) seen. */
  function maxLag(turnRate, seconds, stiffness, maxRate) {
    let horse = 0;
    let cam = 0;
    let worst = 0;
    for (let i = 0; i < Math.round(seconds / DT); i++) {
      horse = wrapAngle(horse + turnRate * DT);
      cam = followHeading(cam, horse, stiffness, DT, maxRate);
      worst = Math.max(worst, Math.abs(wrapAngle(horse - cam)));
    }
    return worst;
  }

  it('the follow camera keeps up with the fastest turn: the lag stays bounded for 20 s', () => {
    const steady = MAX_TURN / FOLLOW_HEADING_STIFFNESS;
    const lag = maxLag(MAX_TURN, 20, FOLLOW_HEADING_STIFFNESS, FOLLOW_HEADING_MAX_RATE);
    // near the steady-state value of an exponential follower, never a whole turn behind
    expect(lag).toBeLessThan(steady * 1.1);
    expect(lag).toBeLessThan((60 * Math.PI) / 180);
  });

  it('the rider view keeps up with the fastest turn too', () => {
    const steady = MAX_TURN / RIDER_HEADING_STIFFNESS;
    expect(maxLag(MAX_TURN, 20, RIDER_HEADING_STIFFNESS, Infinity)).toBeLessThan(steady * 1.1);
  });

  it('the swing-rate cap lies safely above the fastest player turn', () => {
    expect(FOLLOW_HEADING_MAX_RATE).toBeGreaterThan(MAX_TURN * 1.1);
  });

  it('still limits the swing on a sudden big heading jump', () => {
    const h = followHeading(0, Math.PI, FOLLOW_HEADING_STIFFNESS, DT, FOLLOW_HEADING_MAX_RATE);
    expect(Math.abs(h)).toBeLessThanOrEqual(FOLLOW_HEADING_MAX_RATE * DT + 1e-12);
  });
});
