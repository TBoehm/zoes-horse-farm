// Grafikstufen (Konzept Regeln 3, 4): Gerätewahl, Abwärts-Automatik und Voreinstellungen.
// Rein, ohne three.js.

export const QUALITY_LEVELS = Object.freeze(['low', 'medium', 'high']);

export const QUALITY_PRESETS = Object.freeze({
  low: Object.freeze({
    pixelRatio: 1,
    shadows: false,
    shadowMapSize: 0,
    material: 'lambert',
    fog: null,
    envMap: false,
    // Anteil der Umgebungs-Instanzen (Bäume, Büsche, Fernwald)
    envDensity: 0.2,
    grassTufts: 0,
    anisotropy: 1,
    normalMaps: false,
    windyGrass: false,
  }),
  medium: Object.freeze({
    pixelRatio: 1.5,
    shadows: true,
    shadowMapSize: 1024,
    // nur Pferd/Hindernisse werfen Schatten
    shadowCasters: 'obstacles',
    material: 'standard',
    fog: Object.freeze({ near: 120, far: 520 }),
    envMap: true,
    envDensity: 0.55,
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
 * Startstufe passend zum Gerät (Regel 4: „Automatisch" wählt beim ersten Start).
 * info: { hardwareConcurrency, deviceMemory, isTouch, rendererString, screenPixels }
 */
export function pickInitialLevel(info = {}) {
  const cores = Number(info.hardwareConcurrency) || 4;
  const memory = Number(info.deviceMemory) || null; // nur Chrome/Edge melden das
  const renderer = String(info.rendererString || '');
  const pixels = Number(info.screenPixels) || 1920 * 1080;

  if (SOFTWARE_RENDERER.test(renderer)) return 'low';
  if (cores <= 2 || (memory !== null && memory <= 2)) return 'low';

  if (info.isTouch) {
    // Tablets/Handys: höchstens Mittel; schwache Geräte Niedrig
    if (cores <= 4 || (memory !== null && memory <= 4) || WEAK_GPU.test(renderer)) return 'low';
    return 'medium';
  }

  if (WEAK_GPU.test(renderer)) return pixels > 2560 * 1440 ? 'low' : 'medium';
  if (cores >= 8 && (memory === null || memory >= 8)) return 'high';
  if (STRONG_GPU.test(renderer) && cores >= 6) return 'high';
  return 'medium';
}

/** Nächstniedrigere Stufe (nie unter low). */
export function lowerLevel(level) {
  const i = QUALITY_LEVELS.indexOf(level);
  return QUALITY_LEVELS[Math.max(0, i - 1)];
}

export const GOVERNOR_DEFAULTS = Object.freeze({
  windowS: 5, // gleitender Durchschnitt
  minFps: 50,
  graceS: 3, // Karenz nach Unterbrechung
  cooldownS: 10, // Mindestabstand zwischen Anpassungen
  maxFrameS: 0.5, // längerer Frame = Unterbrechung
});

/**
 * Abwärts-Automatik nach Regel 4.
 * frame(dtSeconds, measuring): measuring = es wird gerade geritten (Vorstart, Ritt, freier Modus)
 * und das Fenster ist sichtbar. Liefert die aktuelle Stufe.
 * now: optionale Zeitquelle in ms; wird nur genutzt, wenn frame() ohne dt aufgerufen wird.
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
  // Ringpuffer der gemessenen Frame-Dauern
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
    // ältere Frames entfernen, solange der Rest noch das ganze Fenster abdeckt
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
    /** Unterbrechung melden (Pause, Menü, Tab versteckt). */
    interrupt,
    /** Manuelle Stufe setzen (ohne onChange). */
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
