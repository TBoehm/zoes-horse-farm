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
        settling: true,
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
    expect(snapshot.graphicsSettling).toBe(true);
    expect(snapshot.rails).toEqual({ f1: [true] });
    horse.x = 99;
    expect(snapshot.horse.x).toBe(1);
  });
});

describe('context loss hooks', () => {
  const engineWith = (extension) => ({
    renderer: { getContext: () => ({ getExtension: () => extension }) },
  });
  const install = (services) => {
    const target = {};
    installTestHooks({ app: fakeApp(services), store: { get: () => ({}) }, inputMode: {}, target });
    return target.__zhfTest;
  };

  it('calls WEBGL_lose_context of the engine context', () => {
    const calls = [];
    const hooks = install({
      engine: engineWith({
        loseContext: () => calls.push('lose'),
        restoreContext: () => calls.push('restore'),
      }),
    });
    expect(hooks.loseContext()).toBe(true);
    expect(hooks.restoreContext()).toBe(true);
    expect(calls).toEqual(['lose', 'restore']);
  });

  it('can restore although the lost context no longer hands out the extension', () => {
    const calls = [];
    const extension = {
      loseContext: () => calls.push('lose'),
      restoreContext: () => calls.push('restore'),
    };
    let lost = false;
    const engine = {
      renderer: { getContext: () => ({ getExtension: () => (lost ? null : extension) }) },
    };
    const hooks = install({ engine });
    hooks.loseContext();
    lost = true;
    expect(hooks.restoreContext()).toBe(true);
    expect(calls).toEqual(['lose', 'restore']);
  });

  it('returns false without an engine or without the extension', () => {
    expect(install({}).loseContext()).toBe(false);
    expect(install({ engine: engineWith(null) }).restoreContext()).toBe(false);
  });
});
