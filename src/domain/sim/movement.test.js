import { describe, expect, it } from 'vitest';
import { TUNING, ARENA } from './tuning.js';
import { forwardOf, wrapAngle } from './geometry.js';
import { gaitForSpeed, maxTurnRate } from './movement.js';
import { createRng } from './rng.js';
import { DEG, DT, drive, makeSim, ofType } from '../../../tests/support/sim-utils.js';

const S = TUNING.speeds;

/** Turn radius (m) at full steering lock. */
const turnRadius = (speed, tuning) => speed / maxTurnRate(speed, tuning);

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

  it('negative speed is the rein-back gait (rule 9)', () => {
    expect(gaitForSpeed(-0.01, false, S)).toBe('back');
    expect(gaitForSpeed(-TUNING.reinBack.maxSpeed, false, S)).toBe('back');
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
    // S held on: it brakes to a halt first (the rein-back only follows after its pause)
    drive(sim, { throttle: -1 }, { maxT: 3, until: (s) => s.horse.speed === 0 });
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

  it('gallop ended during the strike-off (below trotMin): back to trot, not to a walk', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0 });
    sim.step(DT, { gallop: false });
    drive(sim, { gallop: true }, { maxT: 0.3 });
    expect(sim.horse.gallop).toBe(true);
    expect(sim.horse.speed).toBeLessThan(S.trotMin);
    sim.step(DT, { gallop: false });
    expect(sim.horse.gallop).toBe(false);
    drive(sim, {}, { maxT: 1 });
    expect(sim.horse.gait).toBe('trot');
    expect(sim.horse.speed).toBeCloseTo(S.trotMin, 6);
  });

  it('eases up to trot at the tuning rate (no jump in speed)', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0 });
    sim.step(DT, { gallop: false });
    drive(sim, { gallop: true }, { maxT: 0.2 });
    const v0 = sim.horse.speed;
    sim.step(DT, { gallop: false });
    expect(sim.horse.speed - v0).toBeLessThanOrEqual(TUNING.control.gallopEndTrotUp * DT + 1e-9);
    expect(sim.horse.speed).toBeGreaterThan(v0);
  });

  it('ending the gallop from a halt after a very short press still reaches trot', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0 });
    sim.step(DT, { gallop: false });
    sim.step(DT, { gallop: true });
    sim.step(DT, { gallop: false });
    drive(sim, {}, { maxT: 2 });
    expect(sim.horse.gait).toBe('trot');
  });

  it('S after the strike-off gallop ends brakes to a halt instead of trotting on', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0 });
    sim.step(DT, { gallop: false });
    drive(sim, { gallop: true }, { maxT: 0.3 });
    // S held on: it brakes to a halt first (the rein-back only follows after its pause)
    drive(sim, { throttle: -1 }, { maxT: 2, until: (s) => s.horse.speed === 0 });
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
  });

  it('a walking horse without gallop stays at its pace (no automatic trot)', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -30, heading: 0, speed: 1.0 });
    drive(sim, {}, { maxT: 2 });
    expect(sim.horse.speed).toBeCloseTo(1.0, 9);
    expect(sim.horse.gait).toBe('walk');
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

describe('Turn agility (rule 10: direct steering, plausible radii)', () => {
  // Real horses: 10 m volte (r = 5 m) at trot/walk, 20 m circle (r = 10 m) at canter, jump-off
  // turns at jumping canter about r = 6-8 m; the game may be somewhat more agile (child audience).
  it('walk: tight turn, at most 1.5 m radius (turn on the haunches)', () => {
    expect(turnRadius(1.5, TUNING)).toBeLessThanOrEqual(1.5);
  });

  it('working trot: tighter than a 10 m volte but not tighter than 2 m', () => {
    const r = turnRadius(S.trotMedium, TUNING);
    expect(r).toBeLessThanOrEqual(3);
    expect(r).toBeGreaterThanOrEqual(2);
  });

  it('jumping canter: jump-off turn radius between 4 and 6.5 m', () => {
    const r = turnRadius(S.canterMedium, TUNING);
    expect(r).toBeLessThanOrEqual(6.5);
    expect(r).toBeGreaterThanOrEqual(4);
  });

  it('lateral acceleration stays plausible at every speed (at most 7 m/s²)', () => {
    for (let v = 0.5; v <= S.canterMax; v += 0.5) {
      expect(v * maxTurnRate(v, TUNING)).toBeLessThanOrEqual(7);
    }
  });

  it('the turn rate follows the stick quickly (90 % of the target within 0.2 s)', () => {
    const sim = makeSim([]);
    drive(sim, { steer: 1 }, { maxT: 0.2 });
    expect(Math.abs(sim.horse.turnRate)).toBeGreaterThanOrEqual(0.9 * maxTurnRate(0, TUNING));
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

  it('a fence stop during the strike-off stays a halt (no trot fall-back)', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: ARENA.length / 2 - TUNING.horse.radius - 0.1, heading: 0 });
    sim.step(DT, { gallop: false });
    drive(sim, { gallop: true }, { maxT: 0.5 });
    expect(sim.horse.speed).toBe(0);
    drive(sim, {}, { maxT: 1 });
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
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

  it('oblique: the heading eases parallel to the wall instead of snapping', () => {
    const sim = makeSim([]);
    sim.reset({ x: 18, z: 0, heading: 40 * DEG, speed: 5, gallop: true });
    const headings = [];
    drive(sim, { gallop: true }, { maxT: 2, onStep: (s) => headings.push(s.horse.heading) });
    // never turns more than the slide turn rate allows within one step
    let previous = 40 * DEG;
    for (const h of headings) {
      expect(Math.abs(wrapAngle(h - previous))).toBeLessThanOrEqual(
        TUNING.fence.slideTurnRate * DT + 1e-9,
      );
      previous = h;
    }
    // it really took several steps (a snap would jump from 40° to 0° at once)
    expect(headings.filter((h) => h > 1 * DEG && h < 39 * DEG).length).toBeGreaterThan(3);
    expect(wrapAngle(sim.horse.heading)).toBeCloseTo(0, 9);
  });

  it('the hindquarters stay inside the arena when the horse turns around at the wall', () => {
    const sim = makeSim([]);
    // halt at the east wall facing it, then turn on the spot until it faces away
    sim.reset({ x: 15, z: 0, heading: 90 * DEG, speed: 5, gallop: true });
    drive(sim, { gallop: true }, { maxT: 2 });
    sim.step(DT, { gallop: false });
    const limit = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9;
    drive(
      sim,
      { steer: 1 },
      {
        maxT: 10,
        // stop as soon as it faces away (independent of the turn rate tuning)
        until: (s) => Math.abs(wrapAngle(s.horse.heading - 270 * DEG)) < 0.1,
        onStep: (s) => {
          const rear = s.horse.x - Math.sin(s.horse.heading) * TUNING.horse.rearLength;
          expect(rear).toBeLessThanOrEqual(limit);
        },
      },
    );
    expect(Math.abs(wrapAngle(sim.horse.heading - 270 * DEG))).toBeLessThan(0.5);
  });

  it('the hindquarters stay inside in all four corners, whatever the heading', () => {
    const limitX = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9;
    const limitZ = ARENA.length / 2 - TUNING.horse.rearMargin + 1e-9;
    const sim = makeSim([]);
    for (const [cx, cz] of [
      [1, 1],
      [1, -1],
      [-1, 1],
      [-1, -1],
    ]) {
      sim.reset({ x: cx * 19, z: cz * 34, heading: 0 });
      drive(
        sim,
        { steer: 1 },
        {
          maxT: 8,
          onStep: (s) => {
            const f = { x: Math.sin(s.horse.heading), z: Math.cos(s.horse.heading) };
            expect(Math.abs(s.horse.x - f.x * TUNING.horse.rearLength)).toBeLessThanOrEqual(limitX);
            expect(Math.abs(s.horse.z - f.z * TUNING.horse.rearLength)).toBeLessThanOrEqual(limitZ);
          },
        },
      );
    }
  });

  it('the horse never leaves the arena (random riding)', () => {
    const sim = makeSim([]);
    const rng = createRng(3);
    let steer = 0;
    let throttle = 1;
    const maxX = ARENA.width / 2 - TUNING.horse.radius + 1e-9;
    const maxZ = ARENA.length / 2 - TUNING.horse.radius + 1e-9;
    const rearMaxX = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9;
    const rearMaxZ = ARENA.length / 2 - TUNING.horse.rearMargin + 1e-9;
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
          const f = forwardOf(s.horse.heading);
          const rear = TUNING.horse.rearLength;
          expect(Math.abs(s.horse.x - f.x * rear)).toBeLessThanOrEqual(rearMaxX);
          expect(Math.abs(s.horse.z - f.z * rear)).toBeLessThanOrEqual(rearMaxZ);
        },
      },
    );
  });
});
