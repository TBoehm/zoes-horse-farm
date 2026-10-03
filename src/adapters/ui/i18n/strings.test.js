import { describe, expect, it } from 'vitest';
import { _dictionaries } from '../i18n.js';
import { registerAllStrings } from './index.js';

describe('texts (rule 6)', () => {
  registerAllStrings();
  const { de, en } = _dictionaries();
  it('have the same keys in German and English', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });
  it('are not empty and have the same placeholders', () => {
    for (const key of Object.keys(de)) {
      expect(de[key].trim(), key).not.toBe('');
      expect(en[key].trim(), key).not.toBe('');
      const ph = (s) => (s.match(/\{\w+\}/g) ?? []).sort();
      expect(ph(de[key]), key).toEqual(ph(en[key]));
    }
  });
});
