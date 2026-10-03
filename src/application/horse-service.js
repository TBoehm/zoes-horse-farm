// Horse use cases: name question (rule 43), renaming, appearance, display name.
// Name rules live in domain/horse; this service validates through them and persists via the store.
import { COATS, MARKINGS } from '../domain/horse/appearance.js';
import { cleanName, NAME_MAX_LENGTH } from '../domain/horse/horse-name.js';

/** Longest name the rules accept (single constant, defined in the domain). */
export { NAME_MAX_LENGTH };

export const isValidName = (input) => cleanName(input) !== null;

/**
 * True while the first-start name question was neither answered nor skipped (rule 43).
 * An older save that has a valid name but no answered flag counts as answered.
 */
export function needsNamePrompt(store) {
  const horse = store.get('horse');
  return !horse.nameAnswered && cleanName(horse.name) === null;
}

/**
 * One rule for the name prompt (`answerName`) and for renaming (`rename`): typing the language
 * default name does not make a custom name (name stays null); any other valid name does. Either
 * way the question is answered. An invalid name is not saved, the last valid name stays (rule 43).
 * @param {{ defaultName?: string }} [options] the language default name
 * @returns {boolean} false if the input is invalid (nothing is saved)
 */
function saveName(store, input, { defaultName } = {}) {
  const typed = cleanName(input);
  if (typed === null) return false;
  const name = defaultName !== undefined && typed === defaultName ? null : typed;
  const current = store.get('horse');
  if (name === current.name && current.nameAnswered) return true;
  store.update('horse', (horse) => ({ ...horse, name, nameAnswered: true }));
  return true;
}

/** Saves the answer to the name question (first start). See `saveName`. */
export const answerName = saveName;

/** Renames the horse. See `saveName`. */
export const rename = saveName;

/** "Skip": the language default name stays, the question counts as answered. */
export function skipName(store) {
  store.update('horse', (horse) => ({ ...horse, name: null, nameAnswered: true }));
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
