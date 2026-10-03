import { describe, expect, it } from 'vitest';
import { TUNING, ARENA } from './tuning.js';
import { forwardOf, wrapAngle } from './geometry.js';
import { gaitForSpeed, maxTurnRate, turnRadius } from './movement.js';
import { createRng } from './rng.js';
import { DEG, drive, makeSim, ofType } from './test-utils.js';

const S = TUNING.speeds;

describe('Gangart aus Tempo (Regel 9)', () => {
  it('ordnet Halt, Schritt und Trab nach Tempo zu, Galopp immer canter', () => {
    expect(gaitForSpeed(0, false, S)).toBe('halt');
    expect(gaitForSpeed(0.1, false, S)).toBe('halt');
    expect(gaitForSpeed(1.0, false, S)).toBe('walk');
    expect(gaitForSpeed(S.walkMax, false, S)).toBe('walk');
    expect(gaitForSpeed(S.walkMax + 0.1, false, S)).toBe('trot');
    expect(gaitForSpeed(S.trotMax, false, S)).toBe('trot');
    expect(gaitForSpeed(0, true, S)).toBe('canter');
  });
});

describe('Tempo (Regeln 8–10)', () => {
  it('W erhöht stufenlos über Schritt bis Trab und nie über trotMax', () => {
    const sim = makeSim([]);
    const gaits = new Set();
    drive(sim, { throttle: 1 }, { maxT: 6, onStep: (s) => gaits.add(s.horse.gait) });
    expect([...gaits]).toEqual(['halt', 'walk', 'trot']);
    expect(sim.horse.speed).toBeCloseTo(S.trotMax, 6);
  });

  it('S bremst bis zum Halt (Tempo 0)', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 3 });
    drive(sim, { throttle: -1 }, { maxT: 3 });
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
  });

  it('ohne Eingabe bleibt das Tempo erhalten', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -20, heading: 0, speed: 2.5 });
    drive(sim, {}, { maxT: 2 });
    expect(sim.horse.speed).toBeCloseTo(2.5, 9);
    expect(sim.horse.gait).toBe('trot');
  });

  it('Rate ist proportional zur Auslenkung (Joystick)', () => {
    const a = makeSim([]);
    const b = makeSim([]);
    drive(a, { throttle: 1 }, { maxT: 0.5 });
    drive(b, { throttle: 0.5 }, { maxT: 0.5 });
    expect(b.horse.speed / a.horse.speed).toBeCloseTo(0.5, 2);
  });

  it('Galopp: sofort Gangart canter, sanftes Beschleunigen auf mindestens canterMin', () => {
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

  it('W/S regeln das Galopptempo innerhalb [canterMin, canterMax]', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: -34, heading: 0, speed: S.canterMin, gallop: true });
    drive(sim, { gallop: true, throttle: 1 }, { maxT: 2 });
    expect(sim.horse.speed).toBeCloseTo(S.canterMax, 6);
    drive(sim, { gallop: true, throttle: -1 }, { maxT: 3 });
    expect(sim.horse.speed).toBeCloseTo(S.canterMin, 6);
    expect(sim.horse.gait).toBe('canter');
  });

  it('Galopp aus: Trab, Tempo sinkt sanft auf Arbeitstrab', () => {
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

  it('nach reset galoppiert das Pferd erst nach neuem Drücken', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0 });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });
});

describe('Lenken (Regeln 8, 10, 22)', () => {
  it('wendet im Halt auf der Stelle; rechts dreht nach rechts', () => {
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

  it('Lenkstärke ist proportional zu |steer|', () => {
    const a = makeSim([]);
    const b = makeSim([]);
    drive(a, { steer: -1 }, { maxT: 1 });
    drive(b, { steer: -0.5 }, { maxT: 1 });
    expect(b.horse.turnRate / a.horse.turnRate).toBeCloseTo(0.5, 3);
    expect(a.horse.turnRate).toBeLessThan(0);
  });

  it('der Kurvenradius wird mit dem Tempo größer', () => {
    const radii = [1, 3, 5, 8].map((v) => turnRadius(v, TUNING));
    for (let i = 1; i < radii.length; i++) expect(radii[i]).toBeGreaterThan(radii[i - 1]);
    expect(maxTurnRate(0, TUNING)).toBe(TUNING.control.turnInPlace);
  });

  it('gemessene Kurve im Trab ist enger als im Galopp', () => {
    const measure = (speed, gallop) => {
      const sim = makeSim([]);
      sim.reset({ x: 0, z: 0, heading: 0, speed, gallop });
      drive(sim, { steer: 1, gallop }, { maxT: 1 });
      return speed / Math.abs(sim.horse.turnRate);
    };
    expect(measure(3.2, false)).toBeLessThan(measure(6, true));
  });
});

describe('Umzäunung (Regel 24)', () => {
  it('frontal: Stopp, Halt, Galopp aus mit Events', () => {
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

  it('nach dem Zaun-Stopp galoppiert es erst nach neuem Drücken', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 90 * DEG, speed: 5, gallop: true });
    drive(sim, { gallop: true }, { maxT: 2 });
    expect(sim.horse.gallop).toBe(false);
    // Shift bleibt gehalten, während auf der Stelle gewendet wird: kein Galopp
    drive(sim, { gallop: true, steer: 1 }, { maxT: 2 });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });

  it('frontal mit 30° Abweichung gilt noch als frontal', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 60 * DEG, speed: 3 });
    const { events } = drive(sim, {}, { maxT: 3 });
    expect(ofType(events, 'fenceStop')).toHaveLength(1);
  });

  it('weiter auf den Zaun drücken erzeugt keine Event-Flut', () => {
    const sim = makeSim([]);
    sim.reset({ x: 15, z: 0, heading: 90 * DEG, speed: 3 });
    const { events } = drive(sim, { throttle: 1 }, { maxT: 4 });
    expect(ofType(events, 'fenceStop')).toHaveLength(1);
    expect(sim.horse.x).toBeLessThanOrEqual(ARENA.width / 2 - TUNING.horse.radius + 1e-9);
  });

  it('schräg: gleitet mit unverändertem Tempo parallel an der Wand entlang', () => {
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

  it('das Pferd verlässt den Platz nie (zufälliges Reiten)', () => {
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
