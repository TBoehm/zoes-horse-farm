// 3D engine: one renderer, one world, one horse with rider, one camera – shared across all rides.
import * as THREE from 'three';
import { h } from '../ui/dom.js';
import { createRenderer, resizeRenderer, setMaxPixelRatio } from './renderer.js';
import { createQualityGovernor, pickInitialLevel, QUALITY_PRESETS } from './quality.js';
import { createWorld } from './world.js';
import { TUNING } from '../../domain/sim/tuning.js';
import { DEFAULT_APPEARANCE } from '../../domain/horse/appearance.js';
import { createHorse } from './horse/index.js';
import { createCameraRig } from './camera.js';

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

  const world = createWorld(renderer, { quality: level });
  const horse = createHorse({ ...DEFAULT_APPEARANCE, quality: level });
  world.scene.add(horse.object);

  function applyQuality(next) {
    level = next;
    setMaxPixelRatio(renderer, QUALITY_PRESETS[next].pixelRatio);
    world.setQuality(next);
    horse.setQuality(next);
    resize(true);
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
    if (s.graphicsAuto !== governor.auto) governor.setAuto(s.graphicsAuto);
    if (s.graphicsLevel !== level) {
      governor.setLevel(s.graphicsLevel);
      applyQuality(s.graphicsLevel);
    }
  });

  function resize(force = false) {
    const w = canvas.clientWidth || window.innerWidth;
    const hgt = canvas.clientHeight || window.innerHeight;
    if (resizeRenderer(renderer, camera, w, hgt) || force) {
      camera.aspect = w / hgt;
      camera.updateProjectionMatrix();
    }
  }
  window.addEventListener('resize', () => resize());
  applyQuality(level);

  let frameFn = null;
  let last = 0;
  function loop(time) {
    const dt = last ? Math.min(TUNING.sim.maxDt, (time - last) / 1000) : 1 / 60;
    const rawDt = last ? (time - last) / 1000 : 1 / 60;
    last = time;
    resize();
    frameFn?.(dt, rawDt);
    renderer.render(world.scene, camera);
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
    /** Starts (fn) or stops (null) the frame loop; the canvas is visible only while it runs. */
    run(fn) {
      frameFn = fn;
      last = 0;
      canvas.hidden = !fn;
      renderer.setAnimationLoop(fn ? loop : null);
      if (fn) resize(true);
    },
    renderOnce() {
      renderer.render(world.scene, camera);
    },
  };
}

/** Create the engine once per app (only when it is needed). */
export function getEngine(ctx) {
  if (!ctx.services.engine) ctx.services.engine = createEngine(ctx);
  return ctx.services.engine;
}
