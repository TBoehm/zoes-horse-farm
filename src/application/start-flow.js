// Start sequence (rules 43, 56): which screens come before the main menu. Every step that is done
// removes itself from the sequence, so each screen just asks for "the next one" when it is closed.
import { needsNamePrompt } from './horse-service.js';

/**
 * Screens still to show on this start, in order; the main menu is always the last one.
 * @param {{ get(section: string): object }} store store port
 * @returns {string[]}
 */
export function startSequence(store) {
  const screens = [];
  if (needsNamePrompt(store)) screens.push('namePrompt');
  if (!store.get('settings').controlsHelpSeen) screens.push('controlsHelp');
  screens.push('menu');
  return screens;
}

/** The screen to show now: the first step of the sequence that is still open. */
export const nextStartScreen = (store) => startSequence(store)[0];
