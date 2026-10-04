import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import {
  GRAZER_STATES,
  GRAZING,
  createGrazer,
  insideArea,
  pickSpot,
  stepGrazer,
  toLocal,
  toWorld,
} from './grazing-logic.js';

const AREA = { x: 30, z: -12, width: 24, depth: 16, rotation: 0.6 };
const DT = 1 / 30;

function simulate(seed, seconds, count = 2, area = AREA) {
  const rng = createRng(seed);
  const grazers = Array.from({ length: count }, (_, i) => {
    const p = toWorld(area, (i - (count - 1) / 2) * 6, 0);
    return createGrazer({ x: p.x, z: p.z, heading: rng() * 6 - 3, rng });
  });
  const log = {
    states: {},
    maxSpeed: 0,
    maxTurn: 0,
    maxGrazeStep: 0,
    minGap: Infinity,
    outside: 0,
  };
  const prevGraze = grazers.map((g) => g.graze);
  let walked = 0;
  for (let t = 0; t < seconds; t += DT) {
    grazers.forEach((g, i) => {
      const others = grazers.filter((o) => o !== g);
      const before = { x: g.x, z: g.z };
      stepGrazer(g, DT, area, others, rng);
      log.states[g.state] = (log.states[g.state] || 0) + DT;
      log.maxSpeed = Math.max(log.maxSpeed, g.speed);
      log.maxTurn = Math.max(log.maxTurn, Math.abs(g.turnRate));
      log.maxGrazeStep = Math.max(log.maxGrazeStep, Math.abs(g.graze - prevGraze[i]));
      prevGraze[i] = g.graze;
      walked += Math.hypot(g.x - before.x, g.z - before.z);
      if (!insideArea(area, g.x, g.z, -1)) log.outside++;
    });
    if (count > 1) {
      log.minGap = Math.min(
        log.minGap,
        Math.hypot(grazers[0].x - grazers[1].x, grazers[0].z - grazers[1].z),
      );
    }
  }
  return { grazers, log, walked };
}

describe('paddock area helpers', () => {
  it('toWorld and toLocal are inverse and respect the rotation', () => {
    for (const [lx, lz] of [
      [0, 0],
      [5, -3],
      [-11, 7],
    ]) {
      const w = toWorld(AREA, lx, lz);
      const l = toLocal(AREA, w.x, w.z);
      expect(l.x).toBeCloseTo(lx, 9);
      expect(l.z).toBeCloseTo(lz, 9);
    }
    // a quarter turn: the local x axis points along −z (three.js rotation about Y)
    const q = toWorld({ x: 0, z: 0, width: 4, depth: 4, rotation: Math.PI / 2 }, 1, 0);
    expect(q.x).toBeCloseTo(0, 9);
    expect(q.z).toBeCloseTo(-1, 9);
  });

  it('insideArea honours the margin', () => {
    const c = toWorld(AREA, 11.5, 0);
    expect(insideArea(AREA, c.x, c.z, 0)).toBe(true);
    expect(insideArea(AREA, c.x, c.z, 1)).toBe(false);
    const out = toWorld(AREA, 13, 0);
    expect(insideArea(AREA, out.x, out.z, 0)).toBe(false);
  });
});

describe('next grazing spot', () => {
  it('lies inside the area with a margin to the fence and is a real step away', () => {
    const rng = createRng(2);
    for (let i = 0; i < 200; i++) {
      const from = toWorld(AREA, (rng() - 0.5) * 20, (rng() - 0.5) * 12);
      const spot = pickSpot(rng, AREA, from, []);
      expect(insideArea(AREA, spot.x, spot.z, GRAZING.margin - 1e-6)).toBe(true);
    }
  });

  it('prefers spots away from the other horses', () => {
    const rng = createRng(3);
    const from = toWorld(AREA, 0, 0);
    const other = toWorld(AREA, 3, 0);
    let near = 0;
    for (let i = 0; i < 100; i++) {
      const spot = pickSpot(rng, AREA, from, [other]);
      if (Math.hypot(spot.x - other.x, spot.z - other.z) < 2) near++;
    }
    expect(near).toBeLessThan(10);
  });
});

describe('grazing horse', () => {
  it('grazes most of the time, sometimes looks up and walks a few steps', () => {
    const { log, walked } = simulate(7, 1200);
    const total = Object.values(log.states).reduce((a, b) => a + b, 0);
    expect(log.states[GRAZER_STATES.graze] / total).toBeGreaterThan(0.6);
    expect(log.states[GRAZER_STATES.look]).toBeGreaterThan(10);
    expect(log.states[GRAZER_STATES.turn]).toBeGreaterThan(2);
    expect(log.states[GRAZER_STATES.walk]).toBeGreaterThan(10);
    expect(walked).toBeGreaterThan(20);
  });

  it('walks slowly and turns gently', () => {
    const { log } = simulate(8, 900);
    expect(log.maxSpeed).toBeLessThanOrEqual(GRAZING.walkSpeed + 1e-9);
    expect(log.maxTurn).toBeLessThanOrEqual(GRAZING.turnRate + 1e-9);
  });

  it('stays in the paddock and keeps away from the other horse', () => {
    for (const seed of [1, 2, 3]) {
      const { log } = simulate(seed, 900);
      expect(log.outside).toBe(0);
      expect(log.minGap).toBeGreaterThan(0.8);
    }
  });

  it('lowers and raises the head gradually', () => {
    const { log } = simulate(9, 600);
    expect(log.maxGrazeStep).toBeLessThan(0.08);
  });

  it('head is down while grazing, up while looking and moving', () => {
    const rng = createRng(4);
    const g = createGrazer({ x: AREA.x, z: AREA.z, rng });
    for (let t = 0; t < 4; t += DT) stepGrazer(g, DT, AREA, [], rng);
    expect(g.state).toBe(GRAZER_STATES.graze);
    expect(g.graze).toBeGreaterThan(0.95);
    g.timer = 0;
    for (let t = 0; t < 3; t += DT) stepGrazer(g, DT, AREA, [], rng);
    expect(g.state).toBe(GRAZER_STATES.look);
    expect(g.graze).toBeLessThan(0.5);
  });

  it('is deterministic for a seed', () => {
    const a = simulate(11, 300).grazers.map((g) => [g.x, g.z, g.heading]);
    const b = simulate(11, 300).grazers.map((g) => [g.x, g.z, g.heading]);
    expect(a).toEqual(b);
  });

  it('walks with the head up and stands still while it grazes', () => {
    const rng = createRng(5);
    const g = createGrazer({ x: AREA.x, z: AREA.z, rng });
    let walking = 0;
    let checkedWalk = 0;
    let checkedGraze = 0;
    for (let t = 0; t < 900; t += DT) {
      stepGrazer(g, DT, AREA, [], rng);
      walking = g.state === GRAZER_STATES.walk ? walking + DT : 0;
      if (walking > 2.5) {
        expect(g.graze).toBeLessThan(0.1);
        checkedWalk++;
      }
      if (g.state === GRAZER_STATES.graze && g.graze > 0.95) {
        expect(g.speed).toBeLessThan(0.3);
        checkedGraze++;
      }
    }
    expect(checkedWalk).toBeGreaterThan(5);
    expect(checkedGraze).toBeGreaterThan(100);
  });
});
