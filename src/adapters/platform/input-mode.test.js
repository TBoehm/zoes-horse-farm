import { describe, expect, it } from 'vitest';
import { classifyDevice, createInputMode } from './input-mode.js';
import { GAME_KEYS } from './game-keys.js';

/** Minimal event target: keeps the listeners and lets the tests dispatch events. */
function fakeTarget() {
  const listeners = new Map();
  return {
    addEventListener: (type, fn) => listeners.set(type, fn),
    removeEventListener: (type, fn) => {
      if (listeners.get(type) === fn) listeners.delete(type);
    },
    listenerCount: () => listeners.size,
    dispatch(type, event = {}) {
      listeners.get(type)?.({ type, ...event });
    },
  };
}

function setup(device) {
  const target = fakeTarget();
  const mode = createInputMode({ device, target });
  return {
    mode,
    target,
    touch: (type = 'pointerdown') => target.dispatch(type, { pointerType: 'touch' }),
    mouse: () => target.dispatch('pointerdown', { pointerType: 'mouse' }),
    key: (code, extra = {}) => target.dispatch('keydown', { code, ...extra }),
  };
}

describe('touch mode (rule 11)', () => {
  it('classifies devices', () => {
    expect(classifyDevice({ maxTouchPoints: 0 })).toBe('keyboard');
    expect(classifyDevice({ maxTouchPoints: 5, finePointer: false })).toBe('touch');
    expect(classifyDevice({ maxTouchPoints: 10, finePointer: true })).toBe('hybrid');
  });

  it('touch-only device: always active, keys change nothing', () => {
    const { mode, key } = setup('touch');
    expect(mode.touch).toBe(true);
    key('KeyW');
    expect(mode.touch).toBe(true);
  });

  it('keyboard-only device: never active', () => {
    const { mode, touch } = setup('keyboard');
    touch();
    expect(mode.touch).toBe(false);
  });

  it('hybrid: starts off, touch turns it on, game key turns it off', () => {
    const { mode, touch, mouse, key } = setup('hybrid');
    const changes = [];
    mode.onChange((v) => changes.push(v));
    expect(mode.touch).toBe(false);
    mouse();
    expect(mode.touch).toBe(false);
    touch();
    expect(mode.touch).toBe(true);
    key('KeyX');
    expect(mode.touch).toBe(true);
    key('Space');
    expect(mode.touch).toBe(false);
    expect(changes).toEqual([true, false]);
  });

  it('hybrid: a touchstart also turns it on', () => {
    const { mode, touch } = setup('hybrid');
    touch('touchstart');
    expect(mode.touch).toBe(true);
  });

  it('hybrid: a game key typed into an editable field keeps it on', () => {
    const { mode, touch, key } = setup('hybrid');
    touch();
    key('KeyW', { target: { closest: () => ({}) } });
    expect(mode.touch).toBe(true);
    key('KeyW', { target: { closest: () => null } });
    expect(mode.touch).toBe(false);
  });

  it('dispose removes the listeners', () => {
    const { mode, target, touch } = setup('hybrid');
    expect(target.listenerCount()).toBe(3);
    mode.dispose();
    expect(target.listenerCount()).toBe(0);
    touch();
    expect(mode.touch).toBe(false);
  });
});

describe('shared game keys', () => {
  it('arrow keys steer, so they end the touch mode like WASD', () => {
    for (const code of ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']) {
      const { mode, touch, key } = setup('hybrid');
      touch();
      expect(mode.touch).toBe(true);
      key(code);
      expect(mode.touch).toBe(false);
    }
  });

  it('is the same list the keyboard adapter handles', () => {
    expect(GAME_KEYS.has('KeyW')).toBe(true);
    expect(GAME_KEYS.has('ArrowLeft')).toBe(true);
    expect(GAME_KEYS.has('KeyX')).toBe(false);
  });
});
