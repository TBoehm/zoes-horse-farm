import { describe, expect, it } from 'vitest';
import { createEmitter } from './events.js';

describe('createEmitter', () => {
  it('delivers the payload to every handler of the event type only', () => {
    const emitter = createEmitter();
    const a = [];
    const b = [];
    const other = [];
    emitter.on('x', (p) => a.push(p));
    emitter.on('x', (p) => b.push(p));
    emitter.on('y', (p) => other.push(p));
    emitter.emit('x', 1);
    expect(a).toEqual([1]);
    expect(b).toEqual([1]);
    expect(other).toEqual([]);
  });

  it('ignores an event without handlers', () => {
    expect(() => createEmitter().emit('nobody', 1)).not.toThrow();
  });

  it('calls a handler once per registration (the same function twice counts once)', () => {
    const emitter = createEmitter();
    let calls = 0;
    const fn = () => (calls += 1);
    emitter.on('x', fn);
    emitter.on('x', fn);
    emitter.emit('x');
    expect(calls).toBe(1);
  });

  it('stops delivering after unsubscribe, and unsubscribing twice is harmless', () => {
    const emitter = createEmitter();
    const seen = [];
    const off = emitter.on('x', (p) => seen.push(p));
    emitter.emit('x', 1);
    off();
    off();
    emitter.emit('x', 2);
    expect(seen).toEqual([1]);
  });

  it('only removes its own handler', () => {
    const emitter = createEmitter();
    const seen = [];
    const off = emitter.on('x', () => seen.push('a'));
    emitter.on('x', () => seen.push('b'));
    off();
    emitter.emit('x');
    expect(seen).toEqual(['b']);
  });

  it('works on a snapshot while emitting: removing or adding handlers affects the next emit', () => {
    const emitter = createEmitter();
    const seen = [];
    const off = {};
    emitter.on('x', () => {
      seen.push('first');
      off.second();
      emitter.on('x', () => seen.push('late'));
    });
    off.second = emitter.on('x', () => seen.push('second'));
    emitter.emit('x');
    expect(seen).toEqual(['first', 'second']);
    seen.length = 0;
    emitter.emit('x');
    expect(seen).toEqual(['first', 'late']);
  });

  it('allows a handler to unsubscribe itself while emitting', () => {
    const emitter = createEmitter();
    let calls = 0;
    const off = emitter.on('x', () => {
      calls += 1;
      off();
    });
    emitter.emit('x');
    emitter.emit('x');
    expect(calls).toBe(1);
  });
});
