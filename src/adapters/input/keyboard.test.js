// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { createKeyboard, isControlTarget } from './keyboard.js';

const cleanups = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()();
  document.body.innerHTML = '';
});

function setup(options) {
  const kb = createKeyboard(window, options);
  cleanups.push(() => kb.dispose());
  return kb;
}

/** Dispatches a bubbling key event on `el`; returns the event (see defaultPrevented). */
function key(type, code, el = document.body, init = {}) {
  const e = new KeyboardEvent(type, { code, bubbles: true, cancelable: true, ...init });
  el.dispatchEvent(e);
  return e;
}
const press = (code, el) => key('keydown', code, el);
const release = (code, el) => key('keyup', code, el);

describe('keyboard input (rule 8)', () => {
  it('maps WASD and arrow keys to steer/throttle', () => {
    const kb = setup();
    press('KeyD');
    press('ArrowUp');
    expect(kb.poll()).toMatchObject({ steer: 1, throttle: 1 });
    release('KeyD');
    press('ArrowLeft');
    release('ArrowUp');
    press('KeyS');
    expect(kb.poll()).toMatchObject({ steer: -1, throttle: -1 });
  });

  it('jump, pause and camera are edges that poll() consumes', () => {
    const kb = setup();
    press('Space');
    press('Escape');
    press('KeyC');
    expect(kb.poll()).toMatchObject({ jump: true, pause: true, camera: true });
    expect(kb.poll()).toMatchObject({ jump: false, pause: false, camera: false });
  });

  it('clearEdges drops pending edges (used when the ride resumes)', () => {
    const kb = setup();
    press('Space');
    press('KeyC');
    kb.clearEdges();
    expect(kb.poll()).toMatchObject({ jump: false, camera: false });
  });

  it('prevents the default of handled keys while active', () => {
    setup();
    expect(press('Space').defaultPrevented).toBe(true);
    expect(press('ArrowDown').defaultPrevented).toBe(true);
    expect(press('KeyX').defaultPrevented).toBe(false);
  });
});

describe('keyboard input ignores keys it must not consume (SRT-002 M1)', () => {
  it('does nothing while the ride is not active (paused, or another screen on top)', () => {
    let active = false;
    const kb = setup({ isActive: () => active });
    const e = press('Space');
    expect(e.defaultPrevented).toBe(false);
    expect(press('Escape').defaultPrevented).toBe(false);
    expect(press('KeyW').defaultPrevented).toBe(false);
    active = true;
    expect(kb.poll()).toMatchObject({ jump: false, pause: false, throttle: 0 });
  });

  it('Space and Enter on a focused button are left alone', () => {
    const kb = setup();
    const button = document.createElement('button');
    document.body.append(button);
    expect(press('Space', button).defaultPrevented).toBe(false);
    expect(press('Enter', button).defaultPrevented).toBe(false);
    expect(press('Escape', button).defaultPrevented).toBe(false);
    expect(kb.poll()).toMatchObject({ jump: false, pause: false });
  });

  it('text fields and links are controls, plain containers are not', () => {
    const mk = (html) => {
      document.body.innerHTML = html;
      return document.body.firstElementChild;
    };
    expect(isControlTarget(mk('<input>'))).toBe(true);
    expect(isControlTarget(mk('<textarea></textarea>'))).toBe(true);
    expect(isControlTarget(mk('<select></select>'))).toBe(true);
    expect(isControlTarget(mk('<a href="#x">x</a>'))).toBe(true);
    expect(isControlTarget(mk('<div role="button"></div>'))).toBe(true);
    expect(isControlTarget(mk('<button><span id="s"></span></button>').firstElementChild)).toBe(
      true,
    );
    expect(isControlTarget(mk('<div></div>'))).toBe(false);
    expect(isControlTarget(document.body)).toBe(false);
    expect(isControlTarget(null)).toBe(false);
  });

  it('releasing a key always works, even while inactive', () => {
    let active = true;
    const kb = setup({ isActive: () => active });
    press('KeyW');
    active = false;
    release('KeyW');
    active = true;
    expect(kb.poll().throttle).toBe(0);
  });
});

describe('gallop latch (rule 9)', () => {
  it('Shift held gives gallop; a latch needs release and a new press', () => {
    const kb = setup();
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(true);
    kb.latchGallop();
    expect(kb.poll().gallop).toBe(false);
    release('ShiftLeft');
    expect(kb.poll().gallop).toBe(false);
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(true);
  });

  it('latching without Shift held does nothing', () => {
    const kb = setup();
    kb.latchGallop();
    press('ShiftRight');
    expect(kb.poll().gallop).toBe(true);
  });

  it('latchGallop({ onNextShiftPress }) catches the Shift press that follows in the same event', () => {
    const kb = setup();
    kb.latchGallop({ onNextShiftPress: true });
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(false);
    release('ShiftLeft');
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(true);
  });

  it('the pending latch expires with the next poll', () => {
    const kb = setup();
    kb.latchGallop({ onNextShiftPress: true });
    kb.poll();
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(true);
  });

  it('a window blur releases everything', () => {
    const kb = setup();
    press('ShiftLeft');
    press('KeyW');
    kb.latchGallop();
    window.dispatchEvent(new Event('blur'));
    expect(kb.poll()).toMatchObject({ gallop: false, throttle: 0 });
    press('ShiftLeft');
    expect(kb.poll().gallop).toBe(true);
  });
});
