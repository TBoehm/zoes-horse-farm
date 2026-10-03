// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { createInputMode } from '../platform/input-mode.js';
import { createInput, mergeInputs } from './input.js';
import { createKeyboard } from './keyboard.js';

/** Stand-in for the nipplejs controls: only the state the input adapter touches. */
function fakeTouch() {
  const t = {
    gallop: false,
    visible: null,
    state: { steer: 0, throttle: 0, jump: false, pause: false, camera: false },
    clearEdgesCalls: 0,
    setGallop(on) {
      t.gallop = on;
    },
    setVisible(v) {
      t.visible = v;
    },
    poll() {
      return { ...t.state, gallop: t.gallop };
    },
    clearEdges() {
      t.clearEdgesCalls += 1;
      t.state.jump = t.state.pause = t.state.camera = false;
    },
    dispose() {},
  };
  return t;
}

const cleanups = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()();
});

function setup(device = 'hybrid') {
  const inputMode = createInputMode({ device, target: window });
  const touch = fakeTouch();
  const input = createInput({
    container: document.body,
    inputMode,
    deps: { createKeyboard: (target) => createKeyboard(target), createTouchControls: () => touch },
  });
  cleanups.push(() => {
    input.dispose();
    inputMode.dispose();
  });
  return { input, inputMode, touch };
}

const fire = (type, code) =>
  document.body.dispatchEvent(new KeyboardEvent(type, { code, bubbles: true, cancelable: true }));
const touchDown = () =>
  document.body.dispatchEvent(
    Object.assign(new Event('pointerdown', { bubbles: true }), { pointerType: 'touch' }),
  );

describe('mergeInputs', () => {
  it('adds steer/throttle clamped to ±1 and ORs the buttons', () => {
    const merged = mergeInputs(
      { steer: 1, throttle: -1, gallop: false, jump: true, pause: false, camera: false },
      { steer: 0.5, throttle: -0.5, gallop: true, jump: false, pause: true, camera: true },
    );
    expect(merged).toEqual({
      steer: 1,
      throttle: -1,
      gallop: true,
      jump: true,
      pause: true,
      camera: true,
    });
  });
});

describe('input rules', () => {
  it('shows the touch controls according to the mode', () => {
    const { touch, inputMode } = setup('hybrid');
    expect(touch.visible).toBe(false);
    touchDown();
    expect(inputMode.touch).toBe(true);
    expect(touch.visible).toBe(true);
  });

  it('the game ending the gallop switches touch off and needs a new Shift press', () => {
    const { input, touch } = setup();
    touch.setGallop(true);
    fire('keydown', 'ShiftLeft');
    expect(input.poll().gallop).toBe(true);
    input.endGallop();
    expect(touch.gallop).toBe(false);
    expect(input.poll().gallop).toBe(false);
    fire('keyup', 'ShiftLeft');
    fire('keydown', 'ShiftLeft');
    expect(input.poll().gallop).toBe(true);
  });

  it('a touch-mode switch ends the touch gallop (rules 9/11)', () => {
    const { input, touch } = setup();
    touchDown();
    touch.setGallop(true);
    expect(input.poll().gallop).toBe(true);
    // a game key switches back to the keyboard
    fire('keydown', 'KeyW');
    expect(touch.gallop).toBe(false);
    expect(input.poll().gallop).toBe(false);
  });

  it('a touch-mode switch caused by pressing Shift does not start a gallop until Shift is pressed anew', () => {
    const { input, touch } = setup();
    touchDown();
    touch.setGallop(true);
    fire('keydown', 'ShiftLeft'); // switches the mode and is the Shift press itself
    expect(touch.gallop).toBe(false);
    expect(input.poll().gallop).toBe(false);
    fire('keyup', 'ShiftLeft');
    fire('keydown', 'ShiftLeft');
    expect(input.poll().gallop).toBe(true);
  });

  it('a touch-mode switch caused by an arrow key also counts (shared game keys)', () => {
    const { inputMode } = setup();
    touchDown();
    expect(inputMode.touch).toBe(true);
    fire('keydown', 'ArrowUp');
    expect(inputMode.touch).toBe(false);
  });

  it('clearEdges (after "Continue") drops pending jump/pause/camera of both sources', () => {
    const { input, touch } = setup();
    fire('keydown', 'Space');
    touch.state.jump = true;
    input.clearEdges();
    expect(input.poll()).toMatchObject({ jump: false, pause: false, camera: false });
    expect(touch.clearEdgesCalls).toBe(1);
  });

  it('keeps the touch gallop across a pause (clearEdges does not touch it)', () => {
    const { input, touch } = setup();
    touch.setGallop(true);
    input.clearEdges();
    expect(input.poll().gallop).toBe(true);
  });

  it('resetTouchGallop only affects touch', () => {
    const { input, touch } = setup();
    touch.setGallop(true);
    input.resetTouchGallop();
    expect(touch.gallop).toBe(false);
  });
});
