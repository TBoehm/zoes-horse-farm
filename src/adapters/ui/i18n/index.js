// Registers the texts of all feature areas.
import { registerStrings } from '../i18n.js';
import core from './core.js';
import riding from './riding.js';
import profile from './profile.js';
import badges from './badges.js';
import courses from './courses.js';
import audio from './audio.js';

export function registerAllStrings() {
  registerStrings(core);
  registerStrings(riding);
  registerStrings(profile);
  registerStrings(badges);
  registerStrings(courses);
  registerStrings(audio);
}
