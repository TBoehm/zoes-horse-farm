import { describe, expect, it } from 'vitest';
import { contrastRatio, parseHex, relativeLuminance } from './color.js';

describe('parseHex', () => {
  it('parses six-digit and three-digit hex colors', () => {
    expect(parseHex('#2E7D32')).toEqual([46, 125, 50]);
    expect(parseHex('#fff')).toEqual([255, 255, 255]);
    expect(parseHex('  #000000 ')).toEqual([0, 0, 0]);
  });

  it('returns null for anything else', () => {
    expect(parseHex('red')).toBeNull();
    expect(parseHex('#12')).toBeNull();
    expect(parseHex('#12345g')).toBeNull();
    expect(parseHex(undefined)).toBeNull();
  });
});

describe('relativeLuminance', () => {
  it('is 0 for black and 1 for white', () => {
    expect(relativeLuminance([0, 0, 0])).toBe(0);
    expect(relativeLuminance([255, 255, 255])).toBeCloseTo(1, 10);
  });
});

describe('contrastRatio', () => {
  it('is 21 for black on white, in either order', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 5);
  });

  it('is 1 for identical colors', () => {
    expect(contrastRatio('#c8502e', '#c8502e')).toBeCloseTo(1, 10);
  });

  it('matches known WCAG values', () => {
    expect(contrastRatio('#2E7D32', '#ffffff')).toBeCloseTo(5.13, 1);
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 1);
  });

  it('throws for a color that cannot be parsed', () => {
    expect(() => contrastRatio('nope', '#fff')).toThrow(/color/i);
  });
});
