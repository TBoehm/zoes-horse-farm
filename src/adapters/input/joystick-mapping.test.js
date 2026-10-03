import { describe, expect, it } from 'vitest';
import { TUNING } from '../../domain/sim/tuning.js';
import { mapStick, readStickEvent } from './joystick-mapping.js';

const STICK_DEAD_ZONE = TUNING.control.stickDeadZone;

const deg = (d) => (d * Math.PI) / 180;

describe('mapStick (nipplejs force/angle to steer/throttle)', () => {
  it('pushing right steers right (+1), up gives full throttle', () => {
    expect(mapStick(1, deg(0))).toEqual({ steer: 1, throttle: 0 });
    const up = mapStick(1, deg(90));
    expect(up.steer).toBeCloseTo(0, 9);
    expect(up.throttle).toBeCloseTo(1, 9);
  });

  it('pushing left steers left, down brakes', () => {
    expect(mapStick(1, deg(180)).steer).toBeCloseTo(-1, 9);
    expect(mapStick(1, deg(270)).throttle).toBeCloseTo(-1, 9);
  });

  it('a small deflection inside the dead zone gives nothing', () => {
    expect(mapStick(STICK_DEAD_ZONE * 0.9, deg(0))).toEqual({ steer: 0, throttle: 0 });
    expect(mapStick(STICK_DEAD_ZONE * 0.9, deg(90))).toEqual({ steer: 0, throttle: 0 });
  });

  it('the dead zone applies per axis and the range above it is rescaled to 0..1', () => {
    // steady forward push with a tiny sideways error must not steer
    const nearlyUp = mapStick(1, deg(86));
    expect(nearlyUp.steer).toBe(0);
    expect(nearlyUp.throttle).toBeGreaterThan(0.9);
    // just above the dead zone the output starts at ~0 and grows linearly
    const slight = mapStick(STICK_DEAD_ZONE + 0.1, deg(0));
    expect(slight.steer).toBeCloseTo(0.1 / (1 - STICK_DEAD_ZONE), 9);
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
    expect(out).toEqual({ steer: 1, throttle: 0 });
  });

  it('never throws on events without data', () => {
    expect(readStickEvent({ type: 'move' })).toEqual({ steer: 0, throttle: 0 });
    expect(readStickEvent(undefined)).toEqual({ steer: 0, throttle: 0 });
    expect(readStickEvent({ data: { force: 1 } })).toEqual({ steer: 0, throttle: 0 });
  });
});
