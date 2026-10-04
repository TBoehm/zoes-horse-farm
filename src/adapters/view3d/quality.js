// Graphics quality levels (concept rules 3, 4): device pick, downgrade governor and presets.
// Pure, no three.js.
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';

export const QUALITY_PRESETS = Object.freeze({
  low: Object.freeze({
    pixelRatio: 1,
    // context attribute: only fixed when the renderer is created (see engine.js)
    antialias: false,
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
    antialias: true,
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
    antialias: true,
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
  const i = GRAPHICS_LEVELS.indexOf(level);
  return GRAPHICS_LEVELS[Math.max(0, i - 1)];
}

// Measuring values of the frame-rate checks below. They are no game-play values (those belong to
// TUNING), so they stay here: the downgrade governor and the "level too high" hint measure the
// same way (rule 4): only while riding, not in the first 3 s, and a frame longer than
// maxFrameS is a real interruption (suspend). Slower frames still count: a very slow device must
// be able to step down. Pauses/hidden tabs are reported via measuring=false.
const GOVERNOR_DEFAULTS = Object.freeze({
  windowS: 5, // moving average
  minFps: 50,
  graceS: 3, // grace period after an interruption
  cooldownS: 10, // minimum time between adjustments
  maxFrameS: 2,
});

const LOW_FPS_HINT_DEFAULTS = Object.freeze({
  windowS: GOVERNOR_DEFAULTS.windowS,
  graceS: GOVERNOR_DEFAULTS.graceS,
  maxFrameS: GOVERNOR_DEFAULTS.maxFrameS,
  maxFps: 30, // a manually chosen level below this average gets a hint
});

/** Moving average over the last `windowS` seconds of frame durations. */
function createFrameWindow(windowS) {
  // queue of measured frame durations
  let samples = [];
  let head = 0;
  let sum = 0;
  const length = () => samples.length - head;
  return {
    clear() {
      samples.length = 0; // in place: this is called every frame while nothing is measured
      head = 0;
      sum = 0;
    },
    push(dt) {
      samples.push(dt);
      sum += dt;
      // drop old frames while the rest still covers the whole window
      while (length() > 1 && sum - samples[head] >= windowS) {
        sum -= samples[head];
        head += 1;
      }
      if (head > 512) {
        samples = samples.slice(head);
        head = 0;
      }
    },
    /** Does the collected time cover the whole window? */
    get full() {
      return sum >= windowS - 1e-9;
    },
    averageFps() {
      const n = length();
      return n > 0 && sum > 0 ? n / sum : null;
    },
  };
}

/** Frame duration from the argument or from the clock; null if it cannot be determined. */
function createFrameClock(now) {
  let lastNow = null;
  return (dtSeconds) => {
    if (dtSeconds !== undefined && dtSeconds !== null) return dtSeconds;
    if (typeof now !== 'function') return null;
    const t = now();
    const dt = lastNow === null ? 0 : (t - lastNow) / 1000;
    lastNow = t;
    return dt;
  };
}

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
  let current = GRAPHICS_LEVELS.includes(level) ? level : 'medium';
  let isAuto = Boolean(auto);
  let grace = cfg.graceS;
  let cooldown = 0;
  const frames = createFrameWindow(cfg.windowS);
  const frameSeconds = createFrameClock(now);

  function interrupt() {
    frames.clear();
    grace = cfg.graceS;
  }

  function frame(dtSeconds, measuring = true) {
    const dt = frameSeconds(dtSeconds);
    if (dt === null) return current;
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
    frames.push(dt);
    if (!frames.full) return current;
    if (cooldown > 0 || current === 'low') return current;

    const fps = frames.averageFps();
    if (fps !== null && fps < cfg.minFps) {
      current = lowerLevel(current);
      cooldown = cfg.cooldownS;
      frames.clear();
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
      if (GRAPHICS_LEVELS.includes(next)) current = next;
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
      return frames.averageFps();
    },
  };
}

/**
 * Does the "level too high" hint make sense? Only for a manual level that has a lower one to pick:
 * with "Automatic" on the governor steps down by itself, and at "low" the hint would send the
 * player to a level that does not exist.
 */
export function canHintLowerLevel({ auto, level }) {
  return !auto && lowerLevel(level) !== level;
}

/**
 * Hint for a manually chosen level that is too high for the device (rule 4): the level stays, the
 * player only gets told. Create one instance per ride or free-mode session: it fires at most once
 * (until reset()).
 * frame(dtSeconds, measuring) returns true exactly once, when the average over the window is below
 * the limit. Pass measuring=false while paused, hidden, in menus or with "Automatic" on (the
 * governor takes care of that case).
 */
export function createLowFpsHint({ options = {} } = {}) {
  const cfg = { ...LOW_FPS_HINT_DEFAULTS, ...options };
  let grace = cfg.graceS;
  let shown = false;
  const frames = createFrameWindow(cfg.windowS);

  function interrupt() {
    frames.clear();
    grace = cfg.graceS;
  }

  return {
    frame(dtSeconds, measuring = true) {
      if (shown || !(dtSeconds >= 0)) return false;
      if (!measuring || dtSeconds > cfg.maxFrameS) {
        interrupt();
        return false;
      }
      if (grace > 0) {
        grace -= dtSeconds;
        return false;
      }
      frames.push(dtSeconds);
      if (!frames.full) return false;
      const fps = frames.averageFps();
      if (fps === null || fps >= cfg.maxFps) return false;
      shown = true;
      return true;
    },
    /** Report an interruption (pause, menu, hidden tab, level change). */
    interrupt,
    /** A new ride begins (e.g. "Start again"): the hint may show once more. */
    reset() {
      shown = false;
      interrupt();
    },
  };
}
