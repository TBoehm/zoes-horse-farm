// Save data in the browser (rules 44–47): save immediately, load robustly, preserve unknown data.
import { createEmitter } from '../../shared/events.js';
import { isPlainObject } from '../../shared/math.js';
import { SAVE_VERSION, getSections } from '../../application/save-schema.js';

export const SAVE_KEY = 'zoes-horse-farm.save';
const SESSION_NOTICE_KEY = 'zoes-horse-farm.saveNoticeShown';

function safeStorage(getter) {
  try {
    return getter();
  } catch {
    return null;
  }
}

/** The stored save as a plain object, or null when there is none or it cannot be read. */
function readStored(backend, key) {
  if (!backend) return null;
  try {
    const text = backend.getItem(key);
    if (!text) return null;
    const parsed = JSON.parse(text);
    return isPlainObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function probe(backend) {
  if (!backend) return false;
  try {
    const k = `${SAVE_KEY}.probe`;
    backend.setItem(k, '1');
    backend.removeItem(k);
    return true;
  } catch {
    return false;
  }
}

/**
 * "Notice shown" marker without Web Storage: history.state survives a reload, not closing the tab.
 */
function historyNoticeMarker(win = globalThis.window) {
  return {
    get: () => Boolean(win?.history?.state?.zhfSaveNotice),
    set: () => {
      try {
        win.history.replaceState({ ...(win.history.state ?? {}), zhfSaveNotice: 1 }, '');
      } catch {
        // no history (e.g. tests)
      }
    },
  };
}

/**
 * @param {object} opts
 * @param {Storage|null} [opts.backend] localStorage-like
 * @param {Storage|null} [opts.sessionBackend] sessionStorage-like (notice once per session)
 * @param {object} [opts.env] environment values for initial values (e.g. defaultLang)
 */
export function createStore({
  backend = safeStorage(() => globalThis.localStorage),
  sessionBackend = safeStorage(() => globalThis.sessionStorage),
  env = {},
  noticeMarker = historyNoticeMarker(),
} = {}) {
  const emitter = createEmitter();
  const raw = readStored(backend, SAVE_KEY) ?? {};
  const data = {};
  let canSave = probe(backend);
  let noticeShownInMemory = false;
  const sectionRefs = new Map();

  for (const [name, section] of getSections()) {
    data[name] = section.sanitize(raw[name], env);
    raw[name] = data[name];
    sectionRefs.set(name, section);
  }

  /** Writes `content` (a whole save object) as the stored save; false when that fails. */
  function persist(content) {
    if (!backend) {
      canSave = false;
      return false;
    }
    const version = Math.max(Number.isFinite(content.version) ? content.version : 0, SAVE_VERSION);
    try {
      backend.setItem(SAVE_KEY, JSON.stringify({ ...content, version }));
      canSave = true;
      return true;
    } catch {
      canSave = false;
      emitter.emit('saveFailed');
      return false;
    }
  }

  const write = () => persist(raw);

  function sectionOf(name) {
    const section = getSections().get(name);
    if (!section) throw new Error(`Unknown section: ${name}`);
    // Re-sanitize sections registered or extended later (e.g. new settings fields)
    if (!(name in data) || sectionRefs.get(name) !== section) {
      data[name] = section.sanitize(raw[name], env);
      raw[name] = data[name];
      sectionRefs.set(name, section);
    }
    return section;
  }

  return {
    get canSave() {
      return canSave;
    },
    get(name) {
      sectionOf(name);
      return structuredClone(data[name]);
    },
    update(name, fn) {
      const section = sectionOf(name);
      const next = section.sanitize(fn(structuredClone(data[name])), env);
      data[name] = next;
      raw[name] = next;
      write();
      emitter.emit(`change:${name}`, structuredClone(next));
      return structuredClone(next);
    },
    /**
     * Changes ONE section against what is stored right now and writes only that section through.
     * For writers that run without a user action (the crash guard's heartbeat): `update` writes the
     * whole in-memory copy, which would overwrite what another tab saved meanwhile with stale data.
     * Here `fn` gets the stored section (the memory value when the storage has none or cannot be
     * read), the stored JSON is rewritten with only this section replaced (other and unknown
     * sections stay as stored), and only this section changes in memory. The in-memory state of the
     * other sections is never touched (unsaved progress of this tab survives a failed save, a stale
     * other tab is not pulled in) and no change event is emitted for them. A failed write keeps the
     * new value in memory only (rule 46).
     */
    updateThrough(name, fn) {
      const section = sectionOf(name);
      const stored = readStored(backend, SAVE_KEY);
      const base = stored ?? raw;
      const current = stored && name in stored ? section.sanitize(stored[name], env) : data[name];
      const next = section.sanitize(fn(structuredClone(current)), env);
      data[name] = next;
      raw[name] = next;
      persist({ ...base, [name]: next });
      emitter.emit(`change:${name}`, structuredClone(next));
      return structuredClone(next);
    },
    /** Writes the current state (e.g. so initial values are fixed on first start). */
    flush() {
      return write();
    },
    onChange(name, fn) {
      return emitter.on(`change:${name}`, fn);
    },
    onSaveFailed(fn) {
      return emitter.on('saveFailed', fn);
    },
    /** true at most once per session (tab), and only when saving is not possible (rule 46). */
    shouldShowSaveNotice() {
      if (canSave) return false;
      try {
        if (sessionBackend?.getItem(SESSION_NOTICE_KEY)) return false;
        sessionBackend?.setItem(SESSION_NOTICE_KEY, '1');
        if (sessionBackend) return true;
      } catch {
        // sessionStorage unusable → marker in history.state (survives a reload)
      }
      if (noticeShownInMemory || noticeMarker?.get()) return false;
      noticeShownInMemory = true;
      noticeMarker?.set();
      return true;
    },
  };
}

/** Requests persistent storage where the browser offers it (rule 44). */
export async function requestPersistentStorage(nav = globalThis.navigator) {
  try {
    if (!nav?.storage?.persist) return false;
    if (await nav.storage.persisted?.()) return true;
    return await nav.storage.persist();
  } catch {
    return false;
  }
}
