// Test doubles for the application ports: store, clock and rng. Used by the application tests only.
import { PROGRESS_DEFAULTS } from '../../src/domain/progress/progress.js';
import { createRng } from '../../src/domain/sim/rng.js';

/** In-memory store implementing the store port { get, update, reload, onChange }. */
export function fakeStore({ settings = {}, horse = {}, progress = {} } = {}) {
  const data = {
    settings: { aidFree: true, aidCourse: false, camera: 'follow', ...settings },
    horse: { name: null, nameAnswered: false, coat: 'bay', marking: 'star', ...horse },
    progress: { ...structuredClone(PROGRESS_DEFAULTS), ...progress },
  };
  const listeners = new Map();
  return {
    data,
    get: (section) => structuredClone(data[section]),
    update(section, fn) {
      data[section] = fn(structuredClone(data[section]));
      for (const listener of listeners.get(section) ?? []) listener(structuredClone(data[section]));
      return structuredClone(data[section]);
    },
    /** Nothing to take over: a test simulates another tab by changing `data` before the call. */
    reload() {},
    onChange(section, fn) {
      if (!listeners.has(section)) listeners.set(section, new Set());
      listeners.get(section).add(fn);
      return () => listeners.get(section).delete(fn);
    },
  };
}

export const fixedClock = (iso = '2026-01-02T03:04:05.000Z') => ({ nowIso: () => iso });

export const seededRng = (seed = 1) => createRng(seed);
