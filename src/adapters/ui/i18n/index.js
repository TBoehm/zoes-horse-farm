// Registriert die Texte aller Bereiche.
import { registerStrings } from '../core/i18n.js';
import core from './core.js';
import riding from './riding.js';
import profile from '../profile/profile-strings.js';
import badges from '../progress/strings.js';
import courses from '../course-ui/course-strings.js';
import audio from '../audio/audio-strings.js';

export function registerAllStrings() {
  registerStrings(core);
  registerStrings(riding);
  registerStrings(profile);
  registerStrings(badges);
  registerStrings(courses);
  registerStrings(audio);
}
