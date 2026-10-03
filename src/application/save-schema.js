// Bereiche des Spielstands mit Feld-für-Feld-Bereinigung (Regel 47).
// Ungültige oder fehlende Felder → Anfangswert, lesbare Felder bleiben, unbekannte bleiben erhalten.

export const SAVE_VERSION = 1;

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

export const field = {
  enum: (values, fallback) => ({
    fallback,
    check: (v) => values.includes(v),
  }),
  bool: (fallback) => ({ fallback, check: (v) => typeof v === 'boolean' }),
  number: (min, max, fallback) => ({
    fallback,
    check: (v) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max,
  }),
};

/** Baut einen Sanitizer aus Feld-Spezifikationen. fallback darf eine Funktion (env) sein. */
export function objectSection(fields) {
  const resolve = (spec, env) =>
    typeof spec.fallback === 'function' ? spec.fallback(env) : spec.fallback;
  return {
    defaults(env) {
      const out = {};
      for (const [k, spec] of Object.entries(fields)) out[k] = resolve(spec, env);
      return out;
    },
    sanitize(raw, env) {
      const out = isPlainObject(raw) ? { ...raw } : {};
      for (const [k, spec] of Object.entries(fields)) {
        if (!spec.check(out[k])) out[k] = resolve(spec, env);
      }
      return out;
    },
  };
}

const sections = new Map();

export function registerSection(name, section) {
  sections.set(name, section);
}

export function getSections() {
  return sections;
}

export function addSettingsFields(fields) {
  settingsFields = { ...settingsFields, ...fields };
  registerSection('settings', objectSection(settingsFields));
}

let settingsFields = {
  lang: field.enum(['de', 'en'], (env) => env.defaultLang ?? 'en'),
};
registerSection('settings', objectSection(settingsFields));

export { isPlainObject };
