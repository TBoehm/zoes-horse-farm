// Touch mode (rule 11): touch-only devices always, keyboard-only devices never,
// devices with both start without it and switch on touch or on a game key.
import { createEmitter } from '../../shared/events.js';
import { GAME_KEYS } from './game-keys.js';

export { GAME_KEYS };

/** Determines the device class: 'touch' | 'keyboard' | 'hybrid'. */
export function classifyDevice({
  maxTouchPoints = 0,
  hasTouchEvents = false,
  finePointer = false,
}) {
  const touch = maxTouchPoints > 0 || hasTouchEvents;
  if (!touch) return 'keyboard';
  return finePointer ? 'hybrid' : 'touch';
}

export function detectDevice(win = globalThis.window) {
  const mm = (q) => {
    try {
      return win.matchMedia(q).matches;
    } catch {
      return false;
    }
  };
  return classifyDevice({
    maxTouchPoints: win.navigator?.maxTouchPoints ?? 0,
    hasTouchEvents: 'ontouchstart' in win,
    // Deliberately no stylus/hover detection: tablets with a stylus are touch-only devices
    finePointer: mm('(any-pointer: fine)'),
  });
}

export function createInputMode({ device, target } = {}) {
  const emitter = createEmitter();
  let touch = device === 'touch';

  function set(next) {
    if (next === touch) return;
    touch = next;
    emitter.emit('change', touch);
  }

  const onPointer = (e) => {
    if (device === 'hybrid' && (e.pointerType === 'touch' || e.type === 'touchstart')) set(true);
  };
  const onKey = (e) => {
    const editable = e.target?.closest?.('input, textarea, [contenteditable="true"]');
    if (device === 'hybrid' && GAME_KEYS.has(e.code) && !editable) set(false);
  };

  if (target) {
    target.addEventListener('pointerdown', onPointer, { capture: true, passive: true });
    target.addEventListener('touchstart', onPointer, { capture: true, passive: true });
    target.addEventListener('keydown', onKey, { capture: true });
  }

  return {
    device,
    get touch() {
      return touch;
    },
    onChange(fn) {
      return emitter.on('change', fn);
    },
    dispose() {
      if (!target) return;
      target.removeEventListener('pointerdown', onPointer, { capture: true });
      target.removeEventListener('touchstart', onPointer, { capture: true });
      target.removeEventListener('keydown', onKey, { capture: true });
    },
  };
}

export function isPortrait(win = globalThis.window) {
  return win.innerHeight > win.innerWidth;
}
