import { describe, expect, it } from 'vitest';
import { TUNING, ARENA } from './tuning.js';
import { forwardOf, wrapAngle } from './geometry.js';
import { gaitForSpeed, maxTurnRate, turnRadius } from './movement.js';
import { createRng } from './rng.js';
import { DEG, drive, makeSim, ofType } from './test-utils.js';

const S = TUNING.speeds;

describe('Gait from speed (rule 9)', () => {
  it('maps halt, walk and trot by speed, gallop is always canter', () => {
    expect(gaitForSpeed(0, false, S)).toBe('halt');
    expect(gaitForSpeed(0.1, false, S)).toBe('halt');
    expect(gaitForSpeed(1.0, false, S)).toBe('walk');
    expect(gaitForSpeed(S.walkMax, false, S)).toBe('walk');
    expect(gaitForSpeed(S.walkMax + 0.1, false, S)).toBe('trot');
    expect(gaitForSpeed(S.trotMax, false, S)).toBe('trot');
    expect(gaitForSpeed(0, true, S)).toBe('canter');
  });
});

describe('Speed (rules 8–10)', () => {
  it('W increases speed continuously through walk up to trot and never beyond trotMax', () => {
    const sim = makeSim([]);
    const gaits = new Set();
    drive(sim, { throttle: 1 }, { maxT: 6, onStep: (s) => gaits.add(s.horse.gait) });
    expect([...gaits]).toEqual(['halt', 'walk', 'trot']);
    expect(sim.horse.speed).toBeCloseTo(S.trotMax, 6);
  });

  it('S brakes to a halt (speed 0)', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 3 });
    drive(sim, { throttle: -1 }, { maxT: 3 });
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
  });

  it('speed is kept without input', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -20, heading: 0, speed: 2.5 });
    drive(sim, {}, { maxT: 2 });
    expect(sim.horse.speed).toBeCloseTo(2.5, 9);
    expect(sim.horse.gait).toBe('trot');
  });

  it('rate is proportional to the deflection (joystick)', () => {
    const a = makeSim([]);
    const b = makeSim([]);
    drive(a, { throttle: 1 }, { maxT: 0.5 });
    drive(b, { throttle: 0.5 }, { maxT: 0.5 });
    expect(b.horse.speed / a.horse.speed).toBeCloseTo(0.5, 2);
  });

  it('gallop: gait is canter immediately, gentle acceleration to at least canterMin', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0, speed: 2 });
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gait).toBe('canter');
    expect(sim.horse.gallop).toBe(true);
    expect(sim.horse.speed).toBeLessThan(2.2);
    drive(sim, { gallop: true }, { maxT: 1.5 });
    expect(sim.horse.speed).toBeCloseTo(S.canterMin, 6);
  });

  it('W/S control the canter speed within [canterMin, canterMax]', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -34, heading: 0, speed: S.canterMin, gallop: true });
    drive(sim, { gallop: true, throttle: 1 }, { maxT: 2 });
    expect(sim.horse.speed).toBeCloseTo(S.canterMax, 6);
    drive(sim, { gallop: true, throttle: -1 }, { maxT: 3 });
    expect(sim.horse.speed).toBeCloseTo(S.canterMin, 6);
    expect(sim.horse.gait).toBe('canter');
  });

  it('gallop off: trot, speed drops gently to working trot', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -34, heading: 0, speed: 7, gallop: true });
    sim.step(1 / 60, { gallop: false });
    expect(sim.horse.gallop).toBe(false);
    expect(sim.horse.gait).toBe('trot');
    expect(sim.horse.speed).toBeGreaterThan(6.5);
    drive(sim, {}, { maxT: 3 });
    expect(sim.horse.speed).toBeCloseTo(S.trotMedium, 6);
    expect(sim.horse.gait).toBe('trot');
  });

  it('after reset the horse only gallops after a fresh key press', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0 });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });
});

describe('Steering (rules 8, 10, 22)', () => {
  it('turns on the spot at halt; right turns to the right', () => {
    const sim = makeSim([]);
    sim.reset({ x: 1, z: 2, heading: 0 });
    const right = { x: -1, z: 0 };
    drive(sim, { steer: 1 }, { maxT: 0.5 });
    expect(sim.horse.x).toBe(1);
    expect(sim.horse.z).toBe(2);
    expect(sim.horse.turnRate).toBeGreaterThan(0);
    const f = forwardOf(sim.horse.heading);
    expect(f.x * right.x + f.z * right.z).toBeGreaterThan(0.3);
    expect(sim.horse.gait).toBe('halt');
  });

  it('steering strength is proportional to |steer|', () => {
    const a = makeSim([]);
    const b = makeSim([]);
    drive(a, { steer: -1 }, { maxT: 1 });
    drive(b, { steer: -0.5 }, { maxT: 1 });
    expect(b.horse.turnRate / a.horse.turnRate).toBeCloseTo(0.5, 3);
    expect(a.horse.turnRate).toBeLessThan(0);
  });

  it('the turn radius grows with speed', () => {
    const radii = [1, 3, 5, 8].map((v) => turnRadius(v, TUNING));
    for (let i = 1; i < radii.length; i++) expect(radii[i]).toBeGreaterThan(radii[i - 1]);
    expect(maxTurnRate(0, TUNING)).toBe(TUNING.control.turnInPlace);
  });

  it('measured turn at trot is tighter than at canter', () => {
    const measure = (speed, gallop) => {
      const sim = makeSim([]);
      sim.reset({ x: 0, z: 0, heading: 0, speed, gallop });
      drive(sim, { steer: 1, gallop }, { maxT: 1 });
      return speed / Math.abs(sim.horse.turnRate);
    };
    expect(measure(3.2, false)).toBeLessThan(measure(6, true));
  });
});

describe('Fencing (rule 24)', () => {
  it('frontal: stop, halt, gallop off with events', () => {
    const sim = makeSim([]);
    sim.reset({ x: 10, z: 0, heading: 90 * DEG, speed: 6, gallop: true });
    const { events } = drive(sim, { gallop: true }, { maxT: 3 });
    expect(ofType(events, 'fenceStop')).toHaveLength(1);
    expect(ofType(events, 'gallopEnded')).toEqual([{ type: 'gallopEnded', reason: 'fence' }]);
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
    expect(sim.horse.gallop).toBe(false);
    expect(sim.horse.x).toBeLessThanOrEqual(ARENA.width / 2 - TUNING.horse.radius + 1e-9);
  });

  it('after a fence stop it only gallops after a fresh key press', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 90 * DEG, speed: 5, gallop: true });
    drive(sim, { gallop: true }, { maxT: 2 });
    expect(sim.horse.gallop).toBe(false);
    // Shift stays held while turning on the spot: no gallop
    drive(sim, { gallop: true, steer: 1 }, { maxT: 2 });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });

  it('frontal with 30° deviation still counts as frontal', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 60 * DEG, speed: 3 });
    const { events } = drive(sim, {}, { maxT: 3 });
    expect(ofType(events, 'fenceStop')).toHaveLength(1);
  });

  it('continuing to push against the fence creates no event flood', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 90 * DEG, speed: 3 });
    const { events } = drive(sim, { throttle: 1 }, { maxT: 4 });
    expect(ofType(events, 'fenceStop')).toHaveLength(1);
    expect(sim.horse.x).toBeLessThanOrEqual(ARENA.width / 2 - TUNING.horse.radius + 1e-9);
  });

  it('oblique: slides along the wall in parallel with unchanged speed', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 40 * DEG, speed: 5, gallop: true });
    const { events } = drive(sim, { gallop: true }, { maxT: 2 });
    expect(ofType(events, 'fenceStop')).toHaveLength(0);
    expect(ofType(events, 'gallopEnded')).toHaveLength(0);
    expect(sim.horse.speed).toBeCloseTo(5, 9);
    expect(sim.horse.gallop).toBe(true);
    expect(wrapAngle(sim.horse.heading)).toBeCloseTo(0, 9);
    expect(sim.horse.x).toBeCloseTo(ARENA.width / 2 - TUNING.horse.radius, 9);
  });

  it('the horse never leaves the arena (random riding)', () => {
    const sim = makeSim([]);
    const rng = createRng(3);
    let steer = 0;
    let throttle = 1;
    const maxX = ARENA.width / 2 - TUNING.horse.radius + 1e-9;
    const maxZ = ARENA.length / 2 - TUNING.horse.radius + 1e-9;
    drive(
      sim,
      (_s, t) => {
        if (Math.round(t * 60) % 60 === 0) {
          steer = rng() * 2 - 1;
          throttle = rng() * 2 - 0.6;
        }
        return { steer, throttle, gallop: rng() < 0.98 };
      },
      {
        maxT: 120,
        onStep: (s) => {
          expect(Math.abs(s.horse.x)).toBeLessThanOrEqual(maxX);
          expect(Math.abs(s.horse.z)).toBeLessThanOrEqual(maxZ);
        },
      },
    );
  });
});
