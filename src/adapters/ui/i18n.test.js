import { describe, expect, it } from 'vitest';
import { detectLang, registerStrings, setLang, t, getLang } from './i18n.js';

describe('detectLang (Regel 6)', () => {
  it('wählt Deutsch bei deutscher Browsersprache', () => {
    expect(detectLang(['de-DE', 'en'])).toBe('de');
    expect(detectLang(['de'])).toBe('de');
    expect(detectLang('de-AT')).toBe('de');
  });
  it('wählt sonst Englisch', () => {
    expect(detectLang(['en-US', 'de'])).toBe('en');
    expect(detectLang(['fr-FR'])).toBe('en');
    expect(detectLang([])).toBe('en');
    expect(detectLang(undefined)).toBe('en');
  });
});

describe('t', () => {
  it('liefert Text der aktiven Sprache mit Platzhaltern', () => {
    registerStrings({ de: { 'x.hi': 'Hallo {name}' }, en: { 'x.hi': 'Hi {name}' } });
    setLang('de');
    expect(t('x.hi', { name: 'Blitz' })).toBe('Hallo Blitz');
    setLang('en');
    expect(getLang()).toBe('en');
    expect(t('x.hi', { name: 'Flash' })).toBe('Hi Flash');
  });
});
