// Registers the texts of all feature areas.
import { registerStrings } from '../i18n.js';
import core from './core.js';
import riding from './riding.js';
import profile from './profile.js';
import badges from './badges.js';
import courses from './courses.js';
import audio from './audio.js';
import help from './help.js';
import debug from './debug.js';

/** Every feature area's texts, as `{ de, en }` dictionaries (also the data the tests check). */
export const STRING_AREAS = [core, riding, profile, badges, courses, audio, help, debug];

export function registerAllStrings() {
  for (const area of STRING_AREAS) registerStrings(area);
}
