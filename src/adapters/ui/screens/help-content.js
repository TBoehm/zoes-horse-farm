// What the controls help shows (rule 56, rule 8, rule 10): plain data, the screen renders it.
// A key is { label } (shown as is) or { labelKey } (a translated text, e.g. the space bar).
// `keys` is a list of alternatives; each alternative is a group of key caps shown side by side.

const key = (label) => ({ label });

export const KEYBOARD_ROWS = [
  { id: 'faster', keys: [[key('W')], [key('↑')]], textKey: 'help.kb.faster' },
  { id: 'slower', keys: [[key('S')], [key('↓')]], textKey: 'help.kb.slower' },
  {
    id: 'steer',
    keys: [
      [key('A'), key('D')],
      [key('←'), key('→')],
    ],
    textKey: 'help.kb.steer',
  },
  { id: 'gallop', keys: [[key('Shift')]], textKey: 'help.kb.gallop' },
  { id: 'jump', keys: [[{ labelKey: 'help.key.space' }]], textKey: 'help.kb.jump' },
  { id: 'camera', keys: [[key('C')]], textKey: 'help.kb.camera' },
  { id: 'pause', keys: [[key('Esc')]], textKey: 'help.kb.pause' },
];

// Glyphs look like the touch controls of the ride: the joystick, "Canter", "Jump", and the small
// camera and pause buttons. `symbol` is a decorative character, `labelKey` the button's label,
// `nameKey` the text a screen reader gets for a symbol that has no label.
export const TOUCH_ROWS = [
  {
    id: 'speed',
    glyphs: [{ kind: 'stick', symbol: '↕', nameKey: 'help.joystick' }],
    textKey: 'help.touch.speed',
  },
  {
    id: 'steer',
    glyphs: [{ kind: 'stick', symbol: '↔', nameKey: 'help.joystick' }],
    textKey: 'help.touch.steer',
  },
  {
    id: 'gallop',
    glyphs: [{ kind: 'gallop', labelKey: 'ride.touch.gallop' }],
    textKey: 'help.touch.gallop',
  },
  {
    id: 'jump',
    glyphs: [{ kind: 'jump', labelKey: 'ride.touch.jump' }],
    textKey: 'help.touch.jump',
  },
  {
    id: 'cameraPause',
    glyphs: [
      { kind: 'small', symbol: '🎥' },
      { kind: 'small', symbol: '❚❚' },
    ],
    textKey: 'help.touch.cameraPause',
  },
];

export const HELP_MODES = ['keyboard', 'touch'];

/** The mode shown first: the one that matches the current input (rule 56). */
export const defaultHelpMode = (touch) => (touch ? 'touch' : 'keyboard');

export const rowsFor = (mode) => (mode === 'touch' ? TOUCH_ROWS : KEYBOARD_ROWS);
