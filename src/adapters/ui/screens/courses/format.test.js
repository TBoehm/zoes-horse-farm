import { describe, expect, it } from 'vitest';
import { formatCs } from './format.js';

describe('time display in hundredths', () => {
  it('formats mm:ss,hh with two-digit minutes', () => {
    expect(formatCs(4827)).toBe('00:48,27');
    expect(formatCs(6001)).toBe('01:00,01');
    expect(formatCs(0)).toBe('00:00,00');
    expect(formatCs(7520)).toBe('01:15,20');
  });

  it('uses a decimal point in English', () => {
    expect(formatCs(4827, 'en')).toBe('00:48.27');
  });

  it('rounds to whole hundredths and never shows a negative time', () => {
    expect(formatCs(4826.6)).toBe('00:48,27');
    expect(formatCs(-5)).toBe('00:00,00');
  });

  it('keeps counting past ten minutes', () => {
    expect(formatCs(60_000)).toBe('10:00,00');
  });
});
