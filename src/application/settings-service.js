// Settings use cases on top of the store port: every settings write of the UI goes through here,
// so values are validated in one place (rule 47) and the adapters stay free of rules.
import { clamp } from '../shared/math.js';
import { GRAPHICS_LEVELS } from './graphics-levels.js';
import { LANGS } from './languages.js';
import { CAMERA_MODES } from './settings-schema.js';

const AID_FIELDS = { free: 'aidFree', course: 'aidCourse' };
const SOUND_PREFIX = { music: 'music', sfx: 'sfx' };

/**
 * @param {{ get(section: string): object, update(section: string, fn: Function): object,
 *   onChange(section: string, fn: Function): () => void }} store store port
 */
export function createSettingsService(store) {
  const patch = (changes) => store.update('settings', (settings) => ({ ...settings, ...changes }));

  return {
    /** Saved settings (a copy). */
    get: () => store.get('settings'),

    setLang(lang) {
      if (LANGS.includes(lang)) patch({ lang });
    },

    /**
     * Automatic graphics: `deviceLevel` ('low'|'medium'|'high'|null) is the level for this device.
     */
    setGraphicsAuto(deviceLevel) {
      if (deviceLevel !== null && !GRAPHICS_LEVELS.includes(deviceLevel)) return;
      patch({ graphicsAuto: true, graphicsLevel: deviceLevel });
    },

    /** The player picks a level: automatic graphics are off. */
    setGraphicsLevel(level) {
      if (GRAPHICS_LEVELS.includes(level)) patch({ graphicsAuto: false, graphicsLevel: level });
    },

    /** Frame-rate governor lowers the level: automatic graphics stay on. */
    setAutoLevel(level) {
      if (GRAPHICS_LEVELS.includes(level)) patch({ graphicsLevel: level });
    },

    setCamera(mode) {
      if (CAMERA_MODES.includes(mode)) patch({ camera: mode });
    },

    /** @param {'free'|'course'} kind */
    setAid(kind, on) {
      const key = AID_FIELDS[kind];
      if (key && typeof on === 'boolean') patch({ [key]: on });
    },

    /** Frame-rate display in the ride (rule 4). */
    setShowFps(on) {
      if (typeof on === 'boolean') patch({ showFps: on });
    },

    /** The controls help was closed with "Got it": it no longer shows up by itself (rule 56). */
    markControlsHelpSeen() {
      patch({ controlsHelpSeen: true });
    },

    /** @param {'music'|'sfx'} channel @param {number} value 0..1 */
    setVolume(channel, value) {
      const prefix = SOUND_PREFIX[channel];
      if (prefix && typeof value === 'number' && Number.isFinite(value)) {
        patch({ [`${prefix}Volume`]: clamp(value, 0, 1) });
      }
    },

    setMuted(channel, muted) {
      const prefix = SOUND_PREFIX[channel];
      if (prefix && typeof muted === 'boolean') patch({ [`${prefix}Muted`]: muted });
    },

    /** @returns {() => void} unsubscribe */
    onChange: (fn) => store.onChange('settings', fn),
  };
}
