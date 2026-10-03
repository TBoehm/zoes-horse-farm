// Spielstand im Browser (Regeln 44–47): sofort speichern, robust laden, Unbekanntes erhalten.
import { createEmitter } from './events.js';
import { SAVE_VERSION, getSections, isPlainObject } from './save-schema.js';

export const SAVE_KEY = 'zoes-horse-farm.save';
export const SESSION_NOTICE_KEY = 'zoes-horse-farm.saveNoticeShown';

function safeStorage(getter) {
  try {
    return getter();
  } catch {
    return null;
  }
}

function readRaw(backend, key) {
  if (!backend) return {};
  try {
    const text = backend.getItem(key);
    if (!text) return {};
    const parsed = JSON.parse(text);
    return isPlainObject(parsed) ? parsed : {};
  } catch {
    return {};
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

/** Merker „Hinweis gezeigt" ohne Web Storage: history.state übersteht Neuladen, nicht das Schließen. */
export function historyNoticeMarker(win = globalThis.window) {
  return {
    get: () => Boolean(win?.history?.state?.zhfSaveNotice),
    set: () => {
      try {
        win.history.replaceState({ ...(win.history.state ?? {}), zhfSaveNotice: 1 }, '');
      } catch {
        // kein history (z. B. Tests)
      }
    },
  };
}

/**
 * @param {object} opts
 * @param {Storage|null} [opts.backend] localStorage-artig
 * @param {Storage|null} [opts.sessionBackend] sessionStorage-artig (Hinweis einmal je Sitzung)
 * @param {object} [opts.env] Umgebungswerte für Anfangswerte (z. B. defaultLang)
 */
export function createStore({
  backend = safeStorage(() => globalThis.localStorage),
  sessionBackend = safeStorage(() => globalThis.sessionStorage),
  env = {},
  noticeMarker = historyNoticeMarker(),
} = {}) {
  const emitter = createEmitter();
  const raw = readRaw(backend, SAVE_KEY);
  const data = {};
  let canSave = probe(backend);
  let noticeShownInMemory = false;
  const sectionRefs = new Map();

  for (const [name, section] of getSections()) {
    data[name] = section.sanitize(raw[name], env);
    raw[name] = data[name];
    sectionRefs.set(name, section);
  }

  function write() {
    if (!backend) {
      canSave = false;
      return false;
    }
    const version = Math.max(Number.isFinite(raw.version) ? raw.version : 0, SAVE_VERSION);
    try {
      backend.setItem(SAVE_KEY, JSON.stringify({ ...raw, version }));
      canSave = true;
      return true;
    } catch {
      canSave = false;
      emitter.emit('saveFailed');
      return false;
    }
  }

  function sectionOf(name) {
    const section = getSections().get(name);
    if (!section) throw new Error(`Unbekannter Bereich: ${name}`);
    // Später angemeldete oder erweiterte Bereiche (z. B. neue Einstellungsfelder) neu bereinigen
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
    /** Schreibt den aktuellen Stand (z. B. damit Anfangswerte beim ersten Start feststehen). */
    flush() {
      return write();
    },
    onChange(name, fn) {
      return emitter.on(`change:${name}`, fn);
    },
    onSaveFailed(fn) {
      return emitter.on('saveFailed', fn);
    },
    /** true höchstens einmal je Sitzung (Tab), und nur wenn Speichern nicht möglich ist (Regel 46). */
    shouldShowSaveNotice() {
      if (canSave) return false;
      try {
        if (sessionBackend?.getItem(SESSION_NOTICE_KEY)) return false;
        sessionBackend?.setItem(SESSION_NOTICE_KEY, '1');
        if (sessionBackend) return true;
      } catch {
        // sessionStorage nicht nutzbar → Merker in history.state (übersteht Neuladen)
      }
      if (noticeShownInMemory || noticeMarker?.get()) return false;
      noticeShownInMemory = true;
      noticeMarker?.set();
      return true;
    },
    /** Nur für Tests/Debug. */
    _raw() {
      return structuredClone(raw);
    },
  };
}

/** Fordert dauerhaften Speicher an, wo der Browser es anbietet (Regel 44). */
export async function requestPersistentStorage(nav = globalThis.navigator) {
  try {
    if (!nav?.storage?.persist) return false;
    if (await nav.storage.persisted?.()) return true;
    return await nav.storage.persist();
  } catch {
    return false;
  }
}
