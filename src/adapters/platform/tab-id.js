// Identity of this browser tab for the crash guard (rule 4): kept in sessionStorage, which survives
// a reload of the same tab, also the one the browser does right after it killed an overloaded tab.
// A second tab has its own id. Without sessionStorage the id is unknown (null).

export const TAB_ID_KEY = 'zoes-horse-farm.tabId';

function newId() {
  try {
    const id = globalThis.crypto?.randomUUID?.();
    if (id) return id;
  } catch {
    // fall through
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function safeSession() {
  try {
    return globalThis.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * The id of this tab; created on the first call of the tab.
 * @param {{ storage?: Storage|null, createId?: () => string }} [opts]
 * @returns {string|null} null when sessionStorage is not usable
 */
export function getTabId({ storage = safeSession(), createId = newId } = {}) {
  if (!storage) return null;
  try {
    const existing = storage.getItem(TAB_ID_KEY);
    if (existing) return existing;
    const id = createId();
    storage.setItem(TAB_ID_KEY, id);
    return id;
  } catch {
    return null;
  }
}
