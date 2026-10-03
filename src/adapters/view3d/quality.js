// Graphics quality levels (concept rules 3, 4): device pick, downgrade governor and presets.
// Pure, no three.js.
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';

export const QUALITY_LEVELS = GRAPHICS_LEVELS;

export const QUALITY_PRESETS = Object.freeze({
  low: Object.freeze({
    pixelRatio: 1,
    shadows: false,
    shadowMapSize: 0,
    material: 'lambert',
    fog: null,
    envMap: false,
    // share of environment instances (trees, bushes, distant forest)
    envDensity: 0.2,
    envDetail: 'low',
    grassTufts: 0,
    anisotropy: 1,
    normalMaps: false,
    windyGrass: false,
  }),
  medium: Object.freeze({
    pixelRatio: 1.5,
    shadows: true,
    shadowMapSize: 1024,
    // only horse/obstacles cast shadows
    shadowCasters: 'obstacles',
    material: 'standard',
    fog: Object.freeze({ near: 120, far: 520 }),
    envMap: true,
    envDensity: 0.55,
    envDetail: 'high',
    grassTufts: 0,
    anisotropy: 4,
    normalMaps: true,
    windyGrass: false,
  }),
  high: Object.freeze({
    pixelRatio: 2,
    shadows: true,
    shadowMapSize: 2048,
    shadowCasters: 'all',
    material: 'standard',
    fog: Object.freeze({ near: 90, far: 480 }),
    envMap: true,
    envDensity: 1,
    envDetail: 'high',
    grassTufts: 1,
    anisotropy: 8,
    normalMaps: true,
    windyGrass: true,
  }),
});

const SOFTWARE_RENDERER = /swiftshader|llvmpipe|softpipe|software|basic render|mesa offscreen/i;
const WEAK_GPU =
  /intel.*(hd|uhd)\s*graphics|mali-[gt]?[0-7]\d\b|adreno.*\b[1-5]\d\d\b|powervr|videocore/i;
const STRONG_GPU = /nvidia|geforce|rtx|radeon\s*(rx|pro)|apple m[1-9]|iris xe|arc\b/i;

/**
 * Initial level for the device (rule 4: "automatic" picks on first start).
 * info: { hardwareConcurrency, deviceMemory, isTouch, rendererString, screenPixels }
 */
export function pickInitialLevel(info = {}) {
  const cores = Number(info.hardwareConcurrency) || 4;
  const memory = Number(info.deviceMemory) || null; // only Chrome/Edge report this
  const renderer = String(info.rendererString || '');
  const pixels = Number(info.screenPixels) || 1920 * 1080;

  if (SOFTWARE_RENDERER.test(renderer)) return 'low';
  if (cores <= 2 || (memory !== null && memory <= 2)) return 'low';

  if (info.isTouch) {
    // tablets/phones: at most medium; weak devices low
    if (cores <= 4 || (memory !== null && memory <= 4) || WEAK_GPU.test(renderer)) return 'low';
    return 'medium';
  }

  if (WEAK_GPU.test(renderer)) return pixels > 2560 * 1440 ? 'low' : 'medium';
  if (cores >= 8 && (memory === null || memory >= 8)) return 'high';
  if (STRONG_GPU.test(renderer) && cores >= 6) return 'high';
  return 'medium';
}

/** Next lower level (never below low). */
export function lowerLevel(level) {
  const i = QUALITY_LEVELS.indexOf(level);
  return QUALITY_LEVELS[Math.max(0, i - 1)];
}

export const GOVERNOR_DEFAULTS = Object.freeze({
  windowS: 5, // moving average
  minFps: 50,
  graceS: 3, // grace period after an interruption
  cooldownS: 10, // minimum time between adjustments
  maxFrameS: 0.5, // longer frame = interruption
});

/**
 * Downgrade governor according to rule 4.
 * frame(dtSeconds, measuring): measuring = the player is riding (pre-start, ride, free mode) and
 * the window is visible. Returns the current level.
 * now: optional clock in ms; only used when frame() is called without dt.
 */
export function createQualityGovernor({
  level = 'medium',
  auto = true,
  onChange = () => {},
  now = null,
  options = {},
} = {}) {
  const cfg = { ...GOVERNOR_DEFAULTS, ...options };
  let current = QUALITY_LEVELS.includes(level) ? level : 'medium';
  let isAuto = Boolean(auto);
  let grace = cfg.graceS;
  let cooldown = 0;
  let lastNow = null;
  // queue of measured frame durations
  let samples = [];
  let head = 0;
  let sum = 0;

  function clearWindow() {
    samples = [];
    head = 0;
    sum = 0;
  }

  function interrupt() {
    clearWindow();
    grace = cfg.graceS;
  }

  function windowLength() {
    return samples.length - head;
  }

  function push(dt) {
    samples.push(dt);
    sum += dt;
    // drop old frames while the rest still covers the whole window
    while (windowLength() > 1 && sum - samples[head] >= cfg.windowS) {
      sum -= samples[head];
      head += 1;
    }
    if (head > 512) {
      samples = samples.slice(head);
      head = 0;
    }
  }

  function averageFps() {
    const n = windowLength();
    return n > 0 && sum > 0 ? n / sum : null;
  }

  function frame(dtSeconds, measuring = true) {
    let dt = dtSeconds;
    if (dt === undefined || dt === null) {
      if (typeof now !== 'function') return current;
      const t = now();
      dt = lastNow === null ? 0 : (t - lastNow) / 1000;
      lastNow = t;
    }
    if (!(dt >= 0)) return current;
    if (cooldown > 0) cooldown = Math.max(0, cooldown - dt);
    if (!isAuto) return current;

    if (!measuring || dt > cfg.maxFrameS) {
      interrupt();
      return current;
    }
    if (grace > 0) {
      grace -= dt;
      return current;
    }
    push(dt);
    if (sum < cfg.windowS - 1e-9) return current;
    if (cooldown > 0 || current === 'low') return current;

    const fps = averageFps();
    if (fps !== null && fps < cfg.minFps) {
      current = lowerLevel(current);
      cooldown = cfg.cooldownS;
      clearWindow();
      onChange(current);
    }
    return current;
  }

  return {
    frame,
    /** Report an interruption (pause, menu, hidden tab). */
    interrupt,
    /** Set a level manually (no onChange). */
    setLevel(next) {
      if (QUALITY_LEVELS.includes(next)) current = next;
      interrupt();
    },
    setAuto(value) {
      isAuto = Boolean(value);
      interrupt();
    },
    get level() {
      return current;
    },
    get auto() {
      return isAuto;
    },
    get averageFps() {
      return averageFps();
    },
  };
}
