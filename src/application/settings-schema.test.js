import { describe, expect, it } from 'vitest';
import './settings-schema.js';
import { getSections } from './save-schema.js';
import { AUTO_START_LEVEL, GRAPHICS_LEVELS } from './graphics-levels.js';
import { resetProgress } from './progress-service.js';
import { fakeStore } from '../../tests/support/test-ports.js';

const settings = getSections().get('settings');
const SOUND_FIELDS = ['musicVolume', 'musicMuted', 'sfxVolume', 'sfxMuted'];

describe('graphics settings fields (rule 4)', () => {
  it('first start: automatic, at the lowest level (the automatic works its way up)', () => {
    expect(settings.defaults({})).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: AUTO_START_LEVEL,
    });
    expect(AUTO_START_LEVEL).toBe('low');
  });

  it('a saved automatic level is kept for the next start', () => {
    for (const level of GRAPHICS_LEVELS) {
      expect(settings.sanitize({ graphicsAuto: true, graphicsLevel: level }, {})).toMatchObject({
        graphicsAuto: true,
        graphicsLevel: level,
      });
    }
  });

  it('a missing or invalid level falls back to the start level', () => {
    for (const graphicsLevel of [undefined, null, 'ultra', 3]) {
      expect(settings.sanitize({ graphicsLevel }, {}).graphicsLevel).toBe(AUTO_START_LEVEL);
    }
  });
});

describe('sound settings fields', () => {
  it('default to half volume, not muted', () => {
    const defaults = settings.defaults({});
    expect(defaults).toMatchObject({
      musicVolume: 0.5,
      musicMuted: false,
      sfxVolume: 0.5,
      sfxMuted: false,
    });
  });

  it('fill in the defaults for an old save without them', () => {
    const clean = settings.sanitize({ lang: 'de' }, {});
    expect(clean).toMatchObject({
      musicVolume: 0.5,
      musicMuted: false,
      sfxVolume: 0.5,
      sfxMuted: false,
    });
  });

  it('keep valid values, including the limits 0 and 1', () => {
    const clean = settings.sanitize(
      { musicVolume: 0, musicMuted: true, sfxVolume: 1, sfxMuted: true },
      {},
    );
    expect(clean).toMatchObject({ musicVolume: 0, musicMuted: true, sfxVolume: 1, sfxMuted: true });
    expect(settings.sanitize({ musicVolume: 0.3, sfxVolume: 0.8 }, {})).toMatchObject({
      musicVolume: 0.3,
      sfxVolume: 0.8,
    });
  });

  it.each([-0.1, 1.01, 7, NaN, Infinity, '0.8', null, undefined, true, {}])(
    'fall back to 0.5 for the volume value %s',
    (bad) => {
      const clean = settings.sanitize({ musicVolume: bad, sfxVolume: bad }, {});
      expect(clean.musicVolume).toBe(0.5);
      expect(clean.sfxVolume).toBe(0.5);
    },
  );

  it.each(['true', 1, 0, null, undefined, {}, []])(
    'fall back to "not muted" for the mute value %s',
    (bad) => {
      const clean = settings.sanitize({ musicMuted: bad, sfxMuted: bad }, {});
      expect(clean.musicMuted).toBe(false);
      expect(clean.sfxMuted).toBe(false);
    },
  );

  it('are cleaned field by field: one bad field does not reset the others', () => {
    const clean = settings.sanitize({ musicVolume: 9, musicMuted: true, sfxVolume: 0.2 }, {});
    expect(clean).toMatchObject({ musicVolume: 0.5, musicMuted: true, sfxVolume: 0.2 });
  });

  it('survive "delete progress" (rule 48 resets progress only)', () => {
    const store = fakeStore({
      settings: { musicVolume: 0.1, musicMuted: true, sfxVolume: 0.9, sfxMuted: true },
      progress: { jumps: 12, finishedRides: 3, unlocked: 3 },
    });
    resetProgress(store);
    for (const key of SOUND_FIELDS) {
      expect(store.data.settings[key]).toBe(
        { musicVolume: 0.1, musicMuted: true, sfxVolume: 0.9, sfxMuted: true }[key],
      );
    }
    expect(store.data.progress).toMatchObject({ jumps: 0, finishedRides: 0, unlocked: 1 });
  });
});

describe('fps display setting field', () => {
  it('defaults to off', () => {
    expect(settings.defaults({}).showFps).toBe(false);
  });

  it('is filled in for an old save without it (backward compatible, rule 47)', () => {
    expect(settings.sanitize({ lang: 'de', camera: 'rider' }, {})).toMatchObject({
      showFps: false,
      camera: 'rider',
    });
  });

  it('keeps a valid value', () => {
    expect(settings.sanitize({ showFps: true }, {}).showFps).toBe(true);
    expect(settings.sanitize({ showFps: false }, {}).showFps).toBe(false);
  });

  it.each(['true', 1, 0, null, undefined, {}, []])('falls back to off for the value %s', (bad) => {
    expect(settings.sanitize({ showFps: bad }, {}).showFps).toBe(false);
  });
});

describe('controls help flag', () => {
  it('is off by default and in an old save without it (rule 56)', () => {
    expect(settings.defaults({}).controlsHelpSeen).toBe(false);
    expect(settings.sanitize({ lang: 'de' }, {}).controlsHelpSeen).toBe(false);
  });

  it('keeps a saved true and repairs a wrong type', () => {
    expect(settings.sanitize({ controlsHelpSeen: true }, {}).controlsHelpSeen).toBe(true);
    expect(settings.sanitize({ controlsHelpSeen: 'yes' }, {}).controlsHelpSeen).toBe(false);
  });
});
