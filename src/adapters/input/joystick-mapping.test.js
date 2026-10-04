import { describe, expect, it } from 'vitest';
import { TUNING } from '../../domain/sim/tuning.js';
import { mapStick, readStickEvent } from './joystick-mapping.js';

const STICK_DEAD_ZONE = TUNING.control.stickDeadZone;
const STEER_FULL = TUNING.control.stickSteerFull;

const deg = (d) => (d * Math.PI) / 180;

describe('mapStick (nipplejs force/angle to steer/throttle)', () => {
  it('pushing right steers right (+1), up gives full throttle', () => {
    const right = mapStick(1, deg(0));
    expect(right.steer).toBe(1);
    expect(right.throttle).toBeCloseTo(0, 9);
    const up = mapStick(1, deg(90));
    expect(up.steer).toBeCloseTo(0, 9);
    expect(up.throttle).toBeCloseTo(1, 9);
  });

  it('pushing left steers left, down brakes', () => {
    expect(mapStick(1, deg(180)).steer).toBeCloseTo(-1, 9);
    expect(mapStick(1, deg(270)).throttle).toBeCloseTo(-1, 9);
  });

  it('radial dead zone: a small deflection in any direction is neutral', () => {
    for (let angle = 0; angle < 360; angle += 15) {
      expect(mapStick(STICK_DEAD_ZONE * 0.9, deg(angle))).toEqual({ steer: 0, throttle: 0 });
    }
  });

  it('scaled radial dead zone: the output starts at about 0 just outside the dead zone', () => {
    const out = mapStick(STICK_DEAD_ZONE + 0.01, deg(90));
    expect(out.throttle).toBeGreaterThan(0);
    expect(out.throttle).toBeLessThan(0.02);
    const side = mapStick(STICK_DEAD_ZONE + 0.01, deg(0));
    expect(side.steer).toBeGreaterThan(0);
    expect(side.steer).toBeLessThan(0.05);
  });

  it('throttle is the rescaled vertical component (not amplified)', () => {
    const half = (1 - STICK_DEAD_ZONE) / 2 + STICK_DEAD_ZONE;
    expect(mapStick(half, deg(90)).throttle).toBeCloseTo(0.5, 9);
    expect(mapStick(half, deg(270)).throttle).toBeCloseTo(-0.5, 9);
  });

  it('pulling the stick fully down gives throttle -1 (brake / rein-back)', () => {
    const down = mapStick(1, deg(270));
    expect(down.throttle).toBeCloseTo(-1, 9);
    expect(Math.abs(down.steer)).toBeLessThan(1e-9);
  });

  it('full steering lock is reached at 2/3 sideways deflection at the latest', () => {
    expect(STEER_FULL).toBeLessThanOrEqual(2 / 3);
    expect(mapStick(2 / 3, deg(0)).steer).toBe(1);
    expect(mapStick(2 / 3, deg(180)).steer).toBe(-1);
    expect(mapStick(STEER_FULL, deg(0)).steer).toBeCloseTo(1, 9);
    expect(mapStick(STEER_FULL * 0.99, deg(0)).steer).toBeLessThan(1);
  });

  it('steering grows linearly between the dead zone and the full-lock deflection', () => {
    const mid = (STICK_DEAD_ZONE + STEER_FULL) / 2;
    expect(mapStick(mid, deg(0)).steer).toBeCloseTo(0.5, 9);
    let last = 0;
    for (let f = STICK_DEAD_ZONE; f <= 1; f += 0.02) {
      const steer = mapStick(f, deg(0)).steer;
      expect(steer).toBeGreaterThanOrEqual(last);
      last = steer;
    }
  });

  it('holding the stick 45 degrees forward-right steers clearly and still accelerates', () => {
    const full = mapStick(1, deg(45));
    expect(full.steer).toBeGreaterThanOrEqual(0.9);
    expect(full.throttle).toBeGreaterThan(0.6);
    const light = mapStick(0.6, deg(45));
    expect(light.steer).toBeGreaterThanOrEqual(0.5);
    expect(light.throttle).toBeGreaterThan(0.2);
    expect(mapStick(1, deg(135)).steer).toBeLessThanOrEqual(-0.9);
  });

  it('a nearly straight forward hold gives steer 0 (finger wobble) and full throttle', () => {
    const nearlyUp = mapStick(1, deg(86));
    expect(nearlyUp.steer).toBe(0);
    expect(nearlyUp.throttle).toBeCloseTo(0.997, 2);
  });

  it('a nearly straight down hold gives steer 0 and reins back', () => {
    const nearlyDown = mapStick(1, deg(266));
    expect(nearlyDown.steer).toBe(0);
    expect(nearlyDown.throttle).toBeCloseTo(-0.997, 2);
  });

  it('a near-horizontal hold gives throttle 0 (turning on the spot stays a turn)', () => {
    for (const angle of [0, 10, -10, 180, 170, 190]) {
      const out = mapStick(1, deg(angle));
      expect(out.throttle).toBe(0);
      expect(Math.abs(out.steer)).toBe(1);
    }
    expect(mapStick(STEER_FULL, deg(10)).throttle).toBe(0);
  });

  it('above the axial throttle zone the throttle starts without a jump', () => {
    // 15 degrees above horizontal: only a little throttle
    expect(mapStick(1, deg(15)).throttle).toBeCloseTo(0.074, 2);
    const edge = Math.asin(TUNING.control.stickAxialThrottle);
    expect(mapStick(1, edge - 0.001).throttle).toBe(0);
    expect(mapStick(1, edge + 0.01).throttle).toBeLessThan(0.02);
  });

  it('diagonal holds keep both components (hybrid dead zone values)', () => {
    const full = mapStick(1, deg(45));
    expect(full.steer).toBe(1);
    expect(full.throttle).toBeCloseTo(0.634, 2);
    const light = mapStick(0.6, deg(45));
    expect(light.steer).toBeCloseTo(0.667, 2);
    expect(light.throttle).toBeCloseTo(0.346, 2);
  });

  it('clamps forces above 1 and ignores invalid values', () => {
    expect(mapStick(3, deg(0)).steer).toBe(1);
    expect(mapStick(undefined, deg(0))).toEqual({ steer: 0, throttle: 0 });
    expect(mapStick(1, Number.NaN)).toEqual({ steer: 0, throttle: 0 });
    expect(mapStick(-1, deg(0))).toEqual({ steer: 0, throttle: 0 });
  });
});

describe('readStickEvent (nipplejs 1.x passes ONE argument { type, target, data })', () => {
  it('reads force and angle from evt.data', () => {
    const out = readStickEvent({ type: 'move', data: { force: 1, angle: { radian: 0 } } });
    expect(out.steer).toBe(1);
    expect(out.throttle).toBeCloseTo(0, 9);
  });

  it('never throws on events without data', () => {
    expect(readStickEvent({ type: 'move' })).toEqual({ steer: 0, throttle: 0 });
    expect(readStickEvent(undefined)).toEqual({ steer: 0, throttle: 0 });
    expect(readStickEvent({ data: { force: 1 } })).toEqual({ steer: 0, throttle: 0 });
  });
});
