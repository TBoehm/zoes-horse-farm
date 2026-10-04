import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  collectGpuObjects,
  createErrorReporter,
  createGpuEpoch,
  createRenderGate,
  createRestoreWatchdog,
  releaseNow,
  watchContextLoss,
} from './resilience.js';

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

  it('prevents the default of the loss event (safety net next to three.js)', () => {
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
});

describe('createRestoreWatchdog', () => {
  it('fires once when the restore does not arrive in time', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    const watchdog = createRestoreWatchdog({ timeoutMs: 8000, onTimeout });
    watchdog.start();
    vi.advanceTimersByTime(7999);
    expect(onTimeout).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2);
    expect(onTimeout).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(60_000);
    expect(onTimeout).toHaveBeenCalledTimes(1);
  });

  it('does not fire after cancel (the context came back)', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    const watchdog = createRestoreWatchdog({ timeoutMs: 8000, onTimeout });
    watchdog.start();
    vi.advanceTimersByTime(5000);
    watchdog.cancel();
    vi.advanceTimersByTime(60_000);
    expect(onTimeout).not.toHaveBeenCalled();
  });

  it('a second start restarts the countdown instead of stacking timers', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    const watchdog = createRestoreWatchdog({ timeoutMs: 8000, onTimeout });
    watchdog.start();
    vi.advanceTimersByTime(6000);
    watchdog.start();
    vi.advanceTimersByTime(6000);
    expect(onTimeout).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2001);
    expect(onTimeout).toHaveBeenCalledTimes(1);
  });

  it('waits about 8 s by default', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    createRestoreWatchdog({ onTimeout }).start();
    vi.advanceTimersByTime(7999);
    expect(onTimeout).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2);
    expect(onTimeout).toHaveBeenCalledTimes(1);
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

const disposable = () => ({ dispose: vi.fn() });

describe('releaseNow', () => {
  it('disposes the object and tolerates a missing one', () => {
    const obj = disposable();
    releaseNow(obj);
    expect(obj.dispose).toHaveBeenCalledTimes(1);
    expect(() => releaseNow(null)).not.toThrow();
  });
});

describe('createGpuEpoch', () => {
  it('disposes objects as usual as long as no context was ever lost', () => {
    const gpu = createGpuEpoch();
    const obj = disposable();
    gpu.release(obj);
    expect(obj.dispose).toHaveBeenCalledTimes(1);
  });

  it('only forgets objects that lived through a context loss (their handles are stale)', () => {
    const gpu = createGpuEpoch();
    const old = disposable();
    gpu.contextLost([old]);
    gpu.contextRestored();
    gpu.release(old);
    expect(old.dispose).not.toHaveBeenCalled();
    expect(gpu.isStale(old)).toBe(true);
  });

  it('disposes objects that were built after the loss normally once the context is back', () => {
    const gpu = createGpuEpoch();
    const old = disposable();
    gpu.contextLost([old]);
    gpu.contextRestored();
    const fresh = disposable();
    gpu.release(fresh);
    expect(fresh.dispose).toHaveBeenCalledTimes(1);
  });

  it('makes no GL calls at all while the context is lost', () => {
    const gpu = createGpuEpoch();
    gpu.contextLost([]);
    const built = disposable();
    gpu.release(built);
    expect(built.dispose).not.toHaveBeenCalled();
    expect(gpu.lost).toBe(true);
    gpu.contextRestored();
    expect(gpu.lost).toBe(false);
  });

  it('marks objects again at a second loss (they may have been uploaded again)', () => {
    const gpu = createGpuEpoch();
    const obj = disposable();
    gpu.contextLost([]);
    gpu.contextRestored();
    gpu.contextLost([obj]);
    gpu.contextRestored();
    gpu.release(obj);
    expect(obj.dispose).not.toHaveBeenCalled();
  });

  it('tolerates a missing object', () => {
    const gpu = createGpuEpoch();
    expect(() => gpu.release(null)).not.toThrow();
    expect(() => gpu.release(undefined)).not.toThrow();
  });
});

describe('collectGpuObjects', () => {
  const texture = () => ({ isTexture: true });
  const traverseOf = (nodes) => ({
    traverse: (fn) => nodes.forEach(fn),
  });

  it('finds geometries, materials, their textures, skeleton textures and shadow maps', () => {
    const map = texture();
    const normalMap = texture();
    const material = { map, normalMap, color: { r: 1 }, roughness: 0.5 };
    const geometry = {};
    const boneTexture = texture();
    const shadowMap = { isRenderTarget: true, texture: texture() };
    const scene = {
      ...traverseOf([
        { geometry, material },
        { skeleton: { boneTexture } },
        { shadow: { map: shadowMap } },
      ]),
    };
    const found = collectGpuObjects(scene);
    for (const obj of [geometry, material, map, normalMap, boneTexture, shadowMap]) {
      expect(found).toContain(obj);
    }
  });

  it('handles material arrays, instanced meshes, the scene environment and extra roots', () => {
    const a = { map: texture() };
    const b = {};
    const instanced = { isInstancedMesh: true, geometry: {}, material: [a, b] };
    const environment = texture();
    const extra = { isRenderTarget: true };
    const scene = { ...traverseOf([instanced]), environment, background: { r: 1 } };
    const found = collectGpuObjects(scene, [extra, null]);
    for (const obj of [instanced, a, b, a.map, environment, extra]) expect(found).toContain(obj);
    expect(found).not.toContain(null);
    expect(found).not.toContain(scene.background);
  });

  it('lists every object once', () => {
    const shared = {};
    const scene = traverseOf([
      { geometry: shared, material: {} },
      { geometry: shared, material: {} },
    ]);
    const found = collectGpuObjects(scene);
    expect(found.filter((o) => o === shared)).toHaveLength(1);
  });
});
