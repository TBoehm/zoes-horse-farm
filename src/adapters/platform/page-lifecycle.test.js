import { describe, expect, it } from 'vitest';
import { installPageLifecycle } from './page-lifecycle.js';

function setup() {
  const calls = [];
  const guard = {
    markBackground: () => calls.push('background'),
    resume: () => calls.push('resume'),
  };
  const doc = Object.assign(new EventTarget(), { visibilityState: 'visible', hidden: false });
  const win = new EventTarget();
  const off = installPageLifecycle(guard, { doc, win });
  const hide = (hidden) => {
    doc.hidden = hidden;
    doc.visibilityState = hidden ? 'hidden' : 'visible';
    doc.dispatchEvent(new Event('visibilitychange'));
  };
  return { calls, doc, win, off, hide };
}

describe('page lifecycle', () => {
  it('marks the guard clean when the page goes to the background and back when it returns', () => {
    const { calls, hide } = setup();
    hide(true);
    hide(false);
    expect(calls).toEqual(['background', 'resume']);
  });

  it('marks the guard clean on pagehide (reload, close)', () => {
    const { calls, win } = setup();
    win.dispatchEvent(new Event('pagehide'));
    expect(calls).toEqual(['background']);
  });

  it('resumes on pageshow of a visible page (back-forward cache)', () => {
    const { calls, win } = setup();
    win.dispatchEvent(new Event('pagehide'));
    win.dispatchEvent(new Event('pageshow'));
    expect(calls).toEqual(['background', 'resume']);
  });

  it('does not resume on pageshow while the page is hidden', () => {
    const { calls, win, hide } = setup();
    hide(true);
    win.dispatchEvent(new Event('pageshow'));
    expect(calls).toEqual(['background']);
  });

  it('removes its listeners', () => {
    const { calls, win, off } = setup();
    off();
    win.dispatchEvent(new Event('pagehide'));
    expect(calls).toEqual([]);
  });
});
