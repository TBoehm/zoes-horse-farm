import { describe, it, expect } from 'vitest';
import { createRng } from './textures.js';
import { isBlocked, paddockContains, SITE } from './world-layout.js';
import { FLOWER_COLORS, planMeadow, butterflyAnchors } from './meadow-plan.js';

const plan = (seed = 5, options) => planMeadow(createRng(seed), options);

describe('planMeadow', () => {
  it('is deterministic for a seed', () => {
    expect(plan(7)).toEqual(plan(7));
    expect(plan(7).flowers).not.toEqual(plan(8).flowers);
  });

  it('plants a dense meadow in patches of several colours', () => {
    const { patches, flowers } = plan();
    expect(patches.length).toBeGreaterThanOrEqual(30);
    expect(flowers.length).toBeGreaterThanOrEqual(3000);
    const used = new Set(flowers.map((f) => f.color));
    expect(used.size).toBe(FLOWER_COLORS.length);
    for (const name of ['white', 'yellow', 'pink', 'violet', 'blue']) {
      expect(FLOWER_COLORS.some((c) => c.name === name)).toBe(true);
    }
  });

  it('keeps flowers off the sand, the paths, the buildings and the paddock', () => {
    const { flowers } = plan(3);
    for (const f of flowers) {
      expect(isBlocked(f.x, f.z, 0.3)).toBe(false);
      expect(paddockContains(f.x, f.z, -1.2)).toBe(false);
    }
  });

  it('stays on the flat meadow around the facility', () => {
    for (const f of plan(4).flowers) {
      expect(Math.hypot(f.x, f.z)).toBeLessThan(90);
    }
  });

  it('puts patches near the judges hut and the stable', () => {
    const { patches } = plan();
    const near = (target, radius) =>
      patches.filter((p) => Math.hypot(p.x - target.x, p.z - target.z) < radius).length;
    expect(near(SITE.hut, 12)).toBeGreaterThanOrEqual(2);
    expect(near(SITE.stable, 22)).toBeGreaterThanOrEqual(2);
  });

  it('keeps flowers close to their patch with a sensible size', () => {
    const { patches, flowers } = plan(9);
    for (const f of flowers) {
      const patch = patches[f.patch];
      expect(Math.hypot(f.x - patch.x, f.z - patch.z)).toBeLessThanOrEqual(patch.radius + 1e-9);
      expect(f.scale).toBeGreaterThan(0.6);
      expect(f.scale).toBeLessThan(1.5);
    }
  });

  it('orders the flowers so that every prefix is a thinner copy of the whole meadow', () => {
    const { patches, flowers } = plan(2);
    for (const share of [0.25, 0.5]) {
      const seen = new Set(
        flowers.slice(0, Math.round(flowers.length * share)).map((f) => f.patch),
      );
      expect(seen.size).toBeGreaterThanOrEqual(patches.length * 0.9);
    }
  });

  it('gives most patches a main colour and some a second one', () => {
    const { patches, flowers } = plan(6);
    const mixed = patches.filter((p) => p.accent !== null).length;
    expect(mixed).toBeGreaterThan(0);
    expect(mixed).toBeLessThan(patches.length);
    const main = flowers.filter((f) => f.color === patches[f.patch].color).length;
    expect(main / flowers.length).toBeGreaterThan(0.6);
  });

  it('accepts a smaller meadow for tests and low budgets', () => {
    const { flowers } = plan(1, { patchCount: 6, perPatch: [10, 12] });
    expect(flowers.length).toBeLessThanOrEqual(6 * 12);
    expect(flowers.length).toBeGreaterThanOrEqual(6 * 10);
  });
});

describe('butterflyAnchors', () => {
  it('picks patches within sight of the arena', () => {
    const { patches } = plan();
    const anchors = butterflyAnchors(patches, 8);
    expect(anchors).toHaveLength(8);
    for (const a of anchors) {
      expect(Math.hypot(a.x, a.z)).toBeLessThan(60);
      expect(a.radius).toBeGreaterThan(0);
    }
  });

  it('returns fewer anchors when there are not enough patches', () => {
    const { patches } = plan(1, { patchCount: 3, perPatch: [5, 5] });
    expect(butterflyAnchors(patches, 10).length).toBeLessThanOrEqual(3);
  });
});
