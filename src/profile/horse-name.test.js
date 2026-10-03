import { describe, expect, it } from 'vitest';
import { cleanName, displayName } from './horse-name.js';

describe('Pferdename (Regel 43)', () => {
  it('entfernt Leerzeichen am Anfang und Ende', () => {
    expect(cleanName('  Luna ')).toBe('Luna');
  });
  it('lehnt leere und zu lange Namen ab', () => {
    expect(cleanName('')).toBeNull();
    expect(cleanName('    ')).toBeNull();
    expect(cleanName('A'.repeat(17))).toBeNull();
    expect(cleanName(42)).toBeNull();
  });
  it('erlaubt 1 bis 16 Zeichen', () => {
    expect(cleanName('A')).toBe('A');
    expect(cleanName('B'.repeat(16))).toBe('B'.repeat(16));
    expect(cleanName('Äpfelchen 🐴')).toBe('Äpfelchen 🐴');
  });
  it('nutzt den Vorgabe-Namen der Sprache ohne eigenen Namen', () => {
    const t = () => 'Blitz';
    expect(displayName({ name: null }, t)).toBe('Blitz');
    expect(displayName({ name: 'Luna' }, t)).toBe('Luna');
  });
});
