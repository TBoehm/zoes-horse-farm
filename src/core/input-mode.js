// Touch-Modus (Regel 11): reine Touch-Geräte immer, reine Tastatur-Geräte nie,
// Geräte mit beidem starten ohne und wechseln bei Berührung bzw. Spieltaste.
import { createEmitter } from './events.js';

export const GAME_KEYS = new Set([
  'KeyW',
  'KeyA',
  'KeyS',
  'KeyD',
  'ShiftLeft',
  'ShiftRight',
  'Space',
  'Escape',
  'KeyC',
]);

/** Ermittelt die Geräteklasse: 'touch' | 'keyboard' | 'hybrid'. */
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
    // Stift-/Hover-Erkennung bewusst nicht: Tablets mit Stift sind reine Touch-Geräte
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
    /** Für Tests: Ereignisse direkt einspeisen. */
    handlePointer: onPointer,
    handleKey: onKey,
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
