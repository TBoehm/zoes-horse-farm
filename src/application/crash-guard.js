// Unclean-exit guard (rule 4): detects that the game ended unexpectedly while the 3D picture was
// being drawn (the browser closed or reloaded the tab because of overload). The handling of a lost
// WebGL context never runs in that case because the whole page is gone, so the next start has to
// treat the leftover "rendering" mark like a context loss in the foreground.
//
// The guard keeps a small section in the save: it flips to "rendering" when a screen starts
// drawing the scene and back to "clean" when it stops, when the page goes to the background or
// closes (pagehide). A page that was hidden and killed later therefore never counts. Writes happen
// on transitions, on a level change, and as a slow heartbeat (so the debug display can tell how
// long the crashed session lasted).
//
// Two more cases keep the guard from crying wolf: after the page came back to the foreground the
// mark is only set again after a grace time (Android often kills or reloads a tab right after an
// app switch), and a leftover mark whose heartbeat is still fresh at the start belongs to a tab
// that is alive (a second tab), not to a crash. The store writes its whole in-memory copy, so
// every write of the guard first takes over what other tabs saved (`store.reload()`).
import { field, registerSection } from './save-schema.js';
import { FOREGROUND_GRACE_S, GRAPHICS_LEVELS } from './graphics-levels.js';
import { isPlainObject } from '../shared/math.js';

export const CRASH_GUARD_SECTION = 'crashGuard';

// Technical value (no game play): how often the heartbeat refreshes the "last seen" time.
export const HEARTBEAT_INTERVAL_MS = 5000;

// A leftover mark younger than this many heartbeat intervals at the start is a live tab.
const LIVE_HEARTBEATS = 2;

const MAX_TIME = Number.MAX_SAFE_INTEGER;

/** The remembered crash is shown by the debug display: all fields or none. */
const isLastCrash = (v) =>
  isPlainObject(v) &&
  GRAPHICS_LEVELS.includes(v.level) &&
  typeof v.auto === 'boolean' &&
  typeof v.seconds === 'number' &&
  Number.isFinite(v.seconds) &&
  v.seconds >= 0 &&
  typeof v.at === 'string';

/**
 * Adds a level to a set of blocked levels (levels that crashed or lost the 3D picture on this
 * device): unique, ordered low to high. An invalid level is ignored. Returns a new array.
 */
export function addBlockedLevel(blocked, level) {
  const set = new Set(blocked);
  if (GRAPHICS_LEVELS.includes(level)) set.add(level);
  return GRAPHICS_LEVELS.filter((l) => set.has(l));
}

const isBlockedLevels = (v) =>
  Array.isArray(v) && v.every((l) => GRAPHICS_LEVELS.includes(l)) && new Set(v).size === v.length;

const fields = {
  rendering: field.bool(false),
  level: field.enum([...GRAPHICS_LEVELS, null], null),
  auto: field.bool(true),
  since: field.number(0, MAX_TIME, 0),
  lastSeen: field.number(0, MAX_TIME, 0),
  // a manual level above low crashed: tell the player once at the next ride start
  hintPending: field.bool(false),
  // levels that crashed or lost the 3D picture on this device; the automatic must not climb to
  // them again until the player selects "Automatic" anew
  blockedLevels: { fallback: [], check: isBlockedLevels },
  lastCrash: { fallback: null, check: (v) => v === null || isLastCrash(v) },
};

registerSection(CRASH_GUARD_SECTION, {
  defaults() {
    return Object.fromEntries(
      Object.entries(fields).map(([k, spec]) => [k, structuredClone(spec.fallback)]),
    );
  },
  sanitize(raw) {
    const out = isPlainObject(raw) ? { ...raw } : {};
    for (const [k, spec] of Object.entries(fields)) {
      if (!spec.check(out[k])) out[k] = structuredClone(spec.fallback);
    }
    return out;
  },
});

/**
 * @param {object} deps
 * @param {{ get(section: string): object, update(section: string, fn: Function): object,
 *   reload(): void }} deps.store `reload` takes over what other tabs saved (see local-store.js)
 * @param {{ setAutoLevel(level: string): void }} deps.settings settings service
 * @param {{ nowMs(): number, nowIso(): string }} deps.clock
 * @param {(input: { auto: boolean, level: string }) =>
 *   { level: string, persist: boolean, hint: boolean }} deps.decide what a loss of the 3D
 *   picture in the foreground means for the level (the same rule as for a lost WebGL context)
 * @param {number} [deps.foregroundGraceMs] time after the page came back before it is marked again
 */
export function createCrashGuard({
  store,
  settings,
  clock,
  decide,
  foregroundGraceMs = FOREGROUND_GRACE_S * 1000,
}) {
  const leases = new Set();
  let background = false;
  let returnedAtMs = -Infinity; // when the page last came back to the foreground
  let latest = null; // { level, auto } of the most recent screen update
  let mirror = null; // what is persisted right now: { rendering, level, auto }
  let lastBeatMs = 0;

  const read = () => store.get(CRASH_GUARD_SECTION);

  /** Changes the guard section only: other tabs' progress and settings are taken over first. */
  function update(fn) {
    store.reload();
    return store.update(CRASH_GUARD_SECTION, fn);
  }

  function write(changes) {
    update((s) => ({ ...s, ...changes }));
    if (mirror) {
      for (const key of ['rendering', 'level', 'auto']) {
        if (key in changes) mirror[key] = changes[key];
      }
    }
  }

  const persisted = () => {
    if (!mirror) {
      const { rendering, level, auto } = read();
      mirror = { rendering, level, auto };
    }
    return mirror;
  };

  const wantRendering = () =>
    leases.size > 0 && !background && clock.nowMs() - returnedAtMs >= foregroundGraceMs;

  /** Brings the persisted mark in line with what the screens do right now. */
  function sync() {
    const now = clock.nowMs();
    const saved = persisted();
    if (!wantRendering()) {
      if (saved.rendering) write({ rendering: false });
      return;
    }
    if (!saved.rendering) {
      lastBeatMs = now;
      write({ rendering: true, level: latest.level, auto: latest.auto, since: now, lastSeen: now });
    } else if (saved.level !== latest.level || saved.auto !== latest.auto) {
      write({ level: latest.level, auto: latest.auto });
    } else if (now - lastBeatMs >= HEARTBEAT_INTERVAL_MS) {
      lastBeatMs = now;
      // also restates the mark: another tab may have cleared it while this one still draws
      write({ rendering: true, level: latest.level, auto: latest.auto, lastSeen: now });
    }
  }

  return {
    /**
     * A screen starts drawing the 3D scene (ride, free mode, pre-start, horse preview). Keep the
     * lease: call lease.frame(info) every frame (cheap, writes only when needed) and
     * lease.release() when the screen stops drawing. Screens may overlap (a rebuilt screen is
     * created before the old one is destroyed): the mark stays until every lease is released.
     * @param {{ level: string, auto: boolean }} info current graphics level and automatic flag
     */
    markRendering(info) {
      latest = { level: info.level, auto: info.auto };
      const lease = {
        frame(next) {
          if (!leases.has(lease)) return;
          latest = { level: next.level, auto: next.auto };
          sync();
        },
        release() {
          if (!leases.delete(lease)) return;
          sync();
        },
      };
      leases.add(lease);
      sync();
      return lease;
    },

    /** The page goes to the background or closes: whatever happens next is not a crash. */
    markBackground() {
      background = true;
      sync();
    },

    /**
     * The page is visible again: a screen that is still drawing is marked again, but only after
     * the grace time (the next frame of a lease notices it): a tab that the system kills or
     * reloads right after an app switch is no crash of the game.
     */
    resume() {
      if (background) returnedAtMs = clock.nowMs();
      background = false;
      sync();
    },

    /**
     * Start of the app, before the first ride: looks at the mark of the previous run. A leftover
     * "rendering" mark is a crash; the level rule is applied (automatic: low is saved; manual
     * above low: a hint is flagged for the next ride start). A mark whose heartbeat is younger
     * than two intervals belongs to a tab that is still drawing (a second tab): no crash, and the
     * mark stays untouched.
     * @returns {{ crashed: false } | { crashed: true, level: string|null, auto: boolean,
     *   seconds: number }}
     */
    checkPreviousRun() {
      const saved = read();
      mirror = null;
      if (!saved.rendering) return { crashed: false };
      const sinceLastSeenMs = clock.nowMs() - saved.lastSeen;
      if (sinceLastSeenMs >= 0 && sinceLastSeenMs < LIVE_HEARTBEATS * HEARTBEAT_INTERVAL_MS) {
        return { crashed: false };
      }
      const seconds = Math.max(0, Math.round((saved.lastSeen - saved.since) / 1000));
      const result = { crashed: true, level: saved.level, auto: saved.auto, seconds };
      let hint = false;
      if (saved.level) {
        const decision = decide({ auto: saved.auto, level: saved.level });
        if (decision.persist) settings.setAutoLevel(decision.level);
        hint = decision.hint;
      }
      update((s) => ({
        ...s,
        rendering: false,
        hintPending: s.hintPending || hint,
        blockedLevels: addBlockedLevel(s.blockedLevels, saved.level),
        lastCrash: saved.level
          ? { level: saved.level, auto: saved.auto, seconds, at: clock.nowIso() }
          : s.lastCrash,
      }));
      return result;
    },

    /** true once after a crash that calls for the "pick a lower level" hint. */
    takeHint() {
      if (!read().hintPending) return false;
      update((s) => ({ ...s, hintPending: false }));
      return true;
    },

    /** Levels that crashed or lost the 3D picture on this device (low to high). */
    blockedLevels: () => read().blockedLevels,

    /** Remembers a level that lost the 3D picture (e.g. after a regular WebGL context loss). */
    blockLevel(level) {
      update((s) => ({
        ...s,
        blockedLevels: addBlockedLevel(s.blockedLevels, level),
      }));
    },

    /** The player selected "Automatic" anew: every level may be tried again. */
    clearBlockedLevels() {
      update((s) => ({ ...s, blockedLevels: [] }));
    },

    /** The last detected crash for the debug display, or null. */
    lastCrash: () => read().lastCrash,
  };
}
