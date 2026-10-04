import { describe, it, expect } from 'vitest';
import { createRng } from './textures.js';
import {
  planFlocks,
  flockPose,
  birdPose,
  planButterflies,
  butterflyPose,
  BIRD_LIMITS,
} from './flight-paths.js';

const flocks = (seed = 4, count = 4, size = 6) => planFlocks(createRng(seed), count, size);

/** Velocity from two poses a little apart. */
function velocity(pose, t, dt = 0.01) {
  const a = pose(t, {});
  const b = pose(t + dt, {});
  return { x: (b.x - a.x) / dt, y: (b.y - a.y) / dt, z: (b.z - a.z) / dt, a };
}

describe('planFlocks', () => {
  it('is deterministic for a seed and differs between seeds', () => {
    expect(flocks(1)).toEqual(flocks(1));
    expect(flocks(1)).not.toEqual(flocks(2));
  });

  it('plans the requested flocks with birds around the center', () => {
    const planned = flocks(3, 3, 5);
    expect(planned).toHaveLength(3);
    for (const flock of planned) {
      expect(flock.birds).toHaveLength(5);
      expect(flock.radius).toBeGreaterThanOrEqual(BIRD_LIMITS.radius[0]);
      expect(flock.radius).toBeLessThanOrEqual(BIRD_LIMITS.radius[1]);
      expect(flock.altitude).toBeGreaterThanOrEqual(BIRD_LIMITS.altitude[0]);
      expect(flock.altitude).toBeLessThanOrEqual(BIRD_LIMITS.altitude[1]);
    }
  });

  it('circles in both directions across the flocks', () => {
    const dirs = new Set(flocks(5, 8).map((f) => f.dir));
    expect(dirs.size).toBe(2);
  });
});

describe('flockPose', () => {
  it('stays on its circle at its altitude', () => {
    for (const flock of flocks()) {
      for (const t of [0, 3.3, 41, 500, 3600]) {
        const p = flockPose(flock, t, {});
        expect(Math.hypot(p.x - flock.cx, p.z - flock.cz)).toBeCloseTo(flock.radius, 6);
        expect(Math.abs(p.y - flock.altitude)).toBeLessThanOrEqual(flock.bob + 1e-9);
      }
    }
  });

  it('flies at the planned speed with its nose along the path', () => {
    for (const flock of flocks()) {
      for (const t of [0, 12.5, 100]) {
        const v = velocity((time, out) => flockPose(flock, time, out), t);
        expect(Math.hypot(v.x, v.z)).toBeCloseTo(flock.speed, 1);
        // heading h points along (sin h, cos h)
        expect(Math.sin(v.a.heading)).toBeCloseTo(v.x / Math.hypot(v.x, v.z), 2);
        expect(Math.cos(v.a.heading)).toBeCloseTo(v.z / Math.hypot(v.x, v.z), 2);
      }
    }
  });

  it('circles slowly: a lap takes at least half a minute', () => {
    for (const flock of flocks()) {
      expect((2 * Math.PI * flock.radius) / flock.speed).toBeGreaterThan(30);
    }
  });
});

describe('birdPose', () => {
  it('keeps the birds together around the flock center', () => {
    for (const flock of flocks(6)) {
      for (const t of [0, 7, 90]) {
        const c = flockPose(flock, t, {});
        flock.birds.forEach((_, i) => {
          const b = birdPose(flock, i, t, {});
          expect(Math.hypot(b.x - c.x, b.z - c.z)).toBeLessThanOrEqual(BIRD_LIMITS.spread);
          expect(Math.abs(b.y - c.y)).toBeLessThanOrEqual(BIRD_LIMITS.spread / 2);
        });
      }
    }
  });

  it('moves smoothly: no jump between frames', () => {
    const [flock] = flocks(8);
    let last = birdPose(flock, 1, 0, {});
    for (let t = 1 / 60; t < 20; t += 1 / 60) {
      const p = birdPose(flock, 1, t, {});
      expect(Math.hypot(p.x - last.x, p.y - last.y, p.z - last.z)).toBeLessThan(0.3);
      expect(Math.abs(p.heading - last.heading)).toBeLessThan(0.05);
      last = p;
    }
  });

  it('writes into the object it is given and returns it', () => {
    const [flock] = flocks();
    const out = {};
    expect(birdPose(flock, 0, 1, out)).toBe(out);
    for (const key of ['x', 'y', 'z', 'heading', 'roll'])
      expect(Number.isFinite(out[key])).toBe(true);
  });

  it('gives each bird its own place', () => {
    const [flock] = flocks();
    const a = birdPose(flock, 0, 5, {});
    const b = birdPose(flock, 1, 5, {});
    expect(Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z)).toBeGreaterThan(0.5);
  });
});

describe('butterflies', () => {
  const anchors = [
    { x: 30, z: -20, radius: 3 },
    { x: -20, z: 40, radius: 4 },
  ];
  const planned = () => planButterflies(createRng(2), anchors, 10);

  it('plans the requested number over the given patches', () => {
    expect(planned()).toHaveLength(10);
    expect(planned()).toEqual(planned());
    expect(planButterflies(createRng(2), [], 5)).toEqual([]);
  });

  it('wanders within its patch and low above the flowers', () => {
    for (const b of planned()) {
      for (let t = 0; t < 120; t += 0.7) {
        const p = butterflyPose(b, t, {});
        expect(Math.hypot(p.x - b.ax, p.z - b.az)).toBeLessThanOrEqual(b.reach + 1e-9);
        expect(p.y).toBeGreaterThan(0.2);
        expect(p.y).toBeLessThan(1.8);
      }
    }
  });

  it('flutters slowly across the meadow, never faster than a few m/s', () => {
    for (const b of planned()) {
      for (let t = 0; t < 60; t += 1.3) {
        const v = velocity((time, out) => butterflyPose(b, time, out), t);
        expect(Math.hypot(v.x, v.y, v.z)).toBeLessThan(2.6);
      }
    }
  });

  it('turns smoothly towards where it flies', () => {
    const [b] = planned();
    let last = butterflyPose(b, 0, {});
    for (let t = 1 / 60; t < 30; t += 1 / 60) {
      const p = butterflyPose(b, t, {});
      let d = p.heading - last.heading;
      d -= Math.round(d / (2 * Math.PI)) * 2 * Math.PI;
      expect(Math.abs(d)).toBeLessThan(0.25);
      last = p;
    }
  });
});
