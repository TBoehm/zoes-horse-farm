import { afterEach, describe, expect, it, vi } from 'vitest';
import { createErrorReporter, createRenderGate, watchContextLoss } from './resilience.js';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('watchContextLoss', () => {
  const fire = (target, type) => {
    const event = new Event(type, { cancelable: true });
    target.dispatchEvent(event);
    return event;
  };

  it('reports loss and restore and tracks the state', () => {
    const canvas = new EventTarget();
    const calls = [];
    const watch = watchContextLoss(canvas, {
      onLost: () => calls.push('lost'),
      onRestored: () => calls.push('restored'),
    });
    expect(watch.lost).toBe(false);
    fire(canvas, 'webglcontextlost');
    expect(watch.lost).toBe(true);
    fire(canvas, 'webglcontextrestored');
    expect(watch.lost).toBe(false);
    expect(calls).toEqual(['lost', 'restored']);
  });

  it('prevents the default of the loss event, otherwise the browser never restores', () => {
    const canvas = new EventTarget();
    watchContextLoss(canvas, {});
    expect(fire(canvas, 'webglcontextlost').defaultPrevented).toBe(true);
  });

  it('keeps the state right when a callback throws', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const canvas = new EventTarget();
    const watch = watchContextLoss(canvas, {
      onLost: () => {
        throw new Error('boom');
      },
    });
    expect(() => fire(canvas, 'webglcontextlost')).not.toThrow();
    expect(watch.lost).toBe(true);
  });

  it('stops listening after dispose', () => {
    const canvas = new EventTarget();
    const onLost = vi.fn();
    const watch = watchContextLoss(canvas, { onLost });
    watch.dispose();
    fire(canvas, 'webglcontextlost');
    expect(onLost).not.toHaveBeenCalled();
  });
});

describe('createRenderGate', () => {
  it('is open at first', () => {
    expect(createRenderGate().blocked).toBe(false);
  });

  it('blocks until the promise is done', async () => {
    const gate = createRenderGate();
    let finish;
    gate.hold(new Promise((resolve) => (finish = resolve)), 1000);
    expect(gate.blocked).toBe(true);
    finish();
    await Promise.resolve();
    await Promise.resolve();
    expect(gate.blocked).toBe(false);
  });

  it('opens after a failed promise', async () => {
    const gate = createRenderGate();
    gate.hold(Promise.reject(new Error('compile failed')), 1000);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(gate.blocked).toBe(false);
  });

  it('opens at the latest after the time limit', () => {
    vi.useFakeTimers();
    const gate = createRenderGate();
    gate.hold(new Promise(() => {}), 2500);
    vi.advanceTimersByTime(2499);
    expect(gate.blocked).toBe(true);
    vi.advanceTimersByTime(2);
    expect(gate.blocked).toBe(false);
  });

  it('a newer hold replaces an older one: the old promise does not open the gate', async () => {
    const gate = createRenderGate();
    let finishOld;
    gate.hold(new Promise((resolve) => (finishOld = resolve)), 1000);
    gate.hold(new Promise(() => {}), 1000);
    finishOld();
    await Promise.resolve();
    await Promise.resolve();
    expect(gate.blocked).toBe(true);
  });

  it('stays open for a value that is not a promise', () => {
    const gate = createRenderGate();
    gate.hold(undefined, 1000);
    expect(gate.blocked).toBe(false);
  });

  it('release opens the gate at once', () => {
    const gate = createRenderGate();
    gate.hold(new Promise(() => {}), 1000);
    gate.release();
    expect(gate.blocked).toBe(false);
  });
});

describe('createErrorReporter', () => {
  it('logs the first error at once', () => {
    const log = vi.fn();
    const report = createErrorReporter({ log, now: () => 0 });
    const error = new Error('x');
    report('frame', error);
    expect(log).toHaveBeenCalledTimes(1);
    expect(log.mock.calls[0][0]).toContain('frame');
    expect(log.mock.calls[0][1]).toBe(error);
  });

  it('does not flood the console when the same error repeats every frame', () => {
    const log = vi.fn();
    let ms = 0;
    const report = createErrorReporter({ log, intervalMs: 5000, now: () => ms });
    for (let i = 0; i < 300; i += 1) {
      ms += 16;
      report('frame', new Error('x'));
    }
    expect(log).toHaveBeenCalledTimes(1);
    ms += 5000;
    report('frame', new Error('x'));
    expect(log).toHaveBeenCalledTimes(2);
  });

  it('logs a different place at once', () => {
    const log = vi.fn();
    const report = createErrorReporter({ log, now: () => 0 });
    report('frame', new Error('x'));
    report('render', new Error('y'));
    expect(log).toHaveBeenCalledTimes(2);
  });
});
