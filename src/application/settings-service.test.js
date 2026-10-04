import { describe, expect, it } from 'vitest';
import './settings-schema.js';
import { createSettingsService } from './settings-service.js';
import { fakeStore } from '../../tests/support/test-ports.js';

const setup = (settings) => {
  const store = fakeStore({ settings });
  return { store, service: createSettingsService(store) };
};

describe('settings service', () => {
  it('get returns the saved settings', () => {
    const { service } = setup({ lang: 'de', camera: 'rider' });
    expect(service.get()).toMatchObject({ lang: 'de', camera: 'rider' });
  });

  it('get returns a copy: changing it does not change the saved settings', () => {
    const { service, store } = setup({ camera: 'follow' });
    service.get().camera = 'rider';
    expect(store.data.settings.camera).toBe('follow');
  });

  describe('language', () => {
    it('saves a supported language', () => {
      const { service, store } = setup({ lang: 'de' });
      service.setLang('en');
      expect(store.data.settings.lang).toBe('en');
    });

    it('ignores an unsupported language', () => {
      const { service, store } = setup({ lang: 'de' });
      service.setLang('fr');
      service.setLang(undefined);
      expect(store.data.settings.lang).toBe('de');
    });
  });

  describe('graphics', () => {
    it('tells the listeners when the player selects automatic graphics', () => {
      const { service } = setup({ graphicsAuto: false, graphicsLevel: 'high' });
      let calls = 0;
      const off = service.onAutoSelected(() => (calls += 1));
      service.setGraphicsAuto('low');
      expect(calls).toBe(1);
      service.setGraphicsLevel('medium');
      service.setAutoLevel('low');
      expect(calls).toBe(1);
      off();
      service.setGraphicsAuto('low');
      expect(calls).toBe(1);
    });

    it('does not tell the listeners about an invalid automatic selection', () => {
      const { service } = setup({});
      let calls = 0;
      service.onAutoSelected(() => (calls += 1));
      service.setGraphicsAuto('ultra');
      expect(calls).toBe(0);
    });

    it('automatic: auto on, level is the device level', () => {
      const { service, store } = setup({ graphicsAuto: false, graphicsLevel: 'high' });
      service.setGraphicsAuto('low');
      expect(store.data.settings).toMatchObject({ graphicsAuto: true, graphicsLevel: 'low' });
    });

    it('automatic without a known device level resets the level to null', () => {
      const { service, store } = setup({ graphicsAuto: false, graphicsLevel: 'high' });
      service.setGraphicsAuto(null);
      expect(store.data.settings).toMatchObject({ graphicsAuto: true, graphicsLevel: null });
    });

    it('automatic ignores an unknown device level', () => {
      const { service, store } = setup({ graphicsAuto: false, graphicsLevel: 'high' });
      service.setGraphicsAuto('ultra');
      expect(store.data.settings).toMatchObject({ graphicsAuto: false, graphicsLevel: 'high' });
    });

    it('manual level: auto off, level saved', () => {
      const { service, store } = setup({ graphicsAuto: true, graphicsLevel: 'low' });
      service.setGraphicsLevel('high');
      expect(store.data.settings).toMatchObject({ graphicsAuto: false, graphicsLevel: 'high' });
    });

    it('manual level ignores an unknown level', () => {
      const { service, store } = setup({ graphicsAuto: true, graphicsLevel: 'low' });
      service.setGraphicsLevel('ultra');
      service.setGraphicsLevel(null);
      expect(store.data.settings).toMatchObject({ graphicsAuto: true, graphicsLevel: 'low' });
    });

    it('governor downgrade keeps auto on and only changes the level', () => {
      const { service, store } = setup({ graphicsAuto: true, graphicsLevel: 'high' });
      service.setAutoLevel('medium');
      expect(store.data.settings).toMatchObject({ graphicsAuto: true, graphicsLevel: 'medium' });
    });

    it('governor downgrade ignores an unknown level', () => {
      const { service, store } = setup({ graphicsAuto: true, graphicsLevel: 'high' });
      service.setAutoLevel('ultra');
      expect(store.data.settings.graphicsLevel).toBe('high');
    });
  });

  describe('camera', () => {
    it('saves follow and rider, ignores anything else', () => {
      const { service, store } = setup({ camera: 'follow' });
      service.setCamera('rider');
      expect(store.data.settings.camera).toBe('rider');
      service.setCamera('birdseye');
      expect(store.data.settings.camera).toBe('rider');
      service.setCamera('follow');
      expect(store.data.settings.camera).toBe('follow');
    });
  });

  describe('jump aid', () => {
    it('switches the free-ride and course aids independently', () => {
      const { service, store } = setup({ aidFree: true, aidCourse: false });
      service.setAid('course', true);
      expect(store.data.settings).toMatchObject({ aidFree: true, aidCourse: true });
      service.setAid('free', false);
      expect(store.data.settings).toMatchObject({ aidFree: false, aidCourse: true });
    });

    it('ignores an unknown kind and non-boolean values', () => {
      const { service, store } = setup({ aidFree: true, aidCourse: false });
      service.setAid('race', true);
      service.setAid('free', 'no');
      expect(store.data.settings).toMatchObject({ aidFree: true, aidCourse: false });
    });
  });

  describe('fps display', () => {
    it('switches the display on and off', () => {
      const { service, store } = setup({ showFps: false });
      service.setShowFps(true);
      expect(store.data.settings.showFps).toBe(true);
      service.setShowFps(false);
      expect(store.data.settings.showFps).toBe(false);
    });

    it('ignores non-boolean values', () => {
      const { service, store } = setup({ showFps: true });
      service.setShowFps('no');
      service.setShowFps(0);
      service.setShowFps(undefined);
      expect(store.data.settings.showFps).toBe(true);
    });

    it('leaves the other settings alone', () => {
      const { service, store } = setup({ camera: 'rider', graphicsLevel: 'low' });
      service.setShowFps(true);
      expect(store.data.settings).toMatchObject({ camera: 'rider', graphicsLevel: 'low' });
    });
  });

  describe('sound', () => {
    it('sets the volume per channel', () => {
      const { service, store } = setup({});
      service.setVolume('music', 0.2);
      service.setVolume('sfx', 0.9);
      expect(store.data.settings).toMatchObject({ musicVolume: 0.2, sfxVolume: 0.9 });
    });

    it('limits the volume to 0..1 and ignores non-numbers', () => {
      const { service, store } = setup({ musicVolume: 0.4 });
      service.setVolume('music', 7);
      expect(store.data.settings.musicVolume).toBe(1);
      service.setVolume('music', -1);
      expect(store.data.settings.musicVolume).toBe(0);
      service.setVolume('music', 0.4);
      service.setVolume('music', NaN);
      service.setVolume('music', '0.9');
      expect(store.data.settings.musicVolume).toBe(0.4);
    });

    it('mutes and unmutes per channel', () => {
      const { service, store } = setup({ musicMuted: false, sfxMuted: false });
      service.setMuted('sfx', true);
      expect(store.data.settings).toMatchObject({ sfxMuted: true, musicMuted: false });
      service.setMuted('sfx', false);
      service.setMuted('music', true);
      expect(store.data.settings).toMatchObject({ sfxMuted: false, musicMuted: true });
    });

    it('ignores an unknown channel', () => {
      const { service, store } = setup({ musicVolume: 0.5 });
      service.setVolume('voice', 1);
      service.setMuted('voice', true);
      expect(store.data.settings).not.toHaveProperty('voiceVolume');
      expect(store.data.settings).not.toHaveProperty('voiceMuted');
    });
  });

  describe('onChange', () => {
    it('notifies with the new settings and stops after unsubscribe', () => {
      const { service } = setup({ camera: 'follow' });
      const seen = [];
      const stop = service.onChange((settings) => seen.push(settings.camera));
      service.setCamera('rider');
      stop();
      service.setCamera('follow');
      expect(seen).toEqual(['rider']);
    });
  });

  describe('controls help', () => {
    it('markControlsHelpSeen saves the flag and keeps the other settings', () => {
      const { service, store } = setup({ controlsHelpSeen: false, camera: 'rider' });
      service.markControlsHelpSeen();
      expect(store.data.settings).toMatchObject({ controlsHelpSeen: true, camera: 'rider' });
      expect(service.get().controlsHelpSeen).toBe(true);
    });

    it('is idempotent', () => {
      const { service, store } = setup({ controlsHelpSeen: true });
      service.markControlsHelpSeen();
      expect(store.data.settings.controlsHelpSeen).toBe(true);
    });
  });
});
