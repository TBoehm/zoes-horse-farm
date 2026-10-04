import { describe, expect, it } from 'vitest';
import { STRING_AREAS } from '../i18n/index.js';
import { defaultHelpMode, HELP_MODES, KEYBOARD_ROWS, rowsFor, TOUCH_ROWS } from './help-content.js';

const de = Object.assign({}, ...STRING_AREAS.map((a) => a.de));
const en = Object.assign({}, ...STRING_AREAS.map((a) => a.en));

const labels = (rows) => rows.flatMap((r) => r.keys.flat().map((k) => k.label ?? k.labelKey));
const textKeys = (rows) =>
  rows.flatMap((r) => [
    r.textKey,
    ...(r.keys ?? []).flat().flatMap((k) => (k.labelKey ? [k.labelKey] : [])),
    ...(r.glyphs ?? []).flatMap((g) => [g.labelKey, g.nameKey].filter(Boolean)),
  ]);

describe('controls help content', () => {
  it('keyboard: every key of rule 8 is there', () => {
    expect(labels(KEYBOARD_ROWS)).toEqual(
      expect.arrayContaining(['W', 'S', 'A', 'D', '↑', '↓', '←', '→', 'Shift', 'C', 'Esc']),
    );
    expect(labels(KEYBOARD_ROWS)).toContain('help.key.space');
  });

  it('touch: joystick, canter, jump, camera and pause', () => {
    const kinds = TOUCH_ROWS.flatMap((r) => r.glyphs.map((g) => g.kind));
    expect(kinds).toEqual(expect.arrayContaining(['stick', 'gallop', 'jump', 'small']));
    const symbols = TOUCH_ROWS.flatMap((r) => r.glyphs.map((g) => g.symbol));
    expect(symbols).toEqual(expect.arrayContaining(['🎥', '❚❚']));
  });

  it('row ids are unique within a mode', () => {
    for (const rows of [KEYBOARD_ROWS, TOUCH_ROWS]) {
      expect(new Set(rows.map((r) => r.id)).size).toBe(rows.length);
    }
  });

  it('every text has a German and an English translation', () => {
    for (const key of [...textKeys(KEYBOARD_ROWS), ...textKeys(TOUCH_ROWS)]) {
      expect(de, key).toHaveProperty([key]);
      expect(en, key).toHaveProperty([key]);
    }
  });

  it('preselects the mode of the current input and offers both modes', () => {
    expect(defaultHelpMode(true)).toBe('touch');
    expect(defaultHelpMode(false)).toBe('keyboard');
    expect(HELP_MODES).toEqual(['keyboard', 'touch']);
    expect(rowsFor('touch')).toBe(TOUCH_ROWS);
    expect(rowsFor('keyboard')).toBe(KEYBOARD_ROWS);
  });
});
