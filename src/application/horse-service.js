// Horse use cases: name question (rule 43), renaming, appearance, display name.
// Name rules live in domain/horse; this service validates through them and persists via the store.
import { COATS, MARKINGS } from '../domain/horse/appearance.js';
import { cleanName, NAME_MAX } from '../domain/horse/horse-name.js';

/** Longest name the rules accept. */
export const NAME_MAX_LENGTH = NAME_MAX;

export const isValidName = (input) => cleanName(input) !== null;

/** True while the first-start name question was neither answered nor skipped (rule 43). */
export function needsNamePrompt(store) {
  return !store.get('horse').nameAnswered;
}

/** Saves the answer to the name question. @returns {boolean} false if the name is invalid */
export function answerName(store, input) {
  const name = cleanName(input);
  if (name === null) return false;
  store.update('horse', (horse) => ({ ...horse, name, nameAnswered: true }));
  return true;
}

/** "Skip": the language default name stays, the question counts as answered. */
export function skipName(store) {
  store.update('horse', (horse) => ({ ...horse, name: null, nameAnswered: true }));
}

/**
 * Renames the horse. An invalid name is not saved, the last valid name stays (rule 43).
 * Typing the language default name does not turn it into a custom name.
 * @param {{ defaultName?: string }} [options]
 * @returns {boolean} false if the input is invalid
 */
export function rename(store, input, { defaultName } = {}) {
  const name = cleanName(input);
  if (name === null) return false;
  const current = store.get('horse');
  const isDefault = current.name === null && name === defaultName;
  if (isDefault || name === current.name) return true;
  store.update('horse', (horse) => ({ ...horse, name, nameAnswered: true }));
  return true;
}

/** The selectable coats and markings. */
export function appearanceOptions() {
  return { coats: [...COATS], markings: [...MARKINGS] };
}

/**
 * Saves coat and/or marking (values outside the choices are ignored).
 * @returns {{ coat: string, marking: string }} the saved appearance
 */
export function setAppearance(store, { coat, marking } = {}) {
  const next = store.update('horse', (horse) => ({
    ...horse,
    coat: COATS.includes(coat) ? coat : horse.coat,
    marking: MARKINGS.includes(marking) ? marking : horse.marking,
  }));
  return { coat: next.coat, marking: next.marking };
}

/** Own name, else the language default name. */
export function displayName(horse, defaultName) {
  return horse?.name ?? defaultName;
}
