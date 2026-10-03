import { describe, expect, it } from 'vitest';
import { installTestHooks, testHooksRequested } from './test-hooks.js';

const fakeApp = (services = {}) => ({
  current: 'menu',
  stack: ['menu'],
  services,
  go: () => {},
});

describe('testHooksRequested', () => {
  it('is only true with the testhooks query parameter', () => {
    expect(testHooksRequested('?testhooks')).toBe(true);
    expect(testHooksRequested('?a=1&testhooks')).toBe(true);
    expect(testHooksRequested('')).toBe(false);
    expect(testHooksRequested('?testhook')).toBe(false);
  });
});

describe('installTestHooks', () => {
  it('exposes read-only helpers on the target object', () => {
    const target = {};
    const store = { get: (section) => ({ section }) };
    installTestHooks({ app: fakeApp(), store, inputMode: { touch: true }, target });
    const hooks = target.__zhfTest;
    expect(hooks.screen()).toBe('menu');
    expect(hooks.stack()).toEqual(['menu']);
    expect(hooks.store('progress')).toEqual({ section: 'progress' });
    expect(hooks.touchMode()).toBe(true);
    expect(hooks.ride()).toBeNull();
    expect(hooks.audio()).toBeNull();
  });

  it('reports the audio state when the audio service offers one', () => {
    const target = {};
    const app = fakeApp({ audio: { getState: () => ({ unlocked: false }) } });
    installTestHooks({ app, store: { get: () => ({}) }, inputMode: { touch: false }, target });
    expect(target.__zhfTest.audio()).toEqual({ unlocked: false });
  });

  it('copies the ride state instead of exposing live objects', () => {
    const horse = { x: 1, z: 2, y: 0, heading: 0, speed: 3, gait: 'trot', gallop: false };
    const session = {
      modeId: 'free',
      obstacles: [],
      view: { horse, rails: new Map([['f1', [true]]]), aid: null, highlight: null, hud: null },
    };
    const ride = {
      session,
      screen: { paused: false },
      engine: {
        level: 'low',
        cameraRig: { mode: 'follow' },
        horse: { object: { position: { toArray: () => [1, 0, 2] } } },
      },
    };
    const target = {};
    installTestHooks({
      app: fakeApp({ ride }),
      store: { get: () => ({}) },
      inputMode: { touch: false },
      target,
    });
    const snapshot = target.__zhfTest.ride();
    expect(snapshot.horse).toMatchObject({ x: 1, z: 2, speed: 3, gait: 'trot', jump: null });
    expect(snapshot.rails).toEqual({ f1: [true] });
    horse.x = 99;
    expect(snapshot.horse.x).toBe(1);
  });
});
