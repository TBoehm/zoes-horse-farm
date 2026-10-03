import { describe, expect, it } from 'vitest';
import { createStore, SAVE_KEY } from './local-store.js';
import { field, objectSection, registerSection } from '../../application/save-schema.js';

function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    map,
  };
}

function failingStorage() {
  return {
    getItem: () => null,
    setItem: () => {
      throw new Error('QuotaExceededError');
    },
    removeItem: () => {},
  };
}

const env = { defaultLang: 'de' };

describe('Spielstand laden (Regel 47)', () => {
  it('nutzt Anfangswerte bei leerem Speicher (Startsprache aus env)', () => {
    const store = createStore({ backend: memoryStorage(), sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('de');
  });

  it('startet mit kaputtem JSON ohne Absturz', () => {
    const backend = memoryStorage({ [SAVE_KEY]: '{kaputt' });
    const store = createStore({ backend, sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('de');
  });

  it('übernimmt lesbare Werte und setzt ungültige zurück', () => {
    const backend = memoryStorage({
      [SAVE_KEY]: JSON.stringify({ version: 1, settings: { lang: 'en', extra: 7 }, horse: 5 }),
    });
    const store = createStore({ backend, sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('en');
    expect(store.get('settings').extra).toBe(7);

    const bad = memoryStorage({ [SAVE_KEY]: JSON.stringify({ settings: { lang: 'fr' } }) });
    expect(createStore({ backend: bad, env }).get('settings').lang).toBe('de');
  });

  it('akzeptiert Nicht-Objekte als Spielstand', () => {
    for (const text of ['42', '"x"', 'null', '[1,2]']) {
      const store = createStore({ backend: memoryStorage({ [SAVE_KEY]: text }), env });
      expect(store.get('settings').lang).toBe('de');
    }
  });

  it('lädt ältere Stände mit fehlenden Bereichen ohne Verlust', () => {
    const backend = memoryStorage({ [SAVE_KEY]: JSON.stringify({ settings: { lang: 'en' } }) });
    registerSection('testArea', objectSection({ level: field.number(0, 9, 1) }));
    const store = createStore({ backend, env });
    expect(store.get('testArea').level).toBe(1);
    expect(store.get('settings').lang).toBe('en');
  });
});

describe('Spielstand speichern (Regeln 45, 47)', () => {
  it('speichert sofort bei Änderung', () => {
    const backend = memoryStorage();
    const store = createStore({ backend, env });
    store.update('settings', (s) => ({ ...s, lang: 'en' }));
    expect(JSON.parse(backend.getItem(SAVE_KEY)).settings.lang).toBe('en');
    expect(createStore({ backend, env }).get('settings').lang).toBe('en');
  });

  it('erhält unbekannte Bereiche und Felder unverändert', () => {
    const future = { stable: { horses: [{ name: 'Luna' }] }, version: 3 };
    const backend = memoryStorage({
      [SAVE_KEY]: JSON.stringify({ ...future, settings: { lang: 'de', futureFlag: true } }),
    });
    const store = createStore({ backend, env });
    store.update('settings', (s) => ({ ...s, lang: 'en' }));
    const saved = JSON.parse(backend.getItem(SAVE_KEY));
    expect(saved.stable).toEqual(future.stable);
    expect(saved.settings.futureFlag).toBe(true);
    expect(saved.version).toBe(3);
  });

  it('ein neuer Bereich ergänzt Daten, ohne bestehende zu löschen', () => {
    const backend = memoryStorage({
      [SAVE_KEY]: JSON.stringify({ settings: { lang: 'en' }, other: { a: 1 } }),
    });
    registerSection('breeding', objectSection({ foals: field.number(0, 99, 0) }));
    const store = createStore({ backend, env });
    store.update('breeding', (b) => ({ ...b, foals: 2 }));
    const saved = JSON.parse(backend.getItem(SAVE_KEY));
    expect(saved.breeding.foals).toBe(2);
    expect(saved.settings.lang).toBe('en');
    expect(saved.other).toEqual({ a: 1 });
  });
});

describe('Später erweiterte Bereiche (Regel 47)', () => {
  it('liefert neue Felder auch ohne vorheriges update', () => {
    registerSection('lateArea', objectSection({ a: field.number(0, 9, 1) }));
    const store = createStore({ backend: memoryStorage(), env });
    expect(store.get('lateArea')).toEqual({ a: 1 });
    registerSection('lateArea', objectSection({ a: field.number(0, 9, 1), b: field.bool(true) }));
    expect(store.get('lateArea')).toEqual({ a: 1, b: true });
  });
});

describe('Speichern nicht möglich (Regel 46)', () => {
  it('bleibt bedienbar und meldet canSave=false', () => {
    const store = createStore({ backend: failingStorage(), sessionBackend: memoryStorage(), env });
    expect(store.canSave).toBe(false);
    expect(() => store.update('settings', (s) => ({ ...s, lang: 'en' }))).not.toThrow();
    expect(store.get('settings').lang).toBe('en');
  });

  it('zeigt den Hinweis einmal je Sitzung, auch nach Neuladen nicht erneut', () => {
    const session = memoryStorage();
    const a = createStore({ backend: failingStorage(), sessionBackend: session, env });
    expect(a.shouldShowSaveNotice()).toBe(true);
    expect(a.shouldShowSaveNotice()).toBe(false);
    const reloaded = createStore({ backend: failingStorage(), sessionBackend: session, env });
    expect(reloaded.shouldShowSaveNotice()).toBe(false);
    const newSession = createStore({
      backend: failingStorage(),
      sessionBackend: memoryStorage(),
      env,
    });
    expect(newSession.shouldShowSaveNotice()).toBe(true);
  });

  it('zeigt keinen Hinweis, wenn Speichern geht', () => {
    const store = createStore({ backend: memoryStorage(), sessionBackend: memoryStorage(), env });
    expect(store.shouldShowSaveNotice()).toBe(false);
  });

  it('kommt ohne jeden Speicher aus, auch nach Neuladen nur einmal', () => {
    let marked = false;
    const noticeMarker = { get: () => marked, set: () => (marked = true) };
    const store = createStore({ backend: null, sessionBackend: null, env, noticeMarker });
    expect(store.canSave).toBe(false);
    expect(store.shouldShowSaveNotice()).toBe(true);
    expect(store.shouldShowSaveNotice()).toBe(false);
    const reloaded = createStore({ backend: null, sessionBackend: null, env, noticeMarker });
    expect(reloaded.shouldShowSaveNotice()).toBe(false);
  });

  it('nutzt den Ersatz-Merker, wenn sessionStorage wirft', () => {
    let marked = false;
    const noticeMarker = { get: () => marked, set: () => (marked = true) };
    const session = failingStorage();
    const a = createStore({
      backend: failingStorage(),
      sessionBackend: session,
      env,
      noticeMarker,
    });
    expect(a.shouldShowSaveNotice()).toBe(true);
    const b = createStore({
      backend: failingStorage(),
      sessionBackend: session,
      env,
      noticeMarker,
    });
    expect(b.shouldShowSaveNotice()).toBe(false);
  });
});
