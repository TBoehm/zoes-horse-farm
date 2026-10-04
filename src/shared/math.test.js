import { describe, expect, it } from 'vitest';
import { clamp, isPlainObject } from './math.js';

describe('clamp', () => {
  it('keeps values inside the range', () => {
    expect(clamp(0.5, 0, 1)).toBe(0.5);
  });

  it('limits values below and above the range', () => {
    expect(clamp(-3, 0, 1)).toBe(0);
    expect(clamp(7, 0, 1)).toBe(1);
  });

  it('accepts the bounds themselves', () => {
    expect(clamp(0, 0, 1)).toBe(0);
    expect(clamp(1, 0, 1)).toBe(1);
  });
});

describe('isPlainObject', () => {
  it('accepts plain objects', () => {
    expect(isPlainObject({})).toBe(true);
    expect(isPlainObject({ a: 1 })).toBe(true);
  });

  it('rejects null, arrays and primitives', () => {
    expect(isPlainObject(null)).toBe(false);
    expect(isPlainObject([])).toBe(false);
    expect(isPlainObject('x')).toBe(false);
    expect(isPlainObject(3)).toBe(false);
    expect(isPlainObject(undefined)).toBe(false);
  });
});
