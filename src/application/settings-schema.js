// Save-game sections and settings fields for riding, jumping, the horse, progress and sound
// (SRT-002 to SRT-006). Importing this module registers them with the save schema.
import { addSettingsFields, field, objectSection, registerSection } from './save-schema.js';
import { AUTO_START_LEVEL, GRAPHICS_LEVELS } from './graphics-levels.js';
import { COATS, DEFAULT_APPEARANCE, MARKINGS } from '../domain/horse/appearance.js';
import { cleanName } from '../domain/horse/horse-name.js';
import { PROGRESS_DEFAULTS, sanitizeProgress } from '../domain/progress/progress.js';

/** Camera modes the player can choose. */
export const CAMERA_MODES = Object.freeze(['follow', 'rider']);

addSettingsFields({
  graphicsAuto: field.bool(true),
  // first start: the automatic begins at the lowest level and works its way up (rule 4); the level
  // it reached is saved and applies at the next start
  graphicsLevel: field.enum(GRAPHICS_LEVELS, AUTO_START_LEVEL),
  camera: field.enum(CAMERA_MODES, 'follow'),
  aidFree: field.bool(true),
  aidCourse: field.bool(false),
  // fps counter in the ride (rule 4): off by default
  showFps: field.bool(false),
  // the controls help was closed with "Got it" at least once (rule 56)
  controlsHelpSeen: field.bool(false),
});

addSettingsFields({
  musicVolume: field.number(0, 1, 0.5),
  musicMuted: field.bool(false),
  sfxVolume: field.number(0, 1, 0.5),
  sfxMuted: field.bool(false),
});

registerSection(
  'horse',
  objectSection({
    coat: field.enum(COATS, DEFAULT_APPEARANCE.coat),
    marking: field.enum(MARKINGS, DEFAULT_APPEARANCE.marking),
    // null = no custom name (language default name, rule 43)
    name: { fallback: null, check: (v) => v === null || cleanName(v) === v },
    nameAnswered: field.bool(false),
  }),
);

registerSection('progress', {
  defaults: () => structuredClone(PROGRESS_DEFAULTS),
  sanitize: (raw) => sanitizeProgress(raw),
});
