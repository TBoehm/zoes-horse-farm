// 3D engine: one renderer, one world, one horse with rider, one camera – shared across all rides.
import * as THREE from 'three';
import { h } from '../ui/dom.js';
import { createRenderer, resizeRenderer, setMaxPixelRatio } from './renderer.js';
import { createQualityGovernor, pickInitialLevel, QUALITY_PRESETS } from './quality.js';
import { createWorld } from './world.js';
import { createHorse } from '../../game/view/horse/index.js';
import { createCameraRig } from './camera.js';

function rendererString(renderer) {
  try {
    const gl = renderer.getContext();
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return String(gl.getParameter(ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
  } catch {
    return '';
  }
}

export function deviceInfo(renderer, inputMode) {
  return {
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemory: navigator.deviceMemory,
    isTouch: inputMode?.device === 'touch',
    rendererString: rendererString(renderer),
    screenPixels: screen.width * screen.height * (window.devicePixelRatio || 1) ** 2,
  };
}

export function createEngine({ app, store, inputMode }) {
  const canvas = h('canvas', { class: 'scene-canvas', 'aria-hidden': 'true' });
  canvas.hidden = true;
  app.layers.scene.append(canvas);

  const renderer = createRenderer(canvas, { antialias: true });
  const camera = new THREE.PerspectiveCamera(58, 16 / 9, 0.1, 900);
  const cameraRig = createCameraRig(camera);

  // Graphics level (rule 4): on first start, or on "Automatic" without a level, pick one that fits the device
  let settings = store.get('settings');
  if (!settings.graphicsLevel) {
    const level = pickInitialLevel(deviceInfo(renderer, inputMode));
    settings = store.update('settings', (s) => ({ ...s, graphicsLevel: level }));
  }
  let level = settings.graphicsLevel;

  const world = createWorld(renderer, { quality: level });
  const horse = createHorse({ coat: 'bay', marking: 'star', quality: level });
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
      store.update('settings', (s) => ({ ...s, graphicsLevel: next }));
    },
  });

  store.onChange('settings', (s) => {
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
    const dt = last ? Math.min(0.1, (time - last) / 1000) : 1 / 60;
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
