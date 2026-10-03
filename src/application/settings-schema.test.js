import { describe, expect, it } from 'vitest';
import './settings-schema.js';
import { getSections } from './save-schema.js';
import { resetProgress } from './progress-service.js';
import { fakeStore } from './test-ports.js';

const settings = getSections().get('settings');
const SOUND_FIELDS = ['musicVolume', 'musicMuted', 'sfxVolume', 'sfxMuted'];

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
