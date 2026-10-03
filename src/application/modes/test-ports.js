// Test doubles for the application ports (store, clock) and the host api given to modes.
import { PROGRESS_DEFAULTS } from '../../domain/progress/progress.js';

export function fakeStore(settings = {}) {
  const data = {
    settings: { aidFree: true, aidCourse: false, ...settings },
    progress: structuredClone(PROGRESS_DEFAULTS),
  };
  return {
    data,
    get: (section) => data[section],
    update(section, fn) {
      data[section] = fn(data[section]);
    },
  };
}

export const fixedClock = (iso = '2026-01-02T03:04:05.000Z') => ({ nowIso: () => iso });

export function fakeApi(horse = { x: 0, z: 0 }) {
  const calls = { feedback: [], rebuildIn: [], rebuildNow: [], finish: [] };
  return {
    calls,
    sim: { horse, approach: null },
    world: { highlight() {}, setFinishMarked() {} },
    feedback: (key) => calls.feedback.push(key),
    rebuildIn: (id, s) => calls.rebuildIn.push([id, s]),
    rebuildNow: (id) => calls.rebuildNow.push(id),
    finish: (arg) => calls.finish.push(arg),
  };
}
