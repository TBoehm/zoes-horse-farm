// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { focusableIn, nextFocusIndex, trapTab } from './focus-trap.js';

afterEach(() => {
  document.body.innerHTML = '';
});

function dialog() {
  document.body.innerHTML = `
    <button id="outside">outside</button>
    <div id="dlg">
      <button id="a">A</button>
      <button id="b" disabled>B</button>
      <button id="c">C</button>
      <button id="d" hidden>D</button>
    </div>`;
  return document.getElementById('dlg');
}

const tab = (shiftKey = false) =>
  new KeyboardEvent('keydown', { key: 'Tab', code: 'Tab', shiftKey, cancelable: true });

describe('nextFocusIndex', () => {
  it('cycles forward and backward', () => {
    expect(nextFocusIndex(3, 0, false)).toBe(1);
    expect(nextFocusIndex(3, 2, false)).toBe(0);
    expect(nextFocusIndex(3, 0, true)).toBe(2);
    expect(nextFocusIndex(3, 2, true)).toBe(1);
  });

  it('starts at the first (or last) element when focus is outside', () => {
    expect(nextFocusIndex(3, -1, false)).toBe(0);
    expect(nextFocusIndex(3, -1, true)).toBe(2);
  });

  it('returns -1 without elements', () => {
    expect(nextFocusIndex(0, -1, false)).toBe(-1);
  });
});

describe('focusableIn', () => {
  it('lists enabled, visible controls in order', () => {
    const dlg = dialog();
    expect(focusableIn(dlg).map((el) => el.id)).toEqual(['a', 'c']);
  });
});

describe('trapTab', () => {
  it('wraps Tab from the last to the first control', () => {
    const dlg = dialog();
    document.getElementById('c').focus();
    const e = tab();
    expect(trapTab(e, dlg)).toBe(true);
    expect(e.defaultPrevented).toBe(true);
    expect(document.activeElement.id).toBe('a');
  });

  it('wraps Shift+Tab from the first to the last control', () => {
    const dlg = dialog();
    document.getElementById('a').focus();
    trapTab(tab(true), dlg);
    expect(document.activeElement.id).toBe('c');
  });

  it('pulls focus back in when it is outside the dialog', () => {
    const dlg = dialog();
    document.getElementById('outside').focus();
    trapTab(tab(), dlg);
    expect(document.activeElement.id).toBe('a');
  });

  it('moves normally inside', () => {
    const dlg = dialog();
    document.getElementById('a').focus();
    trapTab(tab(), dlg);
    expect(document.activeElement.id).toBe('c');
  });

  it('ignores other keys', () => {
    const dlg = dialog();
    const e = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
    expect(trapTab(e, dlg)).toBe(false);
    expect(e.defaultPrevented).toBe(false);
  });
});
