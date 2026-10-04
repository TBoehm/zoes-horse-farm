import { describe, expect, it } from 'vitest';
import { STRING_AREAS } from './index.js';

describe('texts (rule 6)', () => {
  const de = Object.assign({}, ...STRING_AREAS.map((a) => a.de));
  const en = Object.assign({}, ...STRING_AREAS.map((a) => a.en));
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
  it('do not define a key twice across areas', () => {
    const keys = STRING_AREAS.flatMap((a) => Object.keys(a.de));
    expect(new Set(keys).size).toBe(keys.length);
  });
  it('say "fallen pole" instead of "Abwurf" (rule 23: a child reads "Abwurf" as a fall of the rider)', () => {
    expect(de['feedback.knockdown']).toBe('Stange gefallen!');
    expect(de['results.knockdowns']).toBe('Gefallene Stangen');
    expect(en['feedback.knockdown']).toBe('Pole down!');
    for (const text of Object.values(de)) expect(text).not.toMatch(/Abwurf|Abwürfe/);
  });
});
