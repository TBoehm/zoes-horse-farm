import { describe, it, expect } from 'vitest';
import { ARENA } from '../../domain/sim/tuning.js';
import { FENCE, GATE, PADDOCK, planFence, paddockContains } from './world-layout.js';
import { BUNTING_COLORS, planBunting, planPots, planPaddockProps } from './decor-plan.js';

const hx = ARENA.width / 2 + FENCE.offset;
const hz = ARENA.length / 2 + FENCE.offset;

/** Distance of a point from the rectangle of the arena fence line. */
function distanceToFence(x, z) {
  const dx = hx - Math.abs(x);
  const dz = hz - Math.abs(z);
  return Math.min(Math.abs(dx), Math.abs(dz));
}

describe('planBunting', () => {
  const plan = planBunting();

  it('hangs one string per span of the arena fence, none across the gate', () => {
    const spans = planFence().segments.filter((s) => s.style === 'arena');
    expect(plan.strings).toHaveLength(spans.length);
    for (const s of plan.strings) {
      expect(s.a.y).toBeGreaterThan(FENCE.height);
      expect(distanceToFence(s.a.x, s.a.z)).toBeLessThan(1e-6);
      expect(distanceToFence(s.b.x, s.b.z)).toBeLessThan(1e-6);
      expect(s.sag).toBeGreaterThan(0);
    }
    for (const p of plan.pennants) {
      const inGate = Math.abs(p.x + hx) < 0.5 && Math.abs(p.z - GATE.z) < GATE.width / 2;
      expect(inGate).toBe(false);
    }
  });

  it('puts a good number of pennants on the fence, hanging below the string', () => {
    expect(plan.pennants.length).toBeGreaterThan(300);
    for (const p of plan.pennants) {
      expect(distanceToFence(p.x, p.z)).toBeLessThan(0.05);
      expect(p.y).toBeLessThan(FENCE.height + 0.15);
      expect(p.y - p.length).toBeGreaterThan(0.5);
      expect(p.width).toBeGreaterThan(0.1);
    }
  });

  it('alternates the colours so that neighbours differ', () => {
    const colors = new Set(BUNTING_COLORS);
    expect(colors.size).toBeGreaterThanOrEqual(5);
    for (const p of plan.pennants) expect(colors.has(p.color)).toBe(true);
    const bySpan = new Map();
    for (const p of plan.pennants) {
      if (!bySpan.has(p.span)) bySpan.set(p.span, []);
      bySpan.get(p.span).push(p);
    }
    for (const list of bySpan.values()) {
      list.sort((a, b) => a.index - b.index);
      for (let i = 1; i < list.length; i += 1) expect(list[i].color).not.toBe(list[i - 1].color);
    }
  });

  it('lists every second pennant first, so a prefix is an evenly thinner string', () => {
    expect(plan.coreCount).toBeGreaterThan(plan.pennants.length * 0.45);
    expect(plan.coreCount).toBeLessThan(plan.pennants.length * 0.7);
    plan.pennants.forEach((p, i) => expect(p.core).toBe(i < plan.coreCount));
    // within the core, no two pennants of a span are neighbours
    for (const p of plan.pennants.slice(0, plan.coreCount)) expect(p.index % 2).toBe(0);
  });

  it('tells which way a pennant faces (along the fence, flutter across)', () => {
    for (const p of plan.pennants) {
      expect(Math.hypot(p.tx, p.tz)).toBeCloseTo(1, 6);
    }
  });
});

describe('planPots', () => {
  const pots = planPots();

  it('stands a few pots at the gate, outside the fence', () => {
    expect(pots.length).toBeGreaterThanOrEqual(3);
    expect(pots.length).toBeLessThanOrEqual(8);
    for (const pot of pots) {
      expect(pot.x).toBeLessThan(-hx);
      expect(Math.abs(pot.z - GATE.z)).toBeLessThan(GATE.width / 2 + 3);
      expect(pot.scale).toBeGreaterThan(0.7);
    }
  });

  it('keeps the way through the gate and the path free', () => {
    for (const pot of pots) {
      const inWay = Math.abs(pot.z - GATE.z) < GATE.width / 2 + 0.1;
      expect(inWay).toBe(false);
    }
  });

  it('does not put two pots into each other', () => {
    for (let i = 0; i < pots.length; i += 1) {
      for (let j = i + 1; j < pots.length; j += 1) {
        expect(Math.hypot(pots[i].x - pots[j].x, pots[i].z - pots[j].z)).toBeGreaterThan(0.6);
      }
    }
  });
});

describe('planPaddockProps', () => {
  const props = planPaddockProps();

  it('places shelter, trough and hay rack inside the paddock, clear of the fence', () => {
    for (const key of ['shelter', 'trough', 'rack']) {
      const p = props[key];
      expect(paddockContains(p.x, p.z, 0.6)).toBe(true);
    }
  });

  it('turns the open side of the shelter towards the paddock', () => {
    const p = props.shelter;
    // the opening points from the shelter to the center of the paddock
    const toCenter = Math.atan2(PADDOCK.x - p.x, PADDOCK.z - p.z);
    let d = p.rotation - toCenter;
    d -= Math.round(d / (2 * Math.PI)) * 2 * Math.PI;
    expect(Math.abs(d)).toBeLessThan(Math.PI / 2);
  });
});
