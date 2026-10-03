import { describe, expect, it } from 'vitest';
import { formatCs, formatSeconds } from './format.js';

describe('time display in hundredths', () => {
  it('formats mm:ss,hh', () => {
    expect(formatCs(4827)).toBe('0:48,27');
    expect(formatCs(6001)).toBe('1:00,01');
    expect(formatCs(4827, 'en')).toBe('0:48.27');
  });
  it('formats seconds', () => {
    expect(formatSeconds(4827)).toBe('48,27');
    expect(formatSeconds(5)).toBe('0,05');
  });
});
