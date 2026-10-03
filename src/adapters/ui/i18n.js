// Bilingual texts (rule 6). Feature areas register their texts with registerStrings.
import { LANGS } from '../../application/languages.js';

const dictionaries = { de: {}, en: {} };
const listeners = new Set();
let lang = 'de';

export function registerStrings({ de = {}, en = {} }) {
  Object.assign(dictionaries.de, de);
  Object.assign(dictionaries.en, en);
}

/** German if the preferred browser language is German, otherwise English. */
export function detectLang(languages) {
  const list = Array.isArray(languages) && languages.length ? languages : [languages];
  const first = list.find(Boolean);
  return typeof first === 'string' && first.toLowerCase().startsWith('de') ? 'de' : 'en';
}

export function getLang() {
  return lang;
}

export function setLang(next) {
  if (!LANGS.includes(next) || next === lang) return;
  lang = next;
  if (typeof document !== 'undefined') document.documentElement.lang = next;
  for (const fn of [...listeners]) fn(next);
}

export function onLangChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function t(key, params) {
  const text = dictionaries[lang][key] ?? dictionaries.de[key] ?? dictionaries.en[key];
  if (text === undefined) {
    console.error(`Missing text: ${key}`);
    return '';
  }
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (m, name) => (name in params ? String(params[name]) : m));
}
