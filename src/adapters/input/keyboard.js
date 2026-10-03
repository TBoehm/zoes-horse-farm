// Keyboard input (rule 8): A/D steer, W/S speed, Shift (held) gallop, Space jump,
// Esc pause, C camera. A gallop that the game ends needs Shift to be pressed again (rule 9).
const HANDLED = new Set([
  'KeyW',
  'KeyA',
  'KeyS',
  'KeyD',
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'ShiftLeft',
  'ShiftRight',
  'Space',
  'Escape',
  'KeyC',
]);

export function createKeyboard(target = window) {
  const down = new Set();
  let shiftLatched = false;
  let jump = false;
  let pause = false;
  let camera = false;

  const isShift = () => down.has('ShiftLeft') || down.has('ShiftRight');

  const onDown = (e) => {
    if (!HANDLED.has(e.code)) return;
    if (e.target?.closest?.('input, textarea')) return;
    e.preventDefault();
    if (e.repeat) return;
    down.add(e.code);
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
      return state;
    },
    /** Gallop ended by the game: active again only after release and a new press. */
    latchGallop() {
      if (isShift()) shiftLatched = true;
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
