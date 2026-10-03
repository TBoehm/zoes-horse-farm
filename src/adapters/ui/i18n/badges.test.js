import { describe, expect, it } from 'vitest';
import { BADGES } from '../../../domain/progress/badges.js';
import strings from './badges.js';

describe('badge texts', () => {
  it('de and en have the same keys', () => {
    expect(Object.keys(strings.en).sort()).toEqual(Object.keys(strings.de).sort());
  });

  it('contain all keys from BADGES, non-empty', () => {
    for (const lang of ['de', 'en']) {
      for (const b of BADGES) {
        expect(strings[lang][b.nameKey], `${lang} ${b.nameKey}`).toBeTruthy();
        expect(strings[lang][b.conditionKey], `${lang} ${b.conditionKey}`).toBeTruthy();
      }
    }
  });

  it('names match the concept', () => {
    expect(BADGES.map((b) => strings.de[b.nameKey])).toEqual([
      'Erster Sprung',
      'Springmaus',
      'Fehlerfrei',
      'Oxer-Profi',
      'Kombi-Könner',
      'Alles offen',
      'Sternenreiter',
      'Fleißig',
    ]);
  });

  it('placeholders match in both languages', () => {
    const placeholders = (s) => (s.match(/\{\w+\}/g) ?? []).sort().join();
    for (const key of Object.keys(strings.de)) {
      expect(placeholders(strings.en[key])).toBe(placeholders(strings.de[key]));
    }
  });
});
