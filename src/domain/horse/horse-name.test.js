import { describe, expect, it } from 'vitest';
import { cleanName, displayName } from './horse-name.js';

describe('Horse name (rule 43)', () => {
  it('trims leading and trailing whitespace', () => {
    expect(cleanName('  Luna ')).toBe('Luna');
  });
  it('rejects empty and too-long names', () => {
    expect(cleanName('')).toBeNull();
    expect(cleanName('    ')).toBeNull();
    expect(cleanName('A'.repeat(17))).toBeNull();
    expect(cleanName(42)).toBeNull();
  });
  it('allows 1 to 16 characters', () => {
    expect(cleanName('A')).toBe('A');
    expect(cleanName('B'.repeat(16))).toBe('B'.repeat(16));
    expect(cleanName('Äpfelchen 🐴')).toBe('Äpfelchen 🐴');
  });
  it('uses the language default name when there is no custom name', () => {
    const t = () => 'Blitz';
    expect(displayName({ name: null }, t)).toBe('Blitz');
    expect(displayName({ name: 'Luna' }, t)).toBe('Luna');
  });
});
