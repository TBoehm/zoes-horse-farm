// Helpers that keep the 3D view playable when things go wrong (rule 4): WebGL context loss, shader
// compilation stalls after a quality switch and exceptions in the frame loop. Pure, no three.js.

// How long the ride screen waits for `webglcontextrestored` before it asks to reload the page
// (some browsers stop restoring after repeated losses). A technical value, not a game value.
const CONTEXT_RESTORE_TIMEOUT_MS = 8000;

/**
 * Watches the canvas for a lost and restored WebGL context.
 * The default of the loss event is prevented as a safety net: without that the browser never
 * restores the context (https://www.khronos.org/webgl/wiki/HandlingContextLost). three.js
 * (r186, WebGLRenderer onContextLost) already does this on its own listener, so the call here
 * only keeps us independent of that detail. Callbacks may throw; the state stays right.
 */
export function watchContextLoss(canvas, { onLost, onRestored } = {}) {
  let lost = false;
  const handleLost = (event) => {
    event.preventDefault();
    lost = true;
    onLost?.();
  };
  const handleRestored = () => {
    lost = false;
    onRestored?.();
  };
  // A throwing callback must not break the other listeners (three.js has its own on the canvas)
  const safe = (fn) => (event) => {
    try {
      fn(event);
    } catch (err) {
      console.error('Context loss handler failed', err);
    }
  };
  const lostListener = safe(handleLost);
  const restoredListener = safe(handleRestored);
  canvas.addEventListener('webglcontextlost', lostListener, false);
  canvas.addEventListener('webglcontextrestored', restoredListener, false);
  return {
    get lost() {
      return lost;
    },
  };
}

/** Frees a GPU object right away (the normal case, as long as no context was ever lost). */
export function releaseNow(object) {
  object?.dispose();
}

/**
 * Tells which GPU objects must not be released with GL calls any more (rule 4).
 *
 * three.js (r186) registers 'dispose' listeners in the bookkeeping instances that exist when an
 * object is uploaded. A restored context gets new instances (initGLContext), but the old
 * listeners stay on the objects: disposing an object that was uploaded before the loss would
 * delete handles of the lost context ("INVALID_OPERATION: delete: object does not belong to this
 * context", seen in WebKit; Chromium logs it as a warning). The GPU memory went away with the
 * context, so such an object is only forgotten. `contextLost(objects)` is called with everything
 * that lived on the GPU at that moment (collectGpuObjects), `release(object)` replaces
 * `object.dispose()` wherever the view frees GPU objects while it runs. Without a loss nothing
 * changes: every object is disposed. While the context is lost no GL call is made at all.
 * Known limit: an object that survives a loss and is uploaded again stays on the GPU when it is
 * released later (one generation of rebuilt objects per loss), which is the price of never
 * logging a GL error.
 */
export function createGpuEpoch() {
  const stale = new WeakSet();
  let lost = false;
  return {
    get lost() {
      return lost;
    },
    contextLost(objects = []) {
      lost = true;
      for (const object of objects) if (object) stale.add(object);
    },
    contextRestored() {
      lost = false;
    },
    isStale(object) {
      return stale.has(object);
    },
    /** Disposes the object unless its GPU resources belong to a lost context. */
    release(object) {
      if (!object || lost || stale.has(object)) return;
      object.dispose();
    },
  };
}

const hasTexture = (value) => Boolean(value && value.isTexture);

/**
 * Everything of a scene that can have GPU resources: geometries, materials with their textures,
 * instanced meshes, skeleton bone textures, shadow maps and the scene's environment texture.
 * An object that is on the GPU was drawn, so it hangs in the scene; `extras` are render targets
 * and other things held outside of it. Plain duck typing: no three.js import.
 */
export function collectGpuObjects(scene, extras = []) {
  const found = new Set();
  const add = (object) => {
    if (object) found.add(object);
  };
  const addMaterial = (material) => {
    if (!material) return;
    add(material);
    for (const value of Object.values(material)) if (hasTexture(value)) add(value);
  };
  scene.traverse((node) => {
    add(node.geometry);
    if (Array.isArray(node.material)) node.material.forEach(addMaterial);
    else addMaterial(node.material);
    if (node.isInstancedMesh) add(node);
    add(node.skeleton?.boneTexture);
    add(node.shadow?.map);
  });
  if (hasTexture(scene.environment)) add(scene.environment);
  if (hasTexture(scene.background)) add(scene.background);
  extras.forEach(add);
  return [...found];
}

/**
 * Holds back the frame loop while shaders compile (after a quality switch or a restored context),
 * so that the page stays responsive instead of stalling inside the first draw call. The gate
 * always opens again: when the promise settles (also on failure) or after `maxMs`.
 */
export function createRenderGate() {
  let blocked = false;
  let token = 0;
  let timer = 0;

  function open(mine) {
    if (mine !== token) return; // a newer hold has taken over
    blocked = false;
    clearTimeout(timer);
  }

  return {
    hold(promise, maxMs) {
      token += 1;
      const mine = token;
      clearTimeout(timer);
      if (!promise || typeof promise.then !== 'function') {
        blocked = false;
        return;
      }
      blocked = true;
      timer = setTimeout(() => open(mine), maxMs);
      promise.then(
        () => open(mine),
        () => open(mine),
      );
    },
    get blocked() {
      return blocked;
    },
  };
}

/**
 * Countdown for a context that does not come back: start() when it is lost, cancel() when it is
 * restored; onTimeout fires once if the time runs out first. A second start() restarts it.
 */
export function createRestoreWatchdog({ timeoutMs = CONTEXT_RESTORE_TIMEOUT_MS, onTimeout }) {
  let timer = 0;
  const cancel = () => {
    clearTimeout(timer);
    timer = 0;
  };
  return {
    start() {
      cancel();
      timer = setTimeout(() => {
        timer = 0;
        onTimeout?.();
      }, timeoutMs);
    },
    cancel,
  };
}

/**
 * Logs errors from a frame loop without flooding the console: the same place is logged once per
 * `intervalMs` (an error thrown every frame would otherwise print 60 lines per second).
 */
export function createErrorReporter({
  log = console.error,
  intervalMs = 5000,
  now = () => Date.now(),
} = {}) {
  const lastLogged = new Map();
  return (where, error) => {
    const t = now();
    const last = lastLogged.get(where);
    if (last !== undefined && t - last < intervalMs) return;
    lastLogged.set(where, t);
    log(`3D view: ${where} failed, keeping the loop running`, error);
  };
}
