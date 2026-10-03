// Merges keyboard and touch into one InputState (contract: architecture.md "Eingabe").
import { createKeyboard } from './keyboard.js';
import { createTouchControls } from './touch-controls.js';
import { clamp } from '../../shared/math.js';

/** Combines the keyboard and touch states into one InputState. */
export function mergeInputs(k, tc) {
  return {
    steer: clamp(k.steer + tc.steer, -1, 1),
    throttle: clamp(k.throttle + tc.throttle, -1, 1),
    gallop: k.gallop || tc.gallop,
    jump: k.jump || tc.jump,
    pause: k.pause || tc.pause,
    camera: k.camera || tc.camera,
  };
}

/**
 * @param {{ isActive?: () => boolean }} [keyboardOptions] see createKeyboard
 * @param {object} [deps] replaceable parts (tests)
 */
export function createInput({
  container,
  inputMode,
  target = window,
  isActive,
  deps: {
    createKeyboard: makeKeyboard = createKeyboard,
    createTouchControls: makeTouch = createTouchControls,
  } = {},
}) {
  const keyboard = makeKeyboard(target, { isActive });
  const touch = makeTouch(container);
  touch.setVisible(inputMode.touch);

  // Rules 9/11: switching the touch mode ends an active gallop. The switch is often caused by the
  // Shift key itself (its keydown reaches the mode detection first), so that press must not count.
  const offMode = inputMode.onChange((on) => {
    touch.setVisible(on);
    touch.setGallop(false);
    keyboard.latchGallop({ onNextShiftPress: true });
  });

  return {
    keyboard,
    touch,
    poll: () => mergeInputs(keyboard.poll(), touch.poll()),
    /** The game ends the gallop (refusal, fence): touch off, shift must be pressed again. */
    endGallop() {
      touch.setGallop(false);
      keyboard.latchGallop();
    },
    /** Gallop state after "Continue": keyboard only while shift is held, touch keeps its state. */
    clearEdges() {
      keyboard.clearEdges();
      touch.clearEdges();
    },
    resetTouchGallop() {
      touch.setGallop(false);
    },
    dispose() {
      offMode();
      keyboard.dispose();
      touch.dispose();
    },
  };
}
