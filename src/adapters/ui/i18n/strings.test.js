import { describe, expect, it } from 'vitest';
import { _dictionaries } from '../i18n.js';
import { registerAllStrings } from './index.js';

describe('Texte (Regel 6)', () => {
  registerAllStrings();
  const { de, en } = _dictionaries();
  it('haben in Deutsch und Englisch dieselben Schlüssel', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });
  it('sind nicht leer und haben dieselben Platzhalter', () => {
    for (const key of Object.keys(de)) {
      expect(de[key].trim(), key).not.toBe('');
      expect(en[key].trim(), key).not.toBe('');
      const ph = (s) => (s.match(/\{\w+\}/g) ?? []).sort();
      expect(ph(de[key]), key).toEqual(ph(en[key]));
    }
  });
});
