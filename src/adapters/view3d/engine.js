// 3D engine: one renderer, one world, one horse with rider, one camera – shared across all rides.
import * as THREE from 'three';
import { h } from '../ui/dom.js';
import { createRenderer, resizeRenderer, setMaxPixelRatio } from './renderer.js';
import { createQualityGovernor, pickInitialLevel, QUALITY_PRESETS } from './quality.js';
import { createErrorReporter, createRenderGate, watchContextLoss } from './resilience.js';
import { createEmitter } from '../../shared/events.js';
import { createWorld } from './world.js';
import { TUNING } from '../../domain/sim/tuning.js';
import { DEFAULT_APPEARANCE } from '../../domain/horse/appearance.js';
import { createHorse } from './horse/index.js';
import { createCameraRig } from './camera.js';

// Longest time the frame loop waits for the shaders of a new quality level (or of a restored
// context) before it draws anyway.
const COMPILE_HOLD_MAX_MS = 2500;

function rendererString(gl) {
  try {
    if (!gl) return '';
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return String(gl.getParameter(ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
  } catch {
    return '';
  }
}

/** Reads the GPU name from a throw-away context (the real renderer needs the level first). */
function probeRendererString() {
  try {
    const probe = document.createElement('canvas');
    const gl = probe.getContext('webgl2') ?? probe.getContext('webgl');
    const name = gl ? rendererString(gl) : '';
    gl?.getExtension('WEBGL_lose_context')?.loseContext();
    return name;
  } catch {
    return '';
  }
}

/** `renderer`: a THREE.WebGLRenderer, or the GPU name as a string. */
export function deviceInfo(renderer, inputMode) {
  const rendererName =
    typeof renderer === 'string' ? renderer : rendererString(renderer?.getContext?.());
  return {
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemory: navigator.deviceMemory,
    isTouch: inputMode?.device === 'touch',
    rendererString: rendererName,
    screenPixels: screen.width * screen.height * (window.devicePixelRatio || 1) ** 2,
  };
}

/** `settings`: the application settings service (the only writer of the settings section). */
export function createEngine({ app, settings: settingsService, inputMode }) {
  const canvas = h('canvas', { class: 'scene-canvas', 'aria-hidden': 'true' });
  canvas.hidden = true;
  app.layers.scene.append(canvas);

  // Graphics level (rule 4): on first start, or on "Automatic" without a level, pick one that
  // fits the device
  if (!settingsService.get().graphicsLevel) {
    settingsService.setGraphicsAuto(pickInitialLevel(deviceInfo(probeRendererString(), inputMode)));
  }
  const settings = settingsService.get();
  let level = settings.graphicsLevel;

  // Antialiasing is a context attribute and cannot change later: follow the stored level
  const renderer = createRenderer(canvas, { antialias: QUALITY_PRESETS[level].antialias });
  const camera = new THREE.PerspectiveCamera(58, 16 / 9, 0.1, 900);
  const cameraRig = createCameraRig(camera);

  // The first picture is not there yet, so the level's pixel ratio can be applied right away. Later
  // changes wait for the render gate (see applyQuality).
  setMaxPixelRatio(renderer, QUALITY_PRESETS[level].pixelRatio);
  let pendingPixelRatio = null;

  const world = createWorld(renderer, { quality: level });
  const horse = createHorse({ ...DEFAULT_APPEARANCE, quality: level });
  world.scene.add(horse.object);

  const emitter = createEmitter();
  const gate = createRenderGate();
  const reportError = createErrorReporter();
  /** Runs one part of the engine; an exception is logged and never breaks the loop or input. */
  function guarded(where, fn) {
    try {
      fn();
    } catch (err) {
      reportError(where, err);
    }
  }

  /**
   * Compiles the shaders for the current scene state without blocking: the frame loop waits (at
   * most COMPILE_HOLD_MAX_MS) and the last picture stays on screen. WebGLRenderer.compileAsync
   * uses KHR_parallel_shader_compile where the browser has it
   * (https://threejs.org/docs/#api/en/renderers/WebGLRenderer.compileAsync).
   */
  function precompile() {
    guarded('shader precompile', () =>
      gate.hold(renderer.compileAsync(world.scene, camera), COMPILE_HOLD_MAX_MS),
    );
  }

  /**
   * Switches the level while the last picture stays on screen. The pixel ratio is NOT changed
   * here: resizing the drawing buffer clears it, and with the render gate closed (shaders
   * compiling, up to COMPILE_HOLD_MAX_MS) nothing would be drawn, so the screen would be black and
   * the ride frozen. The new ratio is stored and applied by the frame loop in the very frame that
   * draws again (the buffer is cleared and refilled before the browser shows it). Between the
   * switch and that frame the old picture is simply a little too sharp or too soft.
   */
  function applyQuality(next) {
    level = next;
    guarded('quality switch', () => {
      pendingPixelRatio = QUALITY_PRESETS[next].pixelRatio;
      world.setQuality(next);
      horse.setQuality(next);
    });
    if (!contextWatch.lost) precompile();
  }

  const governor = createQualityGovernor({
    level,
    auto: settings.graphicsAuto,
    onChange: (next) => {
      applyQuality(next);
      settingsService.setAutoLevel(next); // governor downgrade: "Automatic" stays on
    },
  });

  settingsService.onChange((s) => {
    guarded('settings change', () => {
      if (s.graphicsAuto !== governor.auto) governor.setAuto(s.graphicsAuto);
      if (s.graphicsLevel !== level) {
        governor.setLevel(s.graphicsLevel);
        applyQuality(s.graphicsLevel);
      }
    });
  });

  function resize(force = false) {
    const w = canvas.clientWidth || window.innerWidth;
    const hgt = canvas.clientHeight || window.innerHeight;
    if (resizeRenderer(renderer, camera, w, hgt) || force) {
      camera.aspect = w / hgt;
      camera.updateProjectionMatrix();
    }
  }
  window.addEventListener('resize', () => guarded('resize', () => resize()));
  // WebGL context loss (rule 4): the device took the graphics memory away (typical on phones and
  // tablets, e.g. under memory pressure or when the tab was in the background). three.js has its
  // own listeners on the canvas, registered before ours, so it has already rebuilt its internal
  // state (initGLContext) when `onRestored` runs. We announce the loss so that the ride pauses,
  // rebuild what only lived on the GPU, and announce the restore.
  const contextWatch = watchContextLoss(canvas, {
    onLost() {
      emitter.emit('contextLost');
    },
    onRestored() {
      guarded('context restore', () => {
        world.restoreAfterContextLoss();
        resize(true);
      });
      precompile();
      emitter.emit('contextRestored');
    },
  });

  if (!contextWatch.lost) precompile(); // the shaders of the first level, before the first frame

  let frameFn = null;
  let last = 0;
  function loop(time) {
    const dt = last ? Math.min(TUNING.sim.maxDt, (time - last) / 1000) : 1 / 60;
    const rawDt = last ? (time - last) / 1000 : 1 / 60;
    last = time;
    // shaders are compiling: keep the last picture, do not simulate (no hidden time passes)
    if (gate.blocked) return;
    if (pendingPixelRatio !== null) {
      // drawing happens right below in this frame, so the cleared buffer is never shown
      const ratio = pendingPixelRatio;
      pendingPixelRatio = null;
      guarded('pixel ratio', () => {
        setMaxPixelRatio(renderer, ratio);
        resize(true);
      });
    }
    // each step on its own: a failing step is logged and the others still run (no closures here,
    // this runs every frame)
    try {
      resize();
    } catch (err) {
      reportError('resize', err);
    }
    try {
      frameFn?.(dt, rawDt);
    } catch (err) {
      reportError('frame', err);
    }
    try {
      renderer.render(world.scene, camera);
    } catch (err) {
      reportError('render', err);
    }
  }

  return {
    renderer,
    camera,
    cameraRig,
    world,
    horse,
    governor,
    get level() {
      return level;
    },
    /** true while the WebGL context is lost (the ride is paused by the ride screen). */
    get contextLost() {
      return contextWatch.lost;
    },
    /** Events: 'contextLost', 'contextRestored'. Returns the unsubscribe function. */
    on: emitter.on,
    /** Starts (fn) or stops (null) the frame loop; the canvas is visible only while it runs. */
    run(fn) {
      frameFn = fn;
      last = 0;
      canvas.hidden = !fn;
      renderer.setAnimationLoop(fn ? loop : null);
      if (fn) resize(true);
    },
  };
}

/** Create the engine once per app (only when it is needed). */
export function getEngine(ctx) {
  if (!ctx.services.engine) ctx.services.engine = createEngine(ctx);
  return ctx.services.engine;
}
