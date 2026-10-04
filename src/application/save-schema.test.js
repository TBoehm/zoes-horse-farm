import { describe, expect, it } from 'vitest';
import {
  addSettingsFields,
  field,
  getSections,
  objectSection,
  registerSection,
} from './save-schema.js';
import { LANGS } from './languages.js';

describe('field specs', () => {
  it('enum accepts only listed values', () => {
    const spec = field.enum(['a', 'b'], 'a');
    expect(spec.check('b')).toBe(true);
    expect(spec.check('c')).toBe(false);
    expect(spec.check(undefined)).toBe(false);
    expect(spec.fallback).toBe('a');
  });

  it('enum can list null as a valid value', () => {
    expect(field.enum(['a', null], null).check(null)).toBe(true);
  });

  it('bool accepts only booleans', () => {
    const spec = field.bool(true);
    expect(spec.check(false)).toBe(true);
    expect(spec.check(0)).toBe(false);
    expect(spec.check('true')).toBe(false);
  });

  it('number accepts finite numbers inside the limits, limits included', () => {
    const spec = field.number(0, 1, 0.5);
    expect(spec.check(0)).toBe(true);
    expect(spec.check(1)).toBe(true);
    expect(spec.check(-0.01)).toBe(false);
    expect(spec.check(1.01)).toBe(false);
    expect(spec.check(NaN)).toBe(false);
    expect(spec.check(Infinity)).toBe(false);
    expect(spec.check('0.5')).toBe(false);
  });
});

describe('objectSection', () => {
  const section = objectSection({
    mode: field.enum(['x', 'y'], 'x'),
    lang: { fallback: (env) => env.defaultLang, check: (v) => LANGS.includes(v) },
  });

  it('builds defaults, resolving fallbacks from the environment', () => {
    expect(section.defaults({ defaultLang: 'de' })).toEqual({ mode: 'x', lang: 'de' });
  });

  it('replaces invalid fields with the env-dependent fallback and keeps valid ones', () => {
    expect(section.sanitize({ mode: 'y', lang: 'fr' }, { defaultLang: 'en' })).toEqual({
      mode: 'y',
      lang: 'en',
    });
  });

  it('keeps unknown fields', () => {
    expect(section.sanitize({ mode: 'x', lang: 'de', future: { a: 1 } }, {}).future).toEqual({
      a: 1,
    });
  });

  it.each([null, undefined, 42, 'text', []])('returns the defaults for %j', (raw) => {
    expect(section.sanitize(raw, { defaultLang: 'en' })).toEqual({ mode: 'x', lang: 'en' });
  });

  it('does not mutate the raw input', () => {
    const raw = { mode: 'bad' };
    section.sanitize(raw, { defaultLang: 'en' });
    expect(raw).toEqual({ mode: 'bad' });
  });
});

describe('settings section', () => {
  it('starts with the language field, defaulting to the environment language', () => {
    const settings = getSections().get('settings');
    expect(settings.defaults({ defaultLang: 'de' })).toEqual({ lang: 'de' });
    expect(settings.defaults({})).toEqual({ lang: 'en' });
    expect(settings.sanitize({ lang: 'fr' }, { defaultLang: 'de' }).lang).toBe('de');
  });

  it('addSettingsFields merges new fields and keeps the existing ones', () => {
    addSettingsFields({ sound: field.bool(true) });
    addSettingsFields({ speed: field.number(0, 2, 1) });
    const settings = getSections().get('settings');
    expect(settings.defaults({ defaultLang: 'de' })).toEqual({ lang: 'de', sound: true, speed: 1 });
    expect(settings.sanitize({ lang: 'en', sound: 3, speed: 2 }, {})).toEqual({
      lang: 'en',
      sound: true,
      speed: 2,
    });
  });

  it('registerSection adds a section by name', () => {
    const section = objectSection({ a: field.bool(false) });
    registerSection('custom', section);
    expect(getSections().get('custom')).toBe(section);
  });
});
