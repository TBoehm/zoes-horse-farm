import { describe, expect, it } from 'vitest';
import { checkLayout, corridorOf, footprint, rectsOverlap, segmentHitsRect } from './layout-check.js';

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

describe('Layout-Prüfung', () => {
  it('akzeptiert ein einzelnes Hindernis mit Platz', () => {
    expect(checkLayout([obs([el('a', 0, 0)])])).toEqual([]);
  });

  it('meldet zu geringen Zaunabstand', () => {
    const issues = checkLayout([obs([el('a', 16, 0)])]);
    expect(issues.some((i) => i.includes('Zaun'))).toBe(true);
  });

  it('meldet einen Korridor, der aus dem Platz ragt', () => {
    // 14 m Anritt vor z = -22 reicht bis -36
    const issues = checkLayout([obs([el('a', 0, -22)])]);
    expect(issues.some((i) => i.includes('ragt'))).toBe(true);
  });

  it('meldet ein Hindernis im Anreit-Korridor (vor und hinter dem Sprung)', () => {
    const before = checkLayout([obs([el('a', 0, 5)]), obs([el('b', 1, -5, Math.PI / 2)])]);
    expect(before.some((i) => i.includes('b steht im Anreit-Korridor'))).toBe(true);
    const after = checkLayout([obs([el('a', 0, 0)]), obs([el('b', 0, 6, Math.PI / 2)])]);
    expect(after.some((i) => i.includes('b steht im Anreit-Korridor'))).toBe(true);
  });

  it('gerichtete Hindernisse brauchen hinten nur den Landeweg, ungerichtete den vollen Anritt', () => {
    const directed = corridorOf(obs([el('a', 0, 0)], true));
    const free = corridorOf(obs([el('a', 0, 0)], false));
    expect(directed.halfAlong * 2).toBeCloseTo(14 + 8, 6);
    expect(free.halfAlong * 2).toBeCloseTo(28, 6);
  });

  it('meldet zu dicht stehende Elemente verschiedener Hindernisse', () => {
    const issues = checkLayout([obs([el('a', -2.5, 0)]), obs([el('b', 2.5, 0)])]);
    expect(issues.some((i) => i.includes('zu dicht'))).toBe(true);
  });

  it('erlaubt Elemente derselben Kombination im Korridor', () => {
    expect(checkLayout([obs([el('a', 0, -4), el('b', 0, 3.3)])])).toEqual([]);
  });

  it('meldet Start-/Ziellinien zu nah an Elementen oder im Korridor', () => {
    const issues = checkLayout([obs([el('a', 0, 0)])], {
      lines: [{ name: 'ziel', a: [-3, -10], b: [3, -10] }],
    });
    expect(issues.some((i) => i.includes('ziel: kreuzt'))).toBe(true);
  });

  it('Rechteck- und Strecken-Schnitt', () => {
    const r = footprint(el('a', 0, 0));
    expect(rectsOverlap(r, footprint(el('b', 1, 0)))).toBe(true);
    expect(rectsOverlap(r, footprint(el('b', 10, 0)))).toBe(false);
    expect(segmentHitsRect([-5, 0], [5, 0], r)).toBe(true);
    expect(segmentHitsRect([-5, 3], [5, 3], r)).toBe(false);
  });
});
