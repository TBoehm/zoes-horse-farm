// 3D engine: one renderer, one world, one horse with rider, one camera – shared across all rides.
import * as THREE from 'three';
import { h } from '../ui/dom.js';
import { createRenderer, resizeRenderer, setMaxPixelRatio } from './renderer.js';
import {
  chooseAntialias,
  createQualityGovernor,
  fitPresetToBudget,
  gpuBudgetMB,
  levelAfterContextLoss,
  pickInitialLevel,
  QUALITY_PRESETS,
} from './quality.js';
import {
  createStageQueue,
  planQualityStagesFromState,
  QUALITY_STAGE_IDS,
} from './quality-stages.js';
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
    // the same attributes as the real renderer's context (see renderer.js), so that a dual-GPU
    // laptop hands out the same (high-performance) GPU: the budget is based on its name
    const attributes = { powerPreference: 'high-performance', antialias: false, depth: false };
    const gl = probe.getContext('webgl2', attributes) ?? probe.getContext('webgl', attributes);
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
  const gpuName = probeRendererString();
  if (!settingsService.get().graphicsLevel) {
    settingsService.setGraphicsAuto(pickInitialLevel(deviceInfo(gpuName, inputMode)));
  }
  const settings = settingsService.get();
  let level = settings.graphicsLevel;

  // GPU memory budget: browsers do not tell how much the GPU may use, so what a level needs is
  // estimated (quality.js) and a level that does not fit gets a lower resolution, smaller shadow
  // map and less scenery instead of risking the context. `?testhooks&gpubudget=MB` overrides it for
  // browser tests (set by main.js, never without the test hooks).
  const budgetMB = app.services.gpuBudgetOverrideMB ?? gpuBudgetMB(deviceInfo(gpuName, inputMode));
  const worldInfo = { textures: undefined }; // texture sizes once the world exists (default until)
  let fitInfo = null; // the fit of the level that was applied last, for the debug box
  const viewSize = () => ({
    cssWidth: canvas.clientWidth || window.innerWidth,
    cssHeight: canvas.clientHeight || window.innerHeight,
    devicePixelRatio: window.devicePixelRatio || 1,
  });
  /** The preset of a level, fitted to the budget for the real context and the current size. */
  function effectivePreset(forLevel, antialias) {
    fitInfo = fitPresetToBudget(
      QUALITY_PRESETS[forLevel],
      { ...viewSize(), antialias, textures: worldInfo.textures },
      budgetMB,
    );
    return fitInfo.preset;
  }

  // Antialiasing is a context attribute and cannot change later: follow the stored level, unless
  // the multisampled buffers alone would not fit the budget
  const antialiasWanted = QUALITY_PRESETS[level].antialias;
  const antialiasChosen = chooseAntialias(QUALITY_PRESETS[level], viewSize(), budgetMB);
  const renderer = createRenderer(canvas, { antialias: antialiasChosen });
  const contextAntialias = Boolean(renderer.getContextAttributes()?.antialias);
  const fitFor = (forLevel) => effectivePreset(forLevel, contextAntialias);
  const camera = new THREE.PerspectiveCamera(58, 16 / 9, 0.1, 900);
  const cameraRig = createCameraRig(camera);

  // The first picture is not there yet, so the level's pixel ratio can be applied right away. Later
  // changes wait for the render gate (see applyQuality).
  const firstPreset = fitFor(level);
  setMaxPixelRatio(renderer, firstPreset.pixelRatio);
  let pendingPixelRatio = null;

  const world = createWorld(renderer, { quality: firstPreset });
  worldInfo.textures = world.textureSizes();
  const horse = createHorse({ ...DEFAULT_APPEARANCE, quality: firstPreset.characterDetail });
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

  // Level changes are applied stage by stage (quality-stages.js): `applied` is the level each
  // stage has reached, the queue paces the remaining ones, one per few drawn frames.
  const applied = Object.fromEntries(QUALITY_STAGE_IDS.map((id) => [id, firstPreset]));
  let targetPreset = firstPreset; // the level's preset fitted to the budget
  const stageQueue = createStageQueue();

  /**
   * Applies one stage for `target`. The pixel ratio is NOT changed here: resizing the drawing
   * buffer clears it, and with the render gate closed (shaders compiling, up to
   * COMPILE_HOLD_MAX_MS) nothing would be drawn, so the screen would be black and the ride
   * frozen. The new ratio is stored and applied by the frame loop in the very frame that draws
   * again (the buffer is cleared and refilled before the browser shows it). Between the stage and
   * that frame the old picture is simply a little too sharp or too soft.
   */
  function applyStage(id, target) {
    guarded(`quality stage ${id}`, () => {
      if (id === 'pixelRatio') pendingPixelRatio = target.pixelRatio;
      else if (id === 'characters') horse.setQuality(target.characterDetail);
      else world.applyQualityStage(id, target);
    });
    applied[id] = target;
  }

  /** Everything at once, for a switch nobody sees or that nothing is drawn for (applyQuality). */
  function applyAllNow({ gpu = true } = {}) {
    stageQueue.clear();
    targetPreset = fitFor(level);
    guarded('quality switch', () => {
      world.setQuality(targetPreset, { gpu });
      horse.setQuality(targetPreset.characterDetail);
      if (gpu) {
        pendingPixelRatio = targetPreset.pixelRatio; // the frame loop applies it (see applyStage)
      } else {
        // The context is lost: nothing is shown, so the drawing buffer can change right away. A
        // restored context is created at the size the canvas has by then, so it has to be the new
        // one already (the old size with multisampling is what the device could not carry).
        pendingPixelRatio = null;
        setMaxPixelRatio(renderer, targetPreset.pixelRatio);
        resize(true);
      }
    });
    for (const id of QUALITY_STAGE_IDS) applied[id] = targetPreset;
    governor.interrupt(); // the switch is no measurement
  }

  /**
   * Switches to a level. While a ride draws, the change is split into stages spread over several
   * frames, each followed by a shader precompile with the last picture staying on screen (see
   * advanceStages). Without a running frame loop (menus) or with a lost context nothing is
   * visible and nothing is drawn, so everything is applied at once.
   */
  function applyQuality(next) {
    level = next;
    if (!frameFn || contextWatch.lost) {
      applyAllNow({ gpu: !contextWatch.lost });
      if (!contextWatch.lost) precompile();
      return;
    }
    // every switch, also a manual "high", is checked against the budget
    targetPreset = fitFor(next);
    stageQueue.plan(planQualityStagesFromState(applied, targetPreset));
  }

  /** Called after every drawn frame: starts the next stage when its time has come. */
  function advanceStages() {
    const stage = stageQueue.tick();
    if (!stage) return;
    // the target is read now: a switch during the staging changes where the remaining stages go
    applyStage(stage.id, targetPreset);
    governor.interrupt(); // frames around a stage are slower: not a measurement
    if (stage.compile) precompile();
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
  // tablets, e.g. under memory pressure or when the app was in the background). three.js has its
  // own listeners on the canvas, registered before ours, so it has already rebuilt its internal
  // state (initGLContext) when `onRestored` runs. We announce the loss so that the ride pauses,
  // rebuild what only lived on the GPU, and announce the restore.
  // A loss in the foreground shows that the device is overloaded: the level goes down right away,
  // while nothing is drawn, so that the restored scene comes back already at the new level and
  // size (no switch later). A loss in the background or just after returning does not count
  // (levelAfterContextLoss). The calls on the lost context are ignored by the browser, and three.js
  // resets all its resource bookkeeping in initGLContext on the restore
  // (https://www.khronos.org/webgl/wiki/HandlingContextLost).
  const contextStats = { lost: 0, restored: 0, lostAtS: null, restoredAtS: null };
  let graphicsHintPending = false;
  const secondsSinceStart = () => performance.now() / 1000;
  // Contexts often get lost while the page is in the background (Android app switch): that says
  // nothing about the load of the game, so the loss handler needs to know when the page last
  // went to the background or came back (levelAfterContextLoss).
  let visibilityChangedAtS = -Infinity;
  document.addEventListener('visibilitychange', () => {
    visibilityChangedAtS = secondsSinceStart();
  });
  const contextWatch = watchContextLoss(canvas, {
    onLost() {
      contextStats.lost += 1;
      contextStats.lostAtS = secondsSinceStart();
      guarded('context loss fallback', () => {
        const decision = levelAfterContextLoss({
          auto: settingsService.get().graphicsAuto,
          level,
          visible: document.visibilityState === 'visible',
          sinceVisibilityChangeS: secondsSinceStart() - visibilityChangedAtS,
        });
        const changed = decision.level !== level;
        level = decision.level;
        if (changed) governor.setLevel(level);
        applyAllNow({ gpu: false }); // also finishes a switch that was still in stages
        if (decision.persist) settingsService.setAutoLevel(level); // "Automatic" stays on
        graphicsHintPending = decision.hint;
      });
      emitter.emit('contextLost');
    },
    onRestored() {
      contextStats.restored += 1;
      contextStats.restoredAtS = secondsSinceStart();
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
    if (stageQueue.pending > 0 && !contextWatch.lost) guarded('quality stage', advanceStages);
  }

  // Debug overlay data (`?debug`): one object that is filled again at every call, so that the
  // overlay needs no new object per update.
  const diag = {
    gpu: '',
    budgetGpu: '',
    level: '',
    auto: false,
    devicePixelRatio: 1,
    pixelRatio: 1,
    bufferWidth: 0,
    bufferHeight: 0,
    maxTextureSize: 0,
    contextLost: 0,
    contextRestored: 0,
    lostAtS: null,
    restoredAtS: null,
    stagesPending: 0,
    gpuEstimateMB: 0,
    gpuBudgetMB: 0,
    ratioCap: null,
    shadowCap: null,
    sceneryCapped: false,
    antialias: false,
    antialiasDropped: false,
  };
  function diagnostics() {
    if (!diag.gpu && !contextWatch.lost) diag.gpu = rendererString(renderer.getContext());
    diag.budgetGpu = gpuName; // the name the budget was based on (the probe context)
    diag.level = level;
    diag.auto = governor.auto;
    diag.devicePixelRatio = window.devicePixelRatio || 1;
    diag.pixelRatio = renderer.getPixelRatio();
    diag.bufferWidth = canvas.width;
    diag.bufferHeight = canvas.height;
    diag.maxTextureSize = renderer.capabilities.maxTextureSize;
    diag.contextLost = contextStats.lost;
    diag.contextRestored = contextStats.restored;
    diag.lostAtS = contextStats.lostAtS;
    diag.restoredAtS = contextStats.restoredAtS;
    diag.stagesPending = stageQueue.pending;
    diag.gpuEstimateMB = fitInfo?.estimateMB ?? 0;
    diag.gpuBudgetMB = budgetMB;
    diag.ratioCap = fitInfo?.capped.pixelRatio ?? null;
    diag.shadowCap = fitInfo?.capped.shadowMapSize ?? null;
    diag.sceneryCapped = fitInfo?.capped.scenery ?? false;
    diag.antialias = contextAntialias;
    diag.antialiasDropped = antialiasWanted && !antialiasChosen; // the budget said no
    return diag;
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
    /** true while a level change is still being applied (stages, pixel ratio, shader compile). */
    get settling() {
      return stageQueue.pending > 0 || pendingPixelRatio !== null || gate.blocked;
    },
    /**
     * true once after a context loss with a manual level above low: the ride screen shows the
     * hint to pick a lower level (rule 4). Reading it clears it.
     */
    takeGraphicsHint() {
      const pending = graphicsHintPending;
      graphicsHintPending = false;
      return pending;
    },
    /** Values for the `?debug` overlay; the same object each time (do not keep it). */
    diagnostics,
    /** Starts (fn) or stops (null) the frame loop; the canvas is visible only while it runs. */
    run(fn) {
      frameFn = fn;
      last = 0;
      canvas.hidden = !fn;
      if (fn) {
        // Nothing of the ride is on screen yet, so this is the time to
        // - finish a switch that was still in stages,
        // - fit the level to the window (its size may have changed since the level was applied),
        // - give the textures the anisotropy of the level (an upload, never done in the middle of
        //   a ride, see world.syncAnisotropy).
        if (planQualityStagesFromState(applied, fitFor(level)).length > 0) {
          applyAllNow();
          precompile();
        }
        guarded('anisotropy', () => world.syncAnisotropy(targetPreset));
      }
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
