// Graphics quality levels (concept rules 3, 4): device pick, downgrade governor and presets.
// Pure, no three.js.
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';

export const QUALITY_PRESETS = Object.freeze({
  low: Object.freeze({
    level: 'low',
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
    // geometry detail and material type of horse and rider
    characterDetail: 'low',
    // share of the grass tufts, of the meadow flowers and of the bunting (SRT-011); low gets none
    // of these: it keeps the draw calls and triangles it had before
    grassTufts: 0,
    flowers: 0,
    decor: 0,
    // share of the birds and butterflies in the sky and over the flowers
    birds: 0,
    butterflies: 0,
    anisotropy: 1,
    normalMaps: false,
    // trees, bushes, grass and flowers sway in the wind (vertex shader)
    wind: false,
  }),
  medium: Object.freeze({
    level: 'medium',
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
    characterDetail: 'medium',
    grassTufts: 0.5,
    flowers: 0.45,
    decor: 0.5,
    birds: 0.5,
    butterflies: 0,
    anisotropy: 4,
    normalMaps: true,
    wind: true,
  }),
  high: Object.freeze({
    level: 'high',
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
    characterDetail: 'high',
    grassTufts: 1,
    flowers: 1,
    decor: 1,
    birds: 1,
    butterflies: 1,
    anisotropy: 8,
    normalMaps: true,
    wind: true,
  }),
});

/** The preset of a level name; a preset object (e.g. one fitted to the budget) passes through. */
export function presetFor(levelOrPreset) {
  if (typeof levelOrPreset === 'string') return QUALITY_PRESETS[levelOrPreset];
  return levelOrPreset && typeof levelOrPreset === 'object' ? levelOrPreset : undefined;
}

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

// ---------------------------------------------------------------------------------------------
// GPU memory budget (rule 4). Browsers do not tell how much GPU memory a page may use, so the
// memory a level needs is estimated before the level is applied and compared with a conservative
// budget per device; if it does not fit, the level keeps its look and gets a lower resolution
// (and, as a last resort, smaller shadow map and less scenery). Technical values, grouped here and
// not in tuning.js: they model the hardware, not game play. They are deliberately on the safe
// side; the `?debug` box shows estimate and budget so that they can be tuned on a real device.

const MIB = 1024 * 1024;

const GPU_MEMORY_MODEL = Object.freeze({
  // Default framebuffer: RGBA8 colour and 24-bit depth (stencil is off, so 4 bytes per pixel with
  // padding). The browser keeps two colour buffers (the one that is shown and the one that is
  // drawn). With `antialias: true` the browser also allocates multisampled colour and depth
  // renderbuffers (MSAA, 4 samples is what mobile GPUs offer: MAX_SAMPLES is 4 on most of them) and
  // resolves into the colour buffer.
  bytesColor: 4,
  bytesDepth: 4,
  swapBuffers: 2,
  msaaSamples: 4,
  // three.js r186 WebGLShadowMap (PCF): an RGBA8 colour target plus a 32-bit depth texture
  shadowBytesPerTexel: 8,
  // PMREM target of the environment map (r186 PMREMGenerator, 256 px cube): cubeUV layout of
  // 3 × 256 by 4 × 256 texels in half float RGBA (8 bytes)
  envMapMB: (3 * 256 * 4 * 256 * 8) / MIB,
  // an RGBA8 texture with a full mipmap chain takes 4/3 of its base level
  textureBytesPerTexel: 4,
  textureMipFactor: 4 / 3,
  // geometry and instance buffers, shader programs, the skinned horse, the compositor's share
  baselineMB: 16,
  // instance matrices/colours and LOD geometry of the scenery at full density, of the 11 000 grass
  // tufts (76 bytes each, with a safety factor) and of the 3 600 meadow flowers
  sceneryFullMB: 2,
  tuftMB: 2,
  flowerMB: 0.5,
  // bunting, flower pots, paddock fence and props, flower boxes at the jumps (at full decor)
  decorMB: 0.5,
  // birds and butterflies: a few dozen instances
  wildlifeMB: 0.1,
  // the pixel ratio is lowered in these steps; the shadow map and the scenery have floors
  ratioStep: 0.05,
  shadowMapFloor: 1024,
  envDensityFloor: 0.55,
});

/** Textures of the world today (grass colour, sand colour, sand normal map), 512² each. */
const DEFAULT_TEXTURES = Object.freeze([
  Object.freeze({ width: 512, height: 512, normal: false }),
  Object.freeze({ width: 512, height: 512, normal: false }),
  Object.freeze({ width: 512, height: 512, normal: true }),
]);

// Budget per device class (MiB). navigator.deviceMemory is rounded to 0.25 … 8 GiB and reported by
// Chrome/Edge only (https://developer.mozilla.org/en-US/docs/Web/API/Navigator/deviceMemory), so a
// desktop with 32 GiB also says 8 and Firefox/Safari say nothing. Phones and tablets share their
// memory with the system and get a small share (Chrome and Firefox on Android lose the context
// or the tab when the GPU process grows too far); a weak GPU gets less again.
const GPU_BUDGET_MODEL = Object.freeze({
  touch: Object.freeze({ perGiB: 40, min: 96, max: 320, unknown: 160 }),
  desktop: Object.freeze({ perGiB: 64, min: 256, max: 1024, unknown: 512 }),
  weakGpuFactor: 0.75,
});

/**
 * GPU memory (MiB) a level needs, estimated from the drawing buffer, shadow map, environment map,
 * textures and scenery (trees, grass tufts, flowers, decoration and animals scale with the preset's
 * `envDensity`, `grassTufts`, `flowers`, `decor`, `birds` and `butterflies`).
 * ctx: { cssWidth, cssHeight, devicePixelRatio, pixelRatio (cap, default: the preset's),
 *   antialias (of the real context, default: the preset's), textures: [{ width, height, normal }] }
 */
export function estimateGpuMemoryMB(
  preset,
  {
    cssWidth,
    cssHeight,
    devicePixelRatio = 1,
    pixelRatio,
    antialias = preset.antialias,
    textures = DEFAULT_TEXTURES,
  } = {},
) {
  const m = GPU_MEMORY_MODEL;
  const ratio = Math.min(devicePixelRatio, pixelRatio ?? preset.pixelRatio);
  const pixels =
    Math.max(1, Math.round(cssWidth * ratio)) * Math.max(1, Math.round(cssHeight * ratio));
  const bytesPerPixel = antialias
    ? m.msaaSamples * (m.bytesColor + m.bytesDepth) + m.bytesColor * m.swapBuffers
    : m.bytesColor * m.swapBuffers + m.bytesDepth;
  let bytes = pixels * bytesPerPixel;
  if (preset.shadows) bytes += preset.shadowMapSize * preset.shadowMapSize * m.shadowBytesPerTexel;
  if (preset.envMap) bytes += m.envMapMB * MIB;
  for (const t of textures) {
    if (t.normal && !preset.normalMaps) continue; // only uploaded when a material uses it
    bytes += t.width * t.height * m.textureBytesPerTexel * m.textureMipFactor;
  }
  return bytes / MIB + m.baselineMB + sceneryMB(preset);
}

/** Instance buffers and extra geometry of the scenery details of a preset (MiB). */
function sceneryMB(preset) {
  const m = GPU_MEMORY_MODEL;
  const wildlife = Math.max(preset.birds ?? 0, preset.butterflies ?? 0);
  return (
    preset.envDensity * m.sceneryFullMB +
    preset.grassTufts * m.tuftMB +
    (preset.flowers ?? 0) * m.flowerMB +
    (preset.decor ?? 0) * m.decorMB +
    wildlife * m.wildlifeMB
  );
}

/**
 * Conservative GPU memory budget (MiB) of a device.
 * info: { deviceMemory (GiB, may be missing), isTouch, rendererString }
 */
export function gpuBudgetMB({ deviceMemory, isTouch, rendererString } = {}) {
  const model = isTouch ? GPU_BUDGET_MODEL.touch : GPU_BUDGET_MODEL.desktop;
  const memory = Number(deviceMemory) > 0 ? Number(deviceMemory) : null;
  let budget =
    memory === null
      ? model.unknown
      : Math.min(model.max, Math.max(model.min, memory * model.perGiB));
  if (WEAK_GPU.test(String(rendererString || ''))) budget *= GPU_BUDGET_MODEL.weakGpuFactor;
  return Math.round(budget);
}

const roundDownTo = (value, step) => Number((Math.floor(value / step + 1e-9) * step).toFixed(2));

/**
 * Fits a preset into the budget. When the estimate is too high, the levers are used in this order
 * until it fits: pixel ratio (down to 1), shadow map size (down to 1024), then grass tufts, flowers
 * and the density of the scenery. The level's look otherwise stays ("high" keeps its effects at a lower
 * resolution).
 * Returns { preset (the same object when nothing changes), estimateMB, requestedMB, budgetMB,
 * fits, capped: { pixelRatio: { from, to } | null, shadowMapSize: { from, to } | null,
 * scenery: boolean } }.
 */
export function fitPresetToBudget(preset, ctx, budgetMB) {
  const m = GPU_MEMORY_MODEL;
  const dpr = ctx.devicePixelRatio ?? 1;
  const estimate = (p) => estimateGpuMemoryMB(p, ctx);
  const requestedMB = estimate(preset);
  const capped = { pixelRatio: null, shadowMapSize: null, scenery: false };
  if (requestedMB <= budgetMB) {
    return { preset, estimateMB: requestedMB, requestedMB, budgetMB, fits: true, capped };
  }
  const fitted = { ...preset };

  // 1. resolution: the biggest lever, no visible loss of effects
  const wanted = Math.min(dpr, preset.pixelRatio);
  const floor = Math.min(1, dpr);
  if (wanted > floor) {
    let ratio = floor;
    if (estimate({ ...fitted, pixelRatio: floor }) <= budgetMB) {
      let lo = floor; // fits
      let hi = wanted; // does not fit
      for (let i = 0; i < 24; i += 1) {
        const mid = (lo + hi) / 2;
        if (estimate({ ...fitted, pixelRatio: mid }) <= budgetMB) lo = mid;
        else hi = mid;
      }
      ratio = Math.max(floor, roundDownTo(lo, m.ratioStep));
    }
    fitted.pixelRatio = ratio;
    capped.pixelRatio = { from: wanted, to: ratio };
  }

  // 2. shadow map size
  if (estimate(fitted) > budgetMB && fitted.shadows && fitted.shadowMapSize > m.shadowMapFloor) {
    capped.shadowMapSize = { from: fitted.shadowMapSize, to: m.shadowMapFloor };
    fitted.shadowMapSize = m.shadowMapFloor;
  }

  // 3. scenery: tufts and flowers first, then the density of trees and bushes
  if (estimate(fitted) > budgetMB && fitted.grassTufts > 0) {
    fitted.grassTufts = 0;
    capped.scenery = true;
  }
  if (estimate(fitted) > budgetMB && fitted.flowers > 0) {
    fitted.flowers = 0;
    capped.scenery = true;
  }
  if (estimate(fitted) > budgetMB && fitted.envDensity > m.envDensityFloor) {
    fitted.envDensity = m.envDensityFloor;
    capped.scenery = true;
  }

  const estimateMB = estimate(fitted);
  return {
    preset: Object.freeze(fitted),
    estimateMB,
    requestedMB,
    budgetMB,
    fits: estimateMB <= budgetMB,
    capped,
  };
}

/**
 * Antialiasing is a context attribute and cannot change later, so it is decided when the renderer
 * is created: only if the level wants it and the budget can carry the multisampled buffers even
 * with every other lever used up (a context without antialiasing is the last resort).
 * ctx: like fitPresetToBudget, without `antialias`.
 */
export function chooseAntialias(preset, ctx, budgetMB) {
  if (!preset.antialias) return false;
  return fitPresetToBudget(preset, { ...ctx, antialias: true }, budgetMB).fits;
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

// A context loss within this many seconds of the page going to the background or coming back is
// not counted as overload (technical value, no game play): Android browsers often drop the
// context on an app switch, and right after the return the page is still waking up.
export const CONTEXT_LOSS_GRACE_S = 3;

/**
 * What a lost WebGL context means for the graphics level (rule 4). A loss in the foreground shows
 * that the device is overloaded, so the level has to go down: with "Automatic" on it goes to low
 * (and is saved, the automatic stays on); a manually chosen level stays, the player only gets the
 * hint to pick a lower one. A loss while the page is hidden, or within CONTEXT_LOSS_GRACE_S of a
 * visibility change, says nothing about the device: nothing changes then.
 * `visible`: the page is in the foreground; `sinceVisibilityChangeS`: seconds since it last went
 * to the background or came back (Infinity: never). Returns { level, persist, hint }.
 */
export function levelAfterContextLoss({
  auto,
  level,
  visible = true,
  sinceVisibilityChangeS = Infinity,
}) {
  if (!visible || sinceVisibilityChangeS < CONTEXT_LOSS_GRACE_S) {
    return { level, persist: false, hint: false };
  }
  const lowered = lowerLevel(level) !== level;
  if (auto) return { level: 'low', persist: lowered, hint: false };
  return { level, persist: false, hint: canHintLowerLevel({ auto, level }) };
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
