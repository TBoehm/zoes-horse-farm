import { describe, expect, it } from 'vitest';
import { TUNING } from './tuning.js';
import { updateReinBack } from './rein-back.js';
import { updateSpeed } from './movement.js';

const R = TUNING.reinBack;
const DT = 1 / 60;

const standing = (extra = {}) => ({
  speed: 0,
  gallop: false,
  settling: false,
  backHold: 0,
  backBlocked: false,
  ...extra,
});

/** One step of the same chain the sim uses: rein-back first, the normal speed update otherwise. */
function stepOnce(state, throttle) {
  if (!updateReinBack(state, throttle, DT, TUNING)) updateSpeed(state, throttle, DT, TUNING);
}

function run(state, throttle, seconds) {
  for (let t = 0; t < seconds - 1e-9; t += DT) stepOnce(state, throttle);
  return state;
}

describe('rein-back tuning (rule 9)', () => {
  it('has a pause shorter than half a second and a speed well below the walk', () => {
    expect(R.delayS).toBeGreaterThan(0);
    expect(R.delayS).toBeLessThan(0.5);
    expect(R.maxSpeed).toBeGreaterThan(0);
    expect(R.maxSpeed).toBeLessThan(TUNING.speeds.walkMax / 2);
    expect(R.accel).toBeGreaterThan(0);
    expect(R.decel).toBeGreaterThan(0);
    expect(R.blockedShare).toBeGreaterThan(0);
    expect(R.blockedShare).toBeLessThan(1);
    expect(R.rearClearance).toBeGreaterThan(0);
  });
});

describe('updateReinBack', () => {
  it('does not take over before the pause is over, then starts backing', () => {
    const s = standing();
    run(s, -1, R.delayS - 0.05);
    expect(s.speed).toBe(0);
    run(s, -1, 0.1);
    expect(s.speed).toBeLessThan(0);
  });

  it('accelerates to the maximum speed and never beyond', () => {
    const s = run(standing(), -1, 4);
    expect(s.speed).toBeCloseTo(-R.maxSpeed, 9);
  });

  it('follows the deflection (joystick): half down gives half the maximum speed', () => {
    const s = run(standing(), -0.5, 4);
    expect(s.speed).toBeCloseTo(-R.maxSpeed / 2, 9);
  });

  it('slows down to the new target when the deflection is reduced', () => {
    const s = run(standing(), -1, 3);
    run(s, -0.4, 3);
    expect(s.speed).toBeCloseTo(-R.maxSpeed * 0.4, 9);
  });

  it('stops when the throttle is released, and the pause starts anew', () => {
    const s = run(standing(), -1, 3);
    run(s, 0, 2);
    expect(s.speed).toBe(0);
    run(s, -1, R.delayS - 0.05);
    expect(s.speed).toBe(0);
  });

  it('forward throttle ends it at once and then accelerates normally', () => {
    const s = run(standing(), -1, 3);
    stepOnce(s, 1);
    expect(s.speed).toBeCloseTo(TUNING.control.speedUp * DT, 9);
  });

  it('gallop ends it at once', () => {
    const s = run(standing(), -1, 3);
    s.gallop = true;
    stepOnce(s, -1);
    expect(s.speed).toBeGreaterThanOrEqual(0);
  });

  it('braking from a walk with S does not back until the horse stands and the pause is over', () => {
    const s = standing({ speed: 1.2 });
    let backedWhileMoving = false;
    for (let t = 0; t < 1.2; t += DT) {
      const wasMoving = s.speed > 0;
      stepOnce(s, -1);
      if (wasMoving && s.speed < 0) backedWhileMoving = true;
    }
    expect(backedWhileMoving).toBe(false);
    expect(s.speed).toBeLessThan(0);
  });

  it('does not start without a negative throttle', () => {
    const s = run(standing(), 0, 2);
    expect(s.speed).toBe(0);
    expect(updateReinBack(s, 0, DT, TUNING)).toBe(false);
  });

  it('a blocked horse stays put until the throttle is released', () => {
    const s = standing({ backBlocked: true });
    run(s, -1, 2);
    expect(s.speed).toBe(0);
    run(s, 0, DT);
    expect(s.backBlocked).toBe(false);
    run(s, -1, R.delayS + 0.1);
    expect(s.speed).toBeLessThan(0);
  });

  it('does not start while the gallop is active or settling', () => {
    const galloping = run(standing({ gallop: true }), -1, 1);
    expect(galloping.speed).toBeGreaterThanOrEqual(0);
    const settling = standing({ settling: true });
    expect(updateReinBack(settling, -1, 1, TUNING)).toBe(false);
    expect(settling.speed).toBe(0);
  });
});
