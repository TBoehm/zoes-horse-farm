// Keyboard input (rule 8): A/D steer, W/S speed, Shift (held) gallop, Space jump,
// Esc pause, C camera. A gallop that the game ends needs Shift to be pressed again (rule 9).
import { GAME_KEYS } from '../platform/game-keys.js';

const CONTROL_SELECTOR =
  'input, textarea, select, button, summary, a[href], [contenteditable="true"], [role="button"]';

/** Focused controls keep their own keys (Space/Enter on a button, typing in a field). */
export function isControlTarget(target) {
  return Boolean(target?.closest?.(CONTROL_SELECTOR));
}

/**
 * @param {EventTarget} target where key events are listened for
 * @param {{ isActive?: () => boolean }} [options] isActive: false while the ride is paused or
 *   another screen is on top; the keyboard then neither records nor consumes keys.
 */
export function createKeyboard(target = window, { isActive = () => true } = {}) {
  const down = new Set();
  let shiftLatched = false;
  // A mode switch caused by the Shift keydown itself happens before this handler sees the key
  let latchNextShiftPress = false;
  let jump = false;
  let pause = false;
  let camera = false;

  const isShift = () => down.has('ShiftLeft') || down.has('ShiftRight');

  const onDown = (e) => {
    if (!GAME_KEYS.has(e.code) || !isActive() || isControlTarget(e.target)) return;
    e.preventDefault();
    if (e.repeat) return;
    down.add(e.code);
    if (e.code === 'ShiftLeft' || e.code === 'ShiftRight') {
      if (latchNextShiftPress) shiftLatched = true;
    } else {
      latchNextShiftPress = false;
    }
    if (e.code === 'Space') jump = true;
    if (e.code === 'Escape') pause = true;
    if (e.code === 'KeyC') camera = true;
  };
  const onUp = (e) => {
    down.delete(e.code);
    if (!isShift()) shiftLatched = false;
  };
  const onBlur = () => {
    down.clear();
    shiftLatched = false;
    latchNextShiftPress = false;
  };
  target.addEventListener('keydown', onDown);
  target.addEventListener('keyup', onUp);
  window.addEventListener('blur', onBlur);

  return {
    /** Reads the state; edges (jump, pause, camera) are reset in the process. */
    poll() {
      const right = down.has('KeyD') || down.has('ArrowRight');
      const left = down.has('KeyA') || down.has('ArrowLeft');
      const up = down.has('KeyW') || down.has('ArrowUp');
      const back = down.has('KeyS') || down.has('ArrowDown');
      const state = {
        steer: (right ? 1 : 0) - (left ? 1 : 0),
        throttle: (up ? 1 : 0) - (back ? 1 : 0),
        gallop: isShift() && !shiftLatched,
        jump,
        pause,
        camera,
      };
      jump = pause = camera = false;
      latchNextShiftPress = false;
      return state;
    },
    /**
     * Gallop ended by the game: active again only after release and a new press.
     * onNextShiftPress: also latch a Shift press that arrives right after this call (the mode
     * switch is triggered by that very key press).
     */
    latchGallop({ onNextShiftPress = false } = {}) {
      if (isShift()) shiftLatched = true;
      else if (onNextShiftPress) latchNextShiftPress = true;
    },
    get shiftHeld() {
      return isShift();
    },
    clearEdges() {
      jump = pause = camera = false;
    },
    dispose() {
      target.removeEventListener('keydown', onDown);
      target.removeEventListener('keyup', onUp);
      window.removeEventListener('blur', onBlur);
    },
  };
}
