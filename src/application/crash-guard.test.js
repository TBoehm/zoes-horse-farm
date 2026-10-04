import { describe, expect, it } from 'vitest';
import './settings-schema.js';
import {
  CRASH_GUARD_SECTION,
  HEARTBEAT_INTERVAL_MS,
  addBlockedLevel,
  createCrashGuard,
} from './crash-guard.js';
import { getSections } from './save-schema.js';
import { createSettingsService } from './settings-service.js';
import { fakeStore } from '../../tests/support/test-ports.js';

// Same rule as the 3D layer's levelAfterContextLoss for a loss in the foreground
const decide = ({ auto, level }) =>
  auto
    ? { level: 'low', persist: level !== 'low', hint: false }
    : { level, persist: false, hint: level !== 'low' };

function setup({ guard: saved = {}, settings = {} } = {}) {
  const store = fakeStore({
    settings: { graphicsAuto: true, graphicsLevel: 'medium', ...settings },
  });
  const defaults = getSections().get(CRASH_GUARD_SECTION).defaults({});
  store.data[CRASH_GUARD_SECTION] = { ...defaults, ...saved };
  let nowMs = 1_000_000;
  const clock = { nowMs: () => nowMs, nowIso: () => new Date(nowMs).toISOString() };
  let writes = 0;
  const update = store.update;
  store.update = (section, fn) => {
    if (section === CRASH_GUARD_SECTION) writes += 1;
    return update(section, fn);
  };
  const guard = createCrashGuard({ store, settings: createSettingsService(store), clock, decide });
  return {
    store,
    guard,
    state: () => store.data[CRASH_GUARD_SECTION],
    writes: () => writes,
    advance: (ms) => {
      nowMs += ms;
    },
  };
}

const MEDIUM_AUTO = { level: 'medium', auto: true };

describe('crash guard: marking', () => {
  it('marks rendering with level, auto flag and start time', () => {
    const { guard, state } = setup();
    guard.markRendering(MEDIUM_AUTO);
    expect(state()).toMatchObject({
      rendering: true,
      level: 'medium',
      auto: true,
      since: 1_000_000,
      lastSeen: 1_000_000,
    });
  });

  it('marks idle when the lease is released', () => {
    const { guard, state } = setup();
    const lease = guard.markRendering(MEDIUM_AUTO);
    lease.release();
    expect(state().rendering).toBe(false);
  });

  it('markIdle releases every lease', () => {
    const { guard, state } = setup();
    guard.markRendering(MEDIUM_AUTO);
    guard.markRendering(MEDIUM_AUTO);
    guard.markIdle();
    expect(state().rendering).toBe(false);
  });

  it('stays marked while another lease is held (a screen is rebuilt: new one first)', () => {
    const { guard, state } = setup();
    const first = guard.markRendering(MEDIUM_AUTO);
    const second = guard.markRendering(MEDIUM_AUTO);
    first.release();
    expect(state().rendering).toBe(true);
    second.release();
    expect(state().rendering).toBe(false);
  });

  it('releasing a lease twice does nothing', () => {
    const { guard, state } = setup();
    const first = guard.markRendering(MEDIUM_AUTO);
    const second = guard.markRendering(MEDIUM_AUTO);
    first.release();
    first.release();
    expect(state().rendering).toBe(true);
    second.release();
  });

  it('does not write again when nothing changed', () => {
    const { guard, writes } = setup();
    const lease = guard.markRendering(MEDIUM_AUTO);
    const before = writes();
    for (let i = 0; i < 100; i += 1) lease.frame(MEDIUM_AUTO);
    expect(writes()).toBe(before);
  });

  it('writes a level change right away but keeps the start time', () => {
    const { guard, state, advance } = setup();
    const lease = guard.markRendering({ level: 'high', auto: true });
    advance(2000);
    lease.frame({ level: 'medium', auto: true });
    expect(state()).toMatchObject({ level: 'medium', auto: true, since: 1_000_000 });
  });

  it('updates the heartbeat at most once per interval', () => {
    const { guard, state, writes, advance } = setup();
    const lease = guard.markRendering(MEDIUM_AUTO);
    const before = writes();
    advance(HEARTBEAT_INTERVAL_MS - 1);
    lease.frame(MEDIUM_AUTO);
    expect(writes()).toBe(before);
    advance(1);
    lease.frame(MEDIUM_AUTO);
    expect(writes()).toBe(before + 1);
    expect(state().lastSeen).toBe(1_000_000 + HEARTBEAT_INTERVAL_MS);
  });

  it('a frame after the lease was released does not mark anything', () => {
    const { guard, state } = setup();
    const lease = guard.markRendering(MEDIUM_AUTO);
    lease.release();
    lease.frame(MEDIUM_AUTO);
    expect(state().rendering).toBe(false);
  });
});

describe('crash guard: background', () => {
  it('goes clean in the background and marks again when the page is back', () => {
    const { guard, state, advance } = setup();
    guard.markRendering(MEDIUM_AUTO);
    guard.markBackground();
    expect(state().rendering).toBe(false);
    advance(60_000);
    guard.resume();
    expect(state()).toMatchObject({ rendering: true, level: 'medium', since: 1_060_000 });
  });

  it('resume without a rendering screen stays clean', () => {
    const { guard, state } = setup();
    guard.markBackground();
    guard.resume();
    expect(state().rendering).toBe(false);
  });

  it('a frame in the background does not mark rendering', () => {
    const { guard, state } = setup();
    const lease = guard.markRendering(MEDIUM_AUTO);
    guard.markBackground();
    lease.frame(MEDIUM_AUTO);
    expect(state().rendering).toBe(false);
  });

  it('a screen opened in the background is marked when the page is back', () => {
    const { guard, state } = setup();
    guard.markBackground();
    guard.markRendering(MEDIUM_AUTO);
    expect(state().rendering).toBe(false);
    guard.resume();
    expect(state().rendering).toBe(true);
  });
});

describe('crash guard: check of the previous run', () => {
  const crashed = (extra = {}) => ({
    rendering: true,
    level: 'medium',
    auto: true,
    since: 100_000,
    lastSeen: 107_400,
    ...extra,
  });

  it('a clean state is no crash and changes nothing', () => {
    const { guard, store, writes } = setup();
    expect(guard.checkPreviousRun()).toEqual({ crashed: false });
    expect(store.data.settings.graphicsLevel).toBe('medium');
    expect(writes()).toBe(0);
  });

  it('reports level, mode and seconds into the session of a crash', () => {
    const { guard } = setup({ guard: crashed() });
    expect(guard.checkPreviousRun()).toEqual({
      crashed: true,
      level: 'medium',
      auto: true,
      seconds: 7,
    });
  });

  it('automatic: saves low as the auto level and keeps automatic on', () => {
    const { guard, store } = setup({ guard: crashed({ level: 'high' }) });
    guard.checkPreviousRun();
    expect(store.data.settings).toMatchObject({ graphicsAuto: true, graphicsLevel: 'low' });
  });

  it('automatic at low: nothing to save', () => {
    const { guard, store } = setup({
      guard: crashed({ level: 'low' }),
      settings: { graphicsLevel: 'low' },
    });
    guard.checkPreviousRun();
    expect(store.data.settings.graphicsLevel).toBe('low');
    expect(store.data.crashGuard.hintPending).toBe(false);
  });

  it('manual above low: keeps the level and flags a hint for the next ride', () => {
    const { guard, store } = setup({
      guard: crashed({ auto: false, level: 'high' }),
      settings: { graphicsAuto: false, graphicsLevel: 'high' },
    });
    guard.checkPreviousRun();
    expect(store.data.settings).toMatchObject({ graphicsAuto: false, graphicsLevel: 'high' });
    expect(store.data.crashGuard.hintPending).toBe(true);
  });

  it('manual low: nothing happens', () => {
    const { guard, store } = setup({
      guard: crashed({ auto: false, level: 'low' }),
      settings: { graphicsAuto: false, graphicsLevel: 'low' },
    });
    expect(guard.checkPreviousRun().crashed).toBe(true);
    expect(store.data.settings.graphicsLevel).toBe('low');
    expect(store.data.crashGuard.hintPending).toBe(false);
  });

  it('clears the rendering mark and remembers the crash for the debug display', () => {
    const { guard, state } = setup({ guard: crashed() });
    guard.checkPreviousRun();
    expect(state().rendering).toBe(false);
    expect(state().lastCrash).toEqual({
      level: 'medium',
      auto: true,
      seconds: 7,
      at: new Date(1_000_000).toISOString(),
    });
    // a second check (e.g. after the next clean exit) finds nothing
    expect(guard.checkPreviousRun()).toEqual({ crashed: false });
  });

  it('a clean run keeps the last crash of the debug display', () => {
    const lastCrash = { level: 'high', auto: true, seconds: 5, at: '2026-01-01T00:00:00.000Z' };
    const { guard, state } = setup({ guard: { lastCrash } });
    guard.checkPreviousRun();
    expect(state().lastCrash).toEqual(lastCrash);
  });

  it('a missing heartbeat gives zero seconds', () => {
    const { guard } = setup({ guard: crashed({ lastSeen: 0 }) });
    expect(guard.checkPreviousRun().seconds).toBe(0);
  });
});

describe('crash guard: hint', () => {
  it('takeHint returns the flag once', () => {
    const { guard } = setup({ guard: { hintPending: true } });
    expect(guard.takeHint()).toBe(true);
    expect(guard.takeHint()).toBe(false);
  });

  it('takeHint is false without a flag', () => {
    expect(setup().guard.takeHint()).toBe(false);
  });

  it('lastCrash returns the remembered crash for the debug display', () => {
    const lastCrash = { level: 'high', auto: true, seconds: 5, at: '2026-01-01T00:00:00.000Z' };
    expect(setup({ guard: { lastCrash } }).guard.lastCrash()).toEqual(lastCrash);
    expect(setup().guard.lastCrash()).toBeNull();
  });
});

describe('crash guard section', () => {
  const section = () => getSections().get(CRASH_GUARD_SECTION);

  it('an old save without the section means: no crash', () => {
    const data = section().sanitize(undefined, {});
    expect(data).toMatchObject({ rendering: false, hintPending: false, lastCrash: null });
  });

  it('sanitizes invalid fields field by field', () => {
    const data = section().sanitize(
      { rendering: 'yes', level: 'ultra', auto: 1, since: -5, lastSeen: 'x', lastCrash: 7 },
      {},
    );
    expect(data).toMatchObject({
      rendering: false,
      level: null,
      auto: true,
      since: 0,
      lastSeen: 0,
      lastCrash: null,
    });
  });

  it('drops a malformed last crash but keeps a valid one', () => {
    const valid = { level: 'low', auto: false, seconds: 3, at: '2026-01-01T00:00:00.000Z' };
    expect(section().sanitize({ lastCrash: valid }, {}).lastCrash).toEqual(valid);
    expect(section().sanitize({ lastCrash: { ...valid, level: 'x' } }, {}).lastCrash).toBeNull();
    expect(section().sanitize({ lastCrash: { ...valid, seconds: -1 } }, {}).lastCrash).toBeNull();
  });
});

describe('blocked levels (per device)', () => {
  const crashed = (level) => ({
    rendering: true,
    level,
    auto: true,
    since: 100_000,
    lastSeen: 107_400,
  });

  it('addBlockedLevel adds a level once, ordered low to high', () => {
    expect(addBlockedLevel([], 'medium')).toEqual(['medium']);
    expect(addBlockedLevel(['high'], 'low')).toEqual(['low', 'high']);
    expect(addBlockedLevel(['medium'], 'medium')).toEqual(['medium']);
  });

  it('addBlockedLevel ignores an invalid level and does not change its input', () => {
    const blocked = ['low'];
    expect(addBlockedLevel(blocked, 'ultra')).toEqual(['low']);
    expect(addBlockedLevel(blocked, null)).toEqual(['low']);
    addBlockedLevel(blocked, 'high');
    expect(blocked).toEqual(['low']);
  });

  it('a detected crash blocks the crashed level, automatic or manual', () => {
    const auto = setup({ guard: crashed('medium') });
    auto.guard.checkPreviousRun();
    expect(auto.guard.blockedLevels()).toEqual(['medium']);
    const manual = setup({ guard: { ...crashed('high'), auto: false, blockedLevels: ['medium'] } });
    manual.guard.checkPreviousRun();
    expect(manual.guard.blockedLevels()).toEqual(['medium', 'high']);
  });

  it('a clean start blocks nothing', () => {
    const { guard } = setup();
    guard.checkPreviousRun();
    expect(guard.blockedLevels()).toEqual([]);
  });

  it('blockLevel adds the level of a regular context loss', () => {
    const { guard, state } = setup();
    guard.blockLevel('medium');
    guard.blockLevel('medium');
    expect(state().blockedLevels).toEqual(['medium']);
  });

  it('clearBlockedLevels empties the set (the player picked "Automatic" again)', () => {
    const { guard } = setup({ guard: { blockedLevels: ['medium', 'high'] } });
    guard.clearBlockedLevels();
    expect(guard.blockedLevels()).toEqual([]);
  });

  it('the section sanitizes the set: valid levels only, no duplicates', () => {
    const section = getSections().get(CRASH_GUARD_SECTION);
    expect(section.sanitize({}, {}).blockedLevels).toEqual([]);
    expect(section.sanitize({ blockedLevels: ['high', 'low'] }, {}).blockedLevels).toEqual([
      'high',
      'low',
    ]);
    expect(section.sanitize({ blockedLevels: ['low', 'low'] }, {}).blockedLevels).toEqual([]);
    expect(section.sanitize({ blockedLevels: ['x'] }, {}).blockedLevels).toEqual([]);
    expect(section.sanitize({ blockedLevels: 'low' }, {}).blockedLevels).toEqual([]);
  });
});
