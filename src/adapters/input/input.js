// Merges keyboard and touch into one InputState (contract: architecture.md "Eingabe").
import { createKeyboard } from './keyboard.js';
import { createTouchControls } from './touch-controls.js';

const clamp1 = (v) => Math.max(-1, Math.min(1, v));

export function createInput({ container, inputMode, target = window }) {
  const keyboard = createKeyboard(target);
  const touch = createTouchControls(container);
  touch.setVisible(inputMode.touch);

  // Rules 9/11: switching the touch mode ends an active gallop
  const offMode = inputMode.onChange((on) => {
    touch.setVisible(on);
    touch.setGallop(false);
    keyboard.latchGallop();
  });

  return {
    keyboard,
    touch,
    poll() {
      const k = keyboard.poll();
      const tc = touch.poll();
      return {
        steer: clamp1(k.steer + tc.steer),
        throttle: clamp1(k.throttle + tc.throttle),
        gallop: k.gallop || tc.gallop,
        jump: k.jump || tc.jump,
        pause: k.pause || tc.pause,
        camera: k.camera || tc.camera,
      };
    },
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
