import { describe, expect, it, vi } from 'vitest';
import {
  createErrorLog,
  debugRequested,
  describeError,
  installErrorCapture,
} from './debug-info.js';

describe('debugRequested', () => {
  it('is only true with the debug query parameter', () => {
    expect(debugRequested('?debug')).toBe(true);
    expect(debugRequested('?testhooks&debug')).toBe(true);
    expect(debugRequested('?debug=1')).toBe(true);
    expect(debugRequested('')).toBe(false);
    expect(debugRequested('?testhooks')).toBe(false);
    expect(debugRequested('?debugger')).toBe(false);
  });
});

describe('describeError', () => {
  it('names an Error with its message', () => {
    expect(describeError(new TypeError('boom'))).toBe('TypeError: boom');
  });

  it('passes strings through and prints other values', () => {
    expect(describeError('plain')).toBe('plain');
    expect(describeError(42)).toBe('42');
    expect(describeError(undefined)).toBe('undefined');
    expect(describeError({ a: 1 })).toBe('[object Object]');
  });

  it('survives values that cannot be turned into text', () => {
    const bad = {
      toString: () => {
        throw new Error('no');
      },
    };
    expect(typeof describeError(bad)).toBe('string');
  });
});

describe('createErrorLog', () => {
  it('keeps the last entries with their time, newest last', () => {
    let t = 0;
    const log = createErrorLog({ max: 3, now: () => (t += 1.5) });
    log.add('first');
    log.add('second');
    expect(log.entries).toEqual([
      { atS: 1.5, message: 'first' },
      { atS: 3, message: 'second' },
    ]);
    expect(log.count).toBe(2);
  });

  it('drops the oldest entry beyond the limit but keeps counting', () => {
    const log = createErrorLog({ max: 2, now: () => 0 });
    for (const m of ['a', 'b', 'c', 'd']) log.add(m);
    expect(log.entries.map((e) => e.message)).toEqual(['c', 'd']);
    expect(log.count).toBe(4);
  });

  it('shortens very long messages', () => {
    const log = createErrorLog({ max: 1, maxLength: 10, now: () => 0 });
    log.add('x'.repeat(50));
    expect(log.entries[0].message).toHaveLength(10);
    expect(log.entries[0].message.endsWith('…')).toBe(true);
  });

  it('keeps the last 5 by default', () => {
    const log = createErrorLog({ now: () => 0 });
    for (let i = 0; i < 8; i += 1) log.add(`e${i}`);
    expect(log.entries.map((e) => e.message)).toEqual(['e3', 'e4', 'e5', 'e6', 'e7']);
  });
});

function fakeWindow() {
  const listeners = {};
  return {
    addEventListener: (type, fn) => (listeners[type] = [...(listeners[type] ?? []), fn]),
    removeEventListener: (type, fn) =>
      (listeners[type] = (listeners[type] ?? []).filter((f) => f !== fn)),
    fire: (type, event) => (listeners[type] ?? []).forEach((fn) => fn(event)),
    count: (type) => (listeners[type] ?? []).length,
  };
}

describe('installErrorCapture', () => {
  it('records console.error calls and still calls the original', () => {
    const log = createErrorLog({ now: () => 0 });
    const original = vi.fn();
    const con = { error: original };
    installErrorCapture(log, { win: fakeWindow(), con });
    con.error('3D view: render failed', new Error('lost'));
    expect(original).toHaveBeenCalledWith('3D view: render failed', expect.any(Error));
    expect(log.entries[0].message).toBe('3D view: render failed Error: lost');
  });

  it('records window errors and unhandled rejections', () => {
    const log = createErrorLog({ now: () => 0 });
    const win = fakeWindow();
    installErrorCapture(log, { win, con: { error: () => {} } });
    win.fire('error', { message: 'Script error at line 3' });
    win.fire('error', { message: '', error: new RangeError('deep') });
    win.fire('unhandledrejection', { reason: new Error('rejected') });
    expect(log.entries.map((e) => e.message)).toEqual([
      'Script error at line 3',
      'RangeError: deep',
      'Error: rejected',
    ]);
  });

  it('uninstall restores console.error and removes the listeners', () => {
    const log = createErrorLog({ now: () => 0 });
    const original = () => {};
    const con = { error: original };
    const win = fakeWindow();
    const uninstall = installErrorCapture(log, { win, con });
    expect(con.error).not.toBe(original);
    uninstall();
    expect(con.error).toBe(original);
    expect(win.count('error')).toBe(0);
    expect(win.count('unhandledrejection')).toBe(0);
  });

  it('a logging problem never breaks console.error', () => {
    const log = {
      add: () => {
        throw new Error('log broke');
      },
    };
    const original = vi.fn();
    const con = { error: original };
    installErrorCapture(log, { win: fakeWindow(), con });
    expect(() => con.error('x')).not.toThrow();
    expect(original).toHaveBeenCalled();
  });
});
