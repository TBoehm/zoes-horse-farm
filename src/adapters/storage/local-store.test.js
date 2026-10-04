import { describe, expect, it } from 'vitest';
import { createStore, SAVE_KEY } from './local-store.js';
import { field, objectSection, registerSection } from '../../application/save-schema.js';
import { createCrashGuard, HEARTBEAT_INTERVAL_MS } from '../../application/crash-guard.js';

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

describe('loading save data (rule 47)', () => {
  it('uses initial values for empty storage (start language from env)', () => {
    const store = createStore({ backend: memoryStorage(), sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('de');
  });

  it('starts with broken JSON without crashing', () => {
    const backend = memoryStorage({ [SAVE_KEY]: '{broken' });
    const store = createStore({ backend, sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('de');
  });

  it('keeps readable values and resets invalid ones', () => {
    const backend = memoryStorage({
      [SAVE_KEY]: JSON.stringify({ version: 1, settings: { lang: 'en', extra: 7 }, horse: 5 }),
    });
    const store = createStore({ backend, sessionBackend: memoryStorage(), env });
    expect(store.get('settings').lang).toBe('en');
    expect(store.get('settings').extra).toBe(7);

    const bad = memoryStorage({ [SAVE_KEY]: JSON.stringify({ settings: { lang: 'fr' } }) });
    expect(createStore({ backend: bad, env }).get('settings').lang).toBe('de');
  });

  it('accepts non-objects as save data', () => {
    for (const text of ['42', '"x"', 'null', '[1,2]']) {
      const store = createStore({ backend: memoryStorage({ [SAVE_KEY]: text }), env });
      expect(store.get('settings').lang).toBe('de');
    }
  });

  it('loads older saves with missing sections without loss', () => {
    const backend = memoryStorage({ [SAVE_KEY]: JSON.stringify({ settings: { lang: 'en' } }) });
    registerSection('testArea', objectSection({ level: field.number(0, 9, 1) }));
    const store = createStore({ backend, env });
    expect(store.get('testArea').level).toBe(1);
    expect(store.get('settings').lang).toBe('en');
  });
});

describe('saving save data (rules 45, 47)', () => {
  it('saves immediately on change', () => {
    const backend = memoryStorage();
    const store = createStore({ backend, env });
    store.update('settings', (s) => ({ ...s, lang: 'en' }));
    expect(JSON.parse(backend.getItem(SAVE_KEY)).settings.lang).toBe('en');
    expect(createStore({ backend, env }).get('settings').lang).toBe('en');
  });

  it('preserves unknown sections and fields unchanged', () => {
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

  it('a new section adds data without deleting existing data', () => {
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

describe('sections extended later (rule 47)', () => {
  it('returns new fields even without a prior update', () => {
    registerSection('lateArea', objectSection({ a: field.number(0, 9, 1) }));
    const store = createStore({ backend: memoryStorage(), env });
    expect(store.get('lateArea')).toEqual({ a: 1 });
    registerSection('lateArea', objectSection({ a: field.number(0, 9, 1), b: field.bool(true) }));
    expect(store.get('lateArea')).toEqual({ a: 1, b: true });
  });
});

describe('several tabs (reload)', () => {
  registerSection('tabProgress', objectSection({ jumps: field.number(0, 999, 0) }));
  const open = (backend) => createStore({ backend, sessionBackend: memoryStorage(), env });
  const saved = (backend) => JSON.parse(backend.getItem(SAVE_KEY));

  it('takes over what another tab saved, so a later write does not overwrite it', () => {
    const backend = memoryStorage();
    const a = open(backend);
    const b = open(backend);
    b.update('tabProgress', (p) => ({ ...p, jumps: 42 }));
    a.reload();
    expect(a.get('tabProgress').jumps).toBe(42);
    a.update('settings', (s) => ({ ...s, lang: 'en' }));
    expect(saved(backend).tabProgress.jumps).toBe(42);
    expect(saved(backend).settings.lang).toBe('en');
  });

  it('without a reload a write of the other tab is overwritten (the case reload is for)', () => {
    const backend = memoryStorage();
    const a = open(backend);
    const b = open(backend);
    b.update('tabProgress', (p) => ({ ...p, jumps: 42 }));
    a.update('settings', (s) => ({ ...s, lang: 'en' }));
    expect(saved(backend).tabProgress.jumps).toBe(0);
  });

  it('sanitizes what it takes over and emits a change only for sections that differ', () => {
    const backend = memoryStorage();
    const a = open(backend);
    const b = open(backend);
    b.update('tabProgress', (p) => ({ ...p, jumps: 7 }));
    const changed = [];
    a.onChange('tabProgress', () => changed.push('tabProgress'));
    a.onChange('settings', () => changed.push('settings'));
    backend.setItem(
      SAVE_KEY,
      JSON.stringify({ ...saved(backend), settings: { ...saved(backend).settings, lang: 'xx' } }),
    );
    a.reload();
    expect(changed).toEqual(['tabProgress']);
    expect(a.get('settings').lang).toBe('de');
    a.reload();
    expect(changed).toEqual(['tabProgress']);
  });

  it('keeps the memory when the storage is empty or unreadable, and unknown sections', () => {
    const backend = memoryStorage();
    const a = open(backend);
    a.update('tabProgress', (p) => ({ ...p, jumps: 3 }));
    backend.map.set(SAVE_KEY, '{broken');
    a.reload();
    expect(a.get('tabProgress').jumps).toBe(3);
    backend.map.set(SAVE_KEY, JSON.stringify({ tabProgress: { jumps: 5 }, future: { x: 1 } }));
    a.reload();
    expect(a.get('tabProgress').jumps).toBe(5);
    a.update('settings', (s) => ({ ...s, lang: 'en' }));
    expect(saved(backend).future).toEqual({ x: 1 });
    expect(open(null).reload()).toBeUndefined();
  });

  it('a crash guard heartbeat in one tab keeps the progress another tab saved', () => {
    const backend = memoryStorage();
    const a = open(backend);
    const b = open(backend);
    let nowMs = 1_000_000;
    const clock = { nowMs: () => nowMs, nowIso: () => new Date(nowMs).toISOString() };
    const guard = createCrashGuard({
      store: a,
      settings: { setAutoLevel() {} },
      clock,
      decide: () => ({ level: 'low', persist: false, hint: false }),
    });
    const lease = guard.markRendering({ level: 'medium', auto: true });
    b.update('tabProgress', (p) => ({ ...p, jumps: 42 }));
    nowMs += HEARTBEAT_INTERVAL_MS;
    lease.frame({ level: 'medium', auto: true });
    expect(saved(backend).tabProgress.jumps).toBe(42);
    expect(saved(backend).crashGuard).toMatchObject({ rendering: true, lastSeen: nowMs });
    lease.release();
    // a second tab that starts now sees a clean save (no crash)
    const second = createCrashGuard({
      store: open(backend),
      settings: { setAutoLevel() {} },
      clock,
      decide: () => ({ level: 'low', persist: false, hint: false }),
    });
    expect(second.checkPreviousRun()).toEqual({ crashed: false });
  });
});

describe('saving not possible (rule 46)', () => {
  it('stays usable and reports canSave=false', () => {
    const store = createStore({ backend: failingStorage(), sessionBackend: memoryStorage(), env });
    expect(store.canSave).toBe(false);
    expect(() => store.update('settings', (s) => ({ ...s, lang: 'en' }))).not.toThrow();
    expect(store.get('settings').lang).toBe('en');
  });

  it('shows the notice once per session, not again after a reload', () => {
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

  it('shows no notice when saving works', () => {
    const store = createStore({ backend: memoryStorage(), sessionBackend: memoryStorage(), env });
    expect(store.shouldShowSaveNotice()).toBe(false);
  });

  it('works without any storage, shows only once even after a reload', () => {
    let marked = false;
    const noticeMarker = { get: () => marked, set: () => (marked = true) };
    const store = createStore({ backend: null, sessionBackend: null, env, noticeMarker });
    expect(store.canSave).toBe(false);
    expect(store.shouldShowSaveNotice()).toBe(true);
    expect(store.shouldShowSaveNotice()).toBe(false);
    const reloaded = createStore({ backend: null, sessionBackend: null, env, noticeMarker });
    expect(reloaded.shouldShowSaveNotice()).toBe(false);
  });

  it('uses the fallback marker when sessionStorage throws', () => {
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
