import { describe, expect, it } from 'vitest';
import { COATS, MARKINGS, coatParams, markingIndex, normalizeAppearance } from './coats.js';

describe('Fellfarben und Abzeichen', () => {
  it('bietet die fünf Fellfarben und vier Kopfabzeichen aus Regel 43', () => {
    expect(COATS).toEqual(['chestnut', 'bay', 'black', 'grey', 'pinto']);
    expect(MARKINGS).toEqual(['none', 'star', 'blaze', 'snip']);
  });

  it('Vorgabe ist Brauner mit Stern; Ungültiges fällt auf die Vorgabe zurück', () => {
    expect(normalizeAppearance()).toEqual({ coat: 'bay', marking: 'star' });
    expect(normalizeAppearance({ coat: 'zebra', marking: 'x' })).toEqual({
      coat: 'bay',
      marking: 'star',
    });
    expect(normalizeAppearance({ coat: 'grey', marking: 'none' })).toEqual({
      coat: 'grey',
      marking: 'none',
    });
  });

  const lum = (c) => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];

  it('Fuchs: rötlich, Langhaar heller als das Fell', () => {
    const p = coatParams('chestnut');
    expect(p.base[0]).toBeGreaterThan(p.base[2] * 2);
    expect(lum(p.hair)).toBeGreaterThan(lum(p.base));
    expect(p.points).toBe(0);
  });

  it('Brauner: braunes Fell, schwarzes Langhaar und schwarze Beine unten', () => {
    const p = coatParams('bay');
    expect(lum(p.hair)).toBeLessThan(0.08);
    expect(p.points).toBe(1);
    expect(lum(p.pointColor)).toBeLessThan(0.08);
    expect(lum(p.base)).toBeGreaterThan(0.15);
  });

  it('Rappe ist schwarz, Schimmel hell mit Apfelung, Schecke mit weißen Platten', () => {
    expect(lum(coatParams('black').base)).toBeLessThan(0.1);
    const g = coatParams('grey');
    expect(lum(g.base)).toBeGreaterThan(0.7);
    expect(g.dapple).toBe(1);
    expect(lum(g.muzzle)).toBeLessThan(0.3);
    expect(coatParams('pinto').pinto).toBe(1);
  });

  it('Abzeichen sind auf dunklem Fell deutlich, beim Schimmel kaum sichtbar', () => {
    const contrast = (coat) => lum(coatParams(coat).white) - lum(coatParams(coat).base);
    expect(contrast('bay')).toBeGreaterThan(0.5);
    expect(contrast('grey')).toBeLessThan(0.2);
  });

  it('markingIndex: none 0, star 1, blaze 2, snip 3', () => {
    expect(MARKINGS.map(markingIndex)).toEqual([0, 1, 2, 3]);
    expect(markingIndex('foo')).toBe(1);
  });
});
