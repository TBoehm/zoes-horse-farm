// The one list of keys the game reacts to. The keyboard adapter handles exactly these keys, and
// pressing any of them ends the touch mode (rule 11), so the two must never drift apart.
export const GAME_KEYS = new Set([
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
