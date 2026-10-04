import { describe, it, expect } from 'vitest';
import { POLE_LENGTH, ARENA } from '../../domain/sim/tuning.js';
import { COURSES } from '../../domain/course/courses.js';
import { crossAxisOf } from '../../domain/sim/geometry.js';
import {
  axesOf,
  flagSides,
  polesOf,
  standRows,
  standHeight,
  labelOf,
  highlightText,
  fallCurve,
  endProgress,
  endProgressOf,
  fallPoint,
  fallPointInto,
  fallTarget,
  aidPlacement,
  lineSegment,
  planLines,
  linePosts,
  planFence,
  FENCE,
  GATE,
  terrainHeight,
  isBlocked,
  scatter,
  instanceCount,
  PADDOCK,
  paddockPoint,
  paddockContains,
  planPaddockFence,
  SITE,
  POLE_RADIUS,
  STAND_X,
} from './world-layout.js';

/** World position of an element-local point (lx, lz). */
const localToWorld = (element, lx, lz) => {
  const rot = element.rot || 0;
  const c = Math.cos(rot);
  const s = Math.sin(rot);
  return { x: element.x + lx * c + lz * s, z: element.z - lx * s + lz * c };
};

const seq = (...values) => {
  let i = 0;
  return () => values[i++ % values.length];
};

describe('axes and flags', () => {
  it('t matches crossAxisOf of the simulation', () => {
    for (const rot of [0, 0.7, Math.PI, -2]) {
      const { t } = axesOf(rot);
      const ref = crossAxisOf({ rot });
      expect(t.x).toBeCloseTo(ref.x);
      expect(t.z).toBeCloseTo(ref.z);
    }
  });

  it('red flag stands on the +t side', () => {
    for (const rot of [0, 1.2, Math.PI, 4]) {
      const el = { x: 3, z: -2, rot };
      const p = localToWorld(el, flagSides().red * STAND_X, 0);
      const { t } = axesOf(rot);
      expect((p.x - el.x) * t.x + (p.z - el.z) * t.z).toBeCloseTo(STAND_X);
      const w = localToWorld(el, flagSides().white * STAND_X, 0);
      expect((w.x - el.x) * t.x + (w.z - el.z) * t.z).toBeCloseTo(-STAND_X);
    }
  });

  it('local +Z points in jump direction n', () => {
    const el = { x: 1, z: 1, rot: 0.9 };
    const p = localToWorld(el, 0, 2);
    const { n } = axesOf(0.9);
    expect(p.x).toBeCloseTo(1 + 2 * n.x);
    expect(p.z).toBeCloseTo(1 + 2 * n.z);
  });
});

describe('polesOf', () => {
  it('vertical: top pole is rail 0 with its top at height', () => {
    const poles = polesOf({ kind: 'vertical', height: 0.6 });
    expect(poles.filter((p) => p.rail === 0)).toHaveLength(1);
    expect(poles[0].a[1] + POLE_RADIUS).toBeCloseTo(0.6);
    expect(poles.every((p) => p.rail <= 0)).toBe(true);
  });

  it('a high vertical also has a fixed filler pole', () => {
    const poles = polesOf({ kind: 'vertical', height: 0.85 });
    expect(poles.filter((p) => p.rail === -1)).toHaveLength(1);
  });

  it('oxer: front rail 0 at −spread/2, back rail 1 at +spread/2, both at height', () => {
    const poles = polesOf({ kind: 'oxer', height: 0.8, spread: 1 });
    const r0 = poles.find((p) => p.rail === 0);
    const r1 = poles.find((p) => p.rail === 1);
    expect(r0.a[2]).toBeCloseTo(-0.5);
    expect(r1.a[2]).toBeCloseTo(0.5);
    expect(r0.a[1]).toBeCloseTo(r1.a[1]);
    expect(r0.a[1] + POLE_RADIUS).toBeCloseTo(0.8);
  });

  it('cross: two crossed poles as rail 0, crossing at height, plus a ground pole', () => {
    const poles = polesOf({ kind: 'cross', height: 0.5 });
    const crossed = poles.filter((p) => p.rail === 0);
    expect(crossed).toHaveLength(2);
    for (const p of crossed) {
      const mid = (p.a[1] + p.b[1]) / 2;
      expect(mid + POLE_RADIUS).toBeCloseTo(0.5);
    }
    expect(poles.filter((p) => p.rail === -1)).toHaveLength(1);
  });

  it('poles lie between the stands (length < POLE_LENGTH)', () => {
    const [p] = polesOf({ kind: 'vertical', height: 0.6 });
    expect(p.b[0] - p.a[0]).toBeLessThanOrEqual(POLE_LENGTH);
    expect(p.b[0] - p.a[0]).toBeGreaterThan(POLE_LENGTH - 0.05);
  });

  it('stand rows and height', () => {
    expect(standRows({ kind: 'oxer', spread: 1.2 })).toEqual([-0.6, 0.6]);
    expect(standRows({ kind: 'cross' })).toEqual([0]);
    expect(standHeight({ height: 0.4 })).toBeGreaterThan(0.4 + 0.5);
    expect(standHeight({ height: 1.2 })).toBeGreaterThan(1.2);
  });
});

describe('labels', () => {
  it('number, combination with a/b, none in free mode', () => {
    expect(labelOf({ number: 3, elements: [{}] }, 0)).toBe('3');
    expect(labelOf({ number: 5, elements: [{}, {}] }, 1)).toBe('5b');
    expect(labelOf({ number: null, elements: [{}] }, 0)).toBeNull();
    expect(highlightText({ number: 5, elements: [{}, {}] }, 0, 5)).toBe('5a');
    expect(highlightText({ number: 2, elements: [{}] }, 0, 2)).toBe('2');
    expect(highlightText({ number: null, elements: [{}] }, 0, null)).toBeNull();
  });
});

describe('falling poles', () => {
  it('fall curve starts at 0, ends at 1, with a small bounce', () => {
    expect(fallCurve(0)).toBe(0);
    expect(fallCurve(1)).toBe(1);
    expect(fallCurve(0.78)).toBeCloseTo(1);
    expect(fallCurve(0.89)).toBeLessThan(1);
    expect(fallCurve(0.89)).toBeGreaterThan(0.85);
    for (let t = 0; t < 0.78; t += 0.05)
      expect(fallCurve(t + 0.05)).toBeGreaterThanOrEqual(fallCurve(t));
  });

  it('one end falls first, both arrive at t = 1', () => {
    const mid = endProgress(0.5, 0);
    expect(mid.a).toBeGreaterThan(mid.b);
    const other = endProgress(0.5, 1);
    expect(other.b).toBeGreaterThan(other.a);
    expect(endProgress(1, 0)).toEqual({ a: 1, b: 1 });
    expect(endProgress(0, 1)).toEqual({ a: 0, b: 0 });
  });

  it('endProgressOf is the allocation-free form of endProgress', () => {
    for (const lead of [0, 1]) {
      for (const t of [0, 0.1, 0.3, 0.5, 0.9, 1]) {
        const ref = endProgress(t, lead);
        expect(endProgressOf(t, lead, true)).toBe(ref.a);
        expect(endProgressOf(t, lead, false)).toBe(ref.b);
      }
    }
  });

  it('fallPointInto writes the same point as fallPoint into a vector-like object', () => {
    const from = { x: 0, y: 0.8, z: 0 };
    const to = { x: 0.2, y: 0.05, z: 1 };
    const out = { x: 9, y: 9, z: 9 };
    for (const t of [0, 0.3, 0.8, 1]) {
      const ref = fallPoint([from.x, from.y, from.z], [to.x, to.y, to.z], t);
      expect(fallPointInto(out, from, to, t)).toBe(out);
      expect([out.x, out.y, out.z]).toEqual(ref);
    }
  });

  it('fallPoint interpolates from cup to ground', () => {
    expect(fallPoint([0, 0.8, 0], [0.2, 0.05, 1], 0)).toEqual([0, 0.8, 0]);
    const end = fallPoint([0, 0.8, 0], [0.2, 0.05, 1], 1);
    expect(end[0]).toBeCloseTo(0.2);
    expect(end[1]).toBeCloseTo(0.05);
    expect(end[2]).toBeCloseTo(1);
  });

  it('a fallen pole lies on the sand on the fall side, length is kept', () => {
    for (const side of [1, -1]) {
      const t = fallTarget([0, 0.6, 0.4], 3.48, side, seq(0.5, 0.9, 0.1, 0.3, 0.7));
      expect(t.a[1]).toBe(POLE_RADIUS);
      expect(t.b[1]).toBe(POLE_RADIUS);
      expect(Math.sign((t.a[2] + t.b[2]) / 2 - 0.4)).toBe(side);
      expect(Math.hypot(t.b[0] - t.a[0], t.b[2] - t.a[2])).toBeCloseTo(3.48);
      expect(Math.sign(t.roll)).toBe(side);
    }
  });
});

describe('aidPlacement', () => {
  const zone = { far: 3, near: 1 };

  it('vertical rot 0, approach in +n: band in front of the obstacle (z < 0)', () => {
    const p = aidPlacement({ kind: 'vertical', x: 0, z: 0, rot: 0 }, 1, zone);
    expect(p.x).toBeCloseTo(0);
    expect(p.z).toBeCloseTo(-2);
    expect(p.depth).toBeCloseTo(2);
    expect(p.width).toBe(POLE_LENGTH);
  });

  it('oxer: measured from the front edge (spread/2), both directions', () => {
    const el = { kind: 'oxer', spread: 1.2, x: 5, z: 5, rot: Math.PI / 2 };
    const p = aidPlacement(el, 1, zone);
    // n = (1, 0): front edge at x = 4.4, band center 2 m before it
    expect(p.x).toBeCloseTo(2.4);
    expect(p.z).toBeCloseTo(5);
    const q = aidPlacement(el, -1, zone);
    expect(q.x).toBeCloseTo(7.6);
    expect(q.rotY).toBeCloseTo(Math.PI / 2);
  });

  it('swapped or empty zones', () => {
    const el = { kind: 'vertical', x: 0, z: 0, rot: 0 };
    expect(aidPlacement(el, 1, { far: 1, near: 3 }).z).toBeCloseTo(-2);
    expect(aidPlacement(el, 1, { far: 2, near: 2 })).toBeNull();
    expect(aidPlacement(null, 1, zone)).toBeNull();
    expect(aidPlacement(el, 1, null)).toBeNull();
  });
});

describe('lines', () => {
  it('lineSegment accepts arrays and objects', () => {
    const s = lineSegment([0, 0], { x: 0, z: 4 });
    expect(s.length).toBeCloseTo(4);
    expect(s.cz).toBeCloseTo(2);
    expect(s.angle).toBeCloseTo(0);
  });

  it('planLines: separate signs with the given texts', () => {
    const plan = planLines({
      start: { a: [0, 0], b: [4, 0] },
      finish: { a: [0, 10], b: [4, 10] },
      labels: { start: 'Start', finish: 'Finish' },
    });
    expect(plan.map((p) => p.text)).toEqual(['Start', 'Finish']);
    expect(plan[1].finish).toBe(true);
  });

  it('planLines: identical lines give one shared sign', () => {
    const line = { a: [0, 0], b: [4, 0] };
    const plan = planLines({ start: line, finish: line, labels: { start: 'S', finish: 'Z' } });
    expect(plan).toHaveLength(1);
    expect(plan[0].text).toBe('S · Z');
    expect(plan[0].finish).toBe(true);
    expect(planLines(null)).toEqual([]);
  });
});

describe('start/finish line flags (SRT-004)', () => {
  it("red flag stands on the rider's right, white on the left, for every course line", () => {
    for (const course of COURSES) {
      for (const line of [course.start, course.finish]) {
        const posts = linePosts(lineSegment(line.a, line.b));
        const red = posts.find((p) => p.red);
        const white = posts.find((p) => !p.red);
        expect(posts).toHaveLength(2);
        const mx = (red.x + white.x) / 2;
        const mz = (red.z + white.z) / 2;
        // right of the rider for heading h = (-cos h, sin h); riding direction is line.dir
        const right = { x: -line.dir[1], z: line.dir[0] };
        expect((red.x - mx) * right.x + (red.z - mz) * right.z).toBeGreaterThan(2);
        expect((white.x - mx) * right.x + (white.z - mz) * right.z).toBeLessThan(-2);
      }
    }
  });

  it('the red flag is the b end (a is the left end of the line)', () => {
    // a is the rider's left end: riding +z, a is at x = +3 (left), b at x = -3 (right)
    const posts = linePosts(lineSegment([3, 0], [-3, 0]));
    expect(posts.find((p) => p.red)).toMatchObject({ x: -3, z: 0 });
  });
});

describe('planFence', () => {
  const plan = planFence({ pathFence: [{ a: [-21, 24], b: [-35, 24] }] });

  it('post spacing at most 2.5 m, fence outside the riding area', () => {
    const arena = plan.segments.filter((s) => s.style === 'arena');
    expect(arena.every((s) => s.len <= FENCE.spacing + 1e-9)).toBe(true);
    const posts = plan.posts.filter((p) => p.style === 'arena');
    expect(
      posts.every((p) => Math.abs(p.x) >= ARENA.width / 2 || Math.abs(p.z) >= ARENA.length / 2),
    ).toBe(true);
  });

  it('gate gap without fence parts', () => {
    const inGap = plan.segments.filter(
      (s) =>
        s.style === 'arena' &&
        s.x < 0 &&
        Math.abs(s.z - GATE.z) < GATE.width / 2 - 0.1 &&
        Math.abs(s.x + 20.18) < 0.1,
    );
    expect(inGap).toHaveLength(0);
    expect(plan.gate.z1 - plan.gate.z0).toBeCloseTo(GATE.width);
  });

  it('no duplicate posts, wooden path fence', () => {
    const keys = plan.posts.map((p) => `${p.x.toFixed(2)},${p.z.toFixed(2)}`);
    expect(new Set(keys).size).toBe(keys.length);
    expect(plan.posts.some((p) => p.style === 'wood')).toBe(true);
  });
});

describe('environment', () => {
  it('terrain is flat around the facility and rises towards the horizon', () => {
    expect(terrainHeight(0, 0)).toBe(0);
    expect(terrainHeight(60, 40)).toBe(0);
    expect(terrainHeight(300, 0)).toBeGreaterThan(5);
  });

  it('the arena is blocked for plants', () => {
    expect(isBlocked(0, 0)).toBe(true);
    expect(isBlocked(60, 60)).toBe(false);
  });

  it('scatter returns points in the annulus outside blocked areas', () => {
    let s = 1;
    const rng = () => (s = (s * 16807) % 2147483647) / 2147483647;
    const pts = scatter(rng, 50, 30, 80, 1);
    expect(pts).toHaveLength(50);
    for (const [x, z] of pts) {
      const r = Math.hypot(x, z);
      expect(r).toBeGreaterThanOrEqual(30 - 1e-9);
      expect(r).toBeLessThanOrEqual(80 + 1e-9);
      expect(isBlocked(x, z, 1)).toBe(false);
    }
  });

  it('instanceCount: mandatory instances always, the rest by density', () => {
    expect(instanceCount(100, 10, 0)).toBe(10);
    expect(instanceCount(100, 10, 1)).toBe(100);
    expect(instanceCount(100, 10, 0.5)).toBe(55);
    expect(instanceCount(5, 10, 0.5)).toBe(5);
  });
});

describe('paddock', () => {
  const rotated = { x: 10, z: -5, width: 20, depth: 14, rotation: 0.6 };

  it('has a documented rectangle on the meadow', () => {
    expect(PADDOCK.width).toBeGreaterThanOrEqual(18);
    expect(PADDOCK.depth).toBeGreaterThanOrEqual(12);
    expect(Object.isFrozen(PADDOCK)).toBe(true);
  });

  it('does not touch the arena, the stable, the path, the hut or the benches', () => {
    const corners = [
      paddockPoint(1, 1),
      paddockPoint(1, -1),
      paddockPoint(-1, 1),
      paddockPoint(-1, -1),
    ];
    const half = { x: ARENA.width / 2 + 3, z: ARENA.length / 2 + 3 };
    for (const c of corners) {
      expect(Math.abs(c.x) > half.x || Math.abs(c.z) > half.z).toBe(true);
      // stable footprint (with a gap) and the path fences
      const st = SITE.stable;
      const inStable =
        Math.abs(c.x - st.x) < st.depth / 2 + 2 && Math.abs(c.z - st.z) < st.length / 2 + 2;
      expect(inStable).toBe(false);
      expect(c.z).toBeLessThan(SITE.pathFence[1].a[1] - 4);
    }
    expect(Math.hypot(PADDOCK.x - SITE.hut.x, PADDOCK.z - SITE.hut.z)).toBeGreaterThan(40);
  });

  it('is on flat ground', () => {
    for (const [u, v] of [
      [0, 0],
      [1, 1],
      [-1, -1],
    ]) {
      const p = paddockPoint(u, v);
      expect(terrainHeight(p.x, p.z)).toBe(0);
    }
  });

  it('paddockPoint maps the unit square to the rectangle, also when rotated', () => {
    expect(paddockPoint(0, 0)).toEqual({ x: PADDOCK.x, z: PADDOCK.z });
    const edge = paddockPoint(1, 0);
    expect(edge.x).toBeCloseTo(PADDOCK.x + PADDOCK.width / 2, 9);
    const r = paddockPoint(1, 0, rotated);
    expect(Math.hypot(r.x - rotated.x, r.z - rotated.z)).toBeCloseTo(rotated.width / 2, 9);
    const back = paddockPoint(0, 1, rotated);
    expect(Math.hypot(back.x - rotated.x, back.z - rotated.z)).toBeCloseTo(rotated.depth / 2, 9);
  });

  it('paddockContains follows the rotated rectangle and the margin', () => {
    expect(paddockContains(PADDOCK.x, PADDOCK.z)).toBe(true);
    expect(paddockContains(PADDOCK.x + PADDOCK.width / 2 + 0.1, PADDOCK.z)).toBe(false);
    expect(paddockContains(PADDOCK.x + PADDOCK.width / 2 - 0.5, PADDOCK.z, 1)).toBe(false);
    expect(paddockContains(PADDOCK.x + PADDOCK.width / 2 + 0.5, PADDOCK.z, -1)).toBe(true);
    for (const [u, v] of [
      [0.9, 0.9],
      [-0.9, 0.9],
      [0.9, -0.9],
      [0, 0],
    ]) {
      const p = paddockPoint(u, v, rotated);
      expect(paddockContains(p.x, p.z, 0, rotated)).toBe(true);
    }
    const outside = paddockPoint(1.2, 0, rotated);
    expect(paddockContains(outside.x, outside.z, 0, rotated)).toBe(false);
  });

  it('is blocked for plants, with a clearance around the fence', () => {
    expect(isBlocked(PADDOCK.x, PADDOCK.z)).toBe(true);
    expect(isBlocked(PADDOCK.x + PADDOCK.width / 2 + 0.5, PADDOCK.z)).toBe(true);
    expect(isBlocked(PADDOCK.x - PADDOCK.width / 2 - 2, PADDOCK.z)).toBe(false);
  });

  it('planPaddockFence runs along the four sides with unique posts', () => {
    const plan = planPaddockFence();
    const perimeter = 2 * (PADDOCK.width + PADDOCK.depth);
    const length = plan.segments.reduce((sum, s) => sum + s.len, 0);
    expect(length).toBeCloseTo(perimeter, 6);
    expect(plan.segments.every((s) => s.style === 'paddock')).toBe(true);
    expect(plan.posts).toHaveLength(plan.segments.length);
    const keys = new Set(plan.posts.map((p) => `${p.x.toFixed(2)},${p.z.toFixed(2)}`));
    expect(keys.size).toBe(plan.posts.length);
    // every post is on the fence line
    for (const p of plan.posts) {
      const onX = Math.abs(Math.abs(p.x - PADDOCK.x) - PADDOCK.width / 2) < 1e-6;
      const onZ = Math.abs(Math.abs(p.z - PADDOCK.z) - PADDOCK.depth / 2) < 1e-6;
      expect(onX || onZ).toBe(true);
    }
  });
});
