import { describe, expect, it } from 'vitest';
import {
  checkLayout,
  corridorOf,
  footprint,
  rectsOverlap,
  segmentHitsRect,
} from './layout-check.js';

const el = (id, x, z, rot = 0, kind = 'vertical', spread = 0) => ({
  id,
  kind,
  height: 0.6,
  spread,
  x,
  z,
  rot,
});
const obs = (elements, directed = true) => ({ number: 1, elements, directed });

describe('Layout check', () => {
  it('accepts a single obstacle with enough room', () => {
    expect(checkLayout([obs([el('a', 0, 0)])])).toEqual([]);
  });

  it('reports too little distance to the fence', () => {
    const issues = checkLayout([obs([el('a', 16, 0)])]);
    expect(issues.some((i) => i.includes('fence'))).toBe(true);
  });

  it('reports a corridor that extends outside the arena', () => {
    // 14 m of approach before z = -22 reaches to -36
    const issues = checkLayout([obs([el('a', 0, -22)])]);
    expect(issues.some((i) => i.includes('outside the arena'))).toBe(true);
  });

  it('reports an obstacle in the approach corridor (before and after the jump)', () => {
    const before = checkLayout([obs([el('a', 0, 5)]), obs([el('b', 1, -5, Math.PI / 2)])]);
    expect(before.some((i) => i.includes('b is in the approach corridor'))).toBe(true);
    const after = checkLayout([obs([el('a', 0, 0)]), obs([el('b', 0, 6, Math.PI / 2)])]);
    expect(after.some((i) => i.includes('b is in the approach corridor'))).toBe(true);
  });

  it('directed obstacles need only the landing path behind, undirected ones the full approach', () => {
    const directed = corridorOf(obs([el('a', 0, 0)], true));
    const free = corridorOf(obs([el('a', 0, 0)], false));
    expect(directed.halfAlong * 2).toBeCloseTo(14 + 8, 6);
    expect(free.halfAlong * 2).toBeCloseTo(28, 6);
  });

  it('reports elements of different obstacles standing too close', () => {
    const issues = checkLayout([obs([el('a', -2.5, 0)]), obs([el('b', 2.5, 0)])]);
    expect(issues.some((i) => i.includes('too close'))).toBe(true);
  });

  it('allows elements of the same combination in the corridor', () => {
    expect(checkLayout([obs([el('a', 0, -4), el('b', 0, 3.3)])])).toEqual([]);
  });

  it('reports start/finish lines too close to elements or in the corridor', () => {
    const issues = checkLayout([obs([el('a', 0, 0)])], {
      lines: [{ name: 'finish', a: [-3, -10], b: [3, -10] }],
    });
    expect(issues.some((i) => i.includes('finish: crosses'))).toBe(true);
  });

  it('rectangle and segment intersection', () => {
    const r = footprint(el('a', 0, 0));
    expect(rectsOverlap(r, footprint(el('b', 1, 0)))).toBe(true);
    expect(rectsOverlap(r, footprint(el('b', 10, 0)))).toBe(false);
    expect(segmentHitsRect([-5, 0], [5, 0], r)).toBe(true);
    expect(segmentHitsRect([-5, 3], [5, 3], r)).toBe(false);
  });
});
