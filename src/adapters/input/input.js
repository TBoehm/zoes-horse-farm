// Führt Tastatur und Touch zu einem InputState zusammen (Vertrag: architecture.md „Eingabe").
import { createKeyboard } from './keyboard.js';
import { createTouchControls } from './touch-controls.js';

const clamp1 = (v) => Math.max(-1, Math.min(1, v));

export function createInput({ container, inputMode, target = window }) {
  const keyboard = createKeyboard(target);
  const touch = createTouchControls(container);
  touch.setVisible(inputMode.touch);

  // Regel 9/11: Wechsel des Touch-Modus beendet einen aktiven Galopp
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
    /** Das Spiel beendet den Galopp (Verweigerung, Zaun): Touch aus, Shift neu drücken. */
    endGallop() {
      touch.setGallop(false);
      keyboard.latchGallop();
    },
    /** Galopp-Zustand nach „Weiter": Tastatur nur bei gehaltenem Shift, Touch behält Zustand. */
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
