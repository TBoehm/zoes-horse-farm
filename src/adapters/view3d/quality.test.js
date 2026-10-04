import { describe, it, expect, vi } from 'vitest';
import {
  canHintLowerLevel,
  chooseAntialias,
  estimateGpuMemoryMB,
  fitPresetToBudget,
  gpuBudgetMB,
  presetFor,
  levelAfterContextLoss,
  CONTEXT_LOSS_GRACE_S,
  createLowFpsHint,
  createQualityGovernor,
  pickInitialLevel,
  lowerLevel,
  QUALITY_PRESETS,
} from './quality.js';
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';

/** Runs the governor for `seconds` at a constant frame rate. */
function run(gov, seconds, fps, measuring = true) {
  const dt = 1 / fps;
  const n = Math.round(seconds * fps);
  for (let i = 0; i < n; i += 1) gov.frame(dt, measuring);
}

describe('pickInitialLevel', () => {
  it('picks low for software renderers', () => {
    for (const r of [
      'Google SwiftShader',
      'ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)',
      'llvmpipe (LLVM 15.0.7, 256 bits)',
      'Microsoft Basic Render Driver',
    ]) {
      expect(
        pickInitialLevel({ hardwareConcurrency: 16, deviceMemory: 8, rendererString: r }),
      ).toBe('low');
    }
  });

  it('picks high for a strong desktop', () => {
    expect(
      pickInitialLevel({
        hardwareConcurrency: 12,
        deviceMemory: 8,
        isTouch: false,
        rendererString: 'ANGLE (NVIDIA, NVIDIA GeForce RTX 3060)',
        screenPixels: 2560 * 1440,
      }),
    ).toBe('high');
  });

  it('touch devices get at most medium, weak ones low', () => {
    expect(
      pickInitialLevel({
        hardwareConcurrency: 8,
        deviceMemory: 8,
        isTouch: true,
        rendererString: 'Apple GPU',
      }),
    ).toBe('medium');
    expect(
      pickInitialLevel({ hardwareConcurrency: 4, isTouch: true, rendererString: 'Mali-G52' }),
    ).toBe('low');
  });

  it('weak desktop gets medium or low', () => {
    expect(
      pickInitialLevel({ hardwareConcurrency: 4, rendererString: 'Intel(R) UHD Graphics 620' }),
    ).toBe('medium');
    expect(pickInitialLevel({ hardwareConcurrency: 2 })).toBe('low');
    expect(pickInitialLevel({})).toBe('medium');
  });
});

describe('lowerLevel', () => {
  it('goes down one level, never below low', () => {
    expect(lowerLevel('high')).toBe('medium');
    expect(lowerLevel('medium')).toBe('low');
    expect(lowerLevel('low')).toBe('low');
  });
});

describe('QUALITY_PRESETS', () => {
  it('has all levels with pixel ratio and shadow values', () => {
    expect(Object.keys(QUALITY_PRESETS)).toEqual(GRAPHICS_LEVELS);
    expect(QUALITY_PRESETS.low.pixelRatio).toBe(1);
    expect(QUALITY_PRESETS.medium.pixelRatio).toBe(1.5);
    expect(QUALITY_PRESETS.high.pixelRatio).toBe(2);
    expect(QUALITY_PRESETS.low.shadows).toBe(false);
    expect(QUALITY_PRESETS.medium.shadowMapSize).toBe(1024);
    expect(QUALITY_PRESETS.high.shadowMapSize).toBe(2048);
    expect(QUALITY_PRESETS.low.material).toBe('lambert');
  });

  it('adds the details of SRT-011 level by level: none on low, the most on high', () => {
    const { low, medium, high } = QUALITY_PRESETS;
    for (const key of ['grassTufts', 'flowers', 'decor', 'birds', 'butterflies']) {
      expect(low[key], `low ${key}`).toBe(0);
      expect(medium[key], `medium ${key}`).toBeLessThanOrEqual(high[key]);
      expect(high[key], `high ${key}`).toBeGreaterThan(0);
    }
    expect(low.wind).toBe(false);
    expect(medium.wind).toBe(true);
    expect(high.wind).toBe(true);
    // moderate flowers and birds on medium, butterflies only on high
    expect(medium.flowers).toBeGreaterThan(0);
    expect(medium.flowers).toBeLessThan(high.flowers);
    // the grass tufts are the biggest triangle cost: medium has none (ticket SRT-011)
    expect(medium.grassTufts).toBe(0);
    expect(medium.birds).toBeGreaterThan(0);
    expect(medium.butterflies).toBe(0);
    expect(high.butterflies).toBeGreaterThan(0);
  });

  it('antialiasing is off on low and on above', () => {
    expect(QUALITY_PRESETS.low.antialias).toBe(false);
    expect(QUALITY_PRESETS.medium.antialias).toBe(true);
    expect(QUALITY_PRESETS.high.antialias).toBe(true);
  });
});

describe('createQualityGovernor', () => {
  it('downgrades one level after 3 s grace + 5 s below 50 fps', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', auto: true, onChange });
    run(gov, 3, 40); // grace period
    expect(gov.level).toBe('high');
    run(gov, 4.9, 40);
    expect(gov.level).toBe('high');
    run(gov, 0.2, 40);
    expect(gov.level).toBe('medium');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('medium');
  });

  it('stays at 50 fps or more', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 60, 50);
    run(gov, 60, 60);
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('waits at least 10 s after an adjustment', () => {
    const changes = [];
    const gov = createQualityGovernor({ level: 'high', onChange: (l) => changes.push(l) });
    let t = 0;
    const dt = 1 / 30;
    const times = [];
    for (let i = 0; i < 30 * 40; i += 1) {
      t += dt;
      const before = gov.level;
      gov.frame(dt, true);
      if (gov.level !== before) times.push(t);
    }
    expect(changes).toEqual(['medium', 'low']);
    expect(times[1] - times[0]).toBeGreaterThanOrEqual(10 - 1e-6);
  });

  it('never below low', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'low', onChange });
    run(gov, 60, 10);
    expect(gov.level).toBe('low');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('does not measure without measuring and needs 3 s grace afterwards', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 30, 20, false); // pause/menu: never measure
    expect(gov.level).toBe('high');
    run(gov, 7.9, 20); // 3 s grace + 4.9 s measuring
    expect(gov.level).toBe('high');
    run(gov, 0.2, 20);
    expect(gov.level).toBe('medium');
  });

  it('an interruption resets the window and the grace period', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 7, 30); // 3 s grace + 4 s measured
    gov.frame(1 / 30, false);
    run(gov, 7, 30); // again 3 s grace + only 4 s measured
    expect(gov.level).toBe('high');
    run(gov, 1.1, 30);
    expect(gov.level).toBe('medium');
  });

  it('a frame longer than 2 s counts as an interruption', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 7, 30);
    gov.frame(2.5, true); // e.g. the machine was suspended
    run(gov, 7, 30);
    expect(gov.level).toBe('high');
    run(gov, 1.1, 30);
    expect(gov.level).toBe('medium');
  });

  it('very slow frames (0.5 s to 2 s) are averaged, not treated as interruptions', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    // 0.6 s per frame is under 2 fps: the device is far too slow and must step down
    run(gov, 3, 1 / 0.6); // grace period
    run(gov, 6, 1 / 0.6);
    expect(gov.level).toBe('medium');
    expect(onChange).toHaveBeenCalledWith('medium');
  });

  it('a single long frame does not reset the window of a slow device', () => {
    const gov = createQualityGovernor({ level: 'high' });
    run(gov, 3, 20); // grace
    run(gov, 3, 20); // 3 s of window collected
    gov.frame(0.8, true); // one hitch
    run(gov, 2.5, 20);
    expect(gov.level).toBe('medium');
  });

  it('short drops are averaged over 5 s', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 3, 60);
    // 4 s at 60 fps, 1 s at 30 fps: mean = 270 frames / 5 s = 54 fps
    for (let k = 0; k < 6; k += 1) {
      run(gov, 4, 60);
      run(gov, 1, 30);
    }
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('never upgrades', () => {
    const gov = createQualityGovernor({ level: 'high' });
    run(gov, 9, 30);
    expect(gov.level).toBe('medium');
    run(gov, 120, 144);
    expect(gov.level).toBe('medium');
  });

  it('auto=false never changes', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', auto: false, onChange });
    run(gov, 60, 10);
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('setAuto(true) starts with grace, setLevel sets manually', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', auto: false, onChange });
    run(gov, 20, 20);
    gov.setAuto(true);
    run(gov, 7.9, 20);
    expect(gov.level).toBe('high');
    run(gov, 0.2, 20);
    expect(gov.level).toBe('medium');
    gov.setLevel('high');
    expect(gov.level).toBe('high');
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('uses now() when no dt is passed', () => {
    let ms = 0;
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'medium', onChange, now: () => ms });
    for (let i = 0; i < 400; i += 1) {
      ms += 40; // 25 fps
      gov.frame(undefined, true);
    }
    expect(gov.level).toBe('low');
    expect(onChange).toHaveBeenCalledWith('low');
  });

  it('reports the moving average', () => {
    const gov = createQualityGovernor({ level: 'high' });
    run(gov, 3, 60);
    expect(gov.averageFps).toBeNull();
    run(gov, 5, 60);
    expect(gov.averageFps).toBeCloseTo(60, 0);
  });
});

describe('createLowFpsHint (rule 4: manual level too high for the device)', () => {
  /** Runs for `seconds`; returns how often the hint fired. */
  function runHint(hint, seconds, fps, measuring = true) {
    const dt = 1 / fps;
    let fired = 0;
    for (let i = 0; i < Math.round(seconds * fps); i += 1) {
      if (hint.frame(dt, measuring)) fired += 1;
    }
    return fired;
  }

  it('fires after 3 s grace + 5 s below 30 fps', () => {
    const hint = createLowFpsHint();
    expect(runHint(hint, 3, 20)).toBe(0); // grace period
    expect(runHint(hint, 4.9, 20)).toBe(0);
    expect(runHint(hint, 0.2, 20)).toBe(1);
  });

  it('fires only once, however long the frame rate stays low', () => {
    const hint = createLowFpsHint();
    expect(runHint(hint, 60, 10)).toBe(1);
  });

  it('does not fire at 30 fps or more', () => {
    const hint = createLowFpsHint();
    expect(runHint(hint, 60, 30)).toBe(0);
    expect(runHint(hint, 60, 60)).toBe(0);
  });

  it('averages short drops over 5 s', () => {
    const hint = createLowFpsHint();
    runHint(hint, 3, 60);
    // 4 s at 40 fps + 1 s at 10 fps: 170 frames / 5 s = 34 fps
    let fired = 0;
    for (let k = 0; k < 6; k += 1) {
      fired += runHint(hint, 4, 40);
      fired += runHint(hint, 1, 10);
    }
    expect(fired).toBe(0);
  });

  it('does not measure while not riding and needs the grace period afterwards', () => {
    const hint = createLowFpsHint();
    expect(runHint(hint, 30, 10, false)).toBe(0);
    expect(runHint(hint, 7.9, 10)).toBe(0); // 3 s grace + 4.9 s measuring
    expect(runHint(hint, 0.2, 10)).toBe(1);
  });

  it('an interruption resets the window and the grace period', () => {
    const hint = createLowFpsHint();
    runHint(hint, 7, 10); // 3 s grace + 4 s measured
    hint.interrupt();
    expect(runHint(hint, 7, 10)).toBe(0);
    expect(runHint(hint, 1.1, 10)).toBe(1);
  });

  it('reset starts a new ride: it can fire again and needs the grace period again', () => {
    const hint = createLowFpsHint();
    expect(runHint(hint, 60, 10)).toBe(1);
    expect(runHint(hint, 60, 10)).toBe(0);
    hint.reset();
    expect(runHint(hint, 7.9, 10)).toBe(0);
    expect(runHint(hint, 0.2, 10)).toBe(1);
  });

  it('a frame longer than 2 s counts as an interruption', () => {
    const hint = createLowFpsHint();
    runHint(hint, 7, 10);
    hint.frame(2.5, true);
    expect(runHint(hint, 7, 10)).toBe(0);
    expect(runHint(hint, 1.1, 10)).toBe(1);
  });

  it('shows the hint again for a new ride (a new instance)', () => {
    expect(runHint(createLowFpsHint(), 20, 10)).toBe(1);
    expect(runHint(createLowFpsHint(), 20, 10)).toBe(1);
  });

  it('ignores invalid frame times', () => {
    const hint = createLowFpsHint();
    for (const bad of [NaN, -1, undefined]) expect(hint.frame(bad, true)).toBe(false);
  });

  it('accepts other thresholds through options', () => {
    const hint = createLowFpsHint({ options: { windowS: 2, graceS: 0, maxFps: 20 } });
    expect(runHint(hint, 2.1, 15)).toBe(1);
  });
});

describe('canHintLowerLevel', () => {
  it('is true only for a manual level above low', () => {
    expect(canHintLowerLevel({ auto: false, level: 'high' })).toBe(true);
    expect(canHintLowerLevel({ auto: false, level: 'medium' })).toBe(true);
  });

  it('is false at the lowest level: there is nothing lower to pick', () => {
    expect(canHintLowerLevel({ auto: false, level: 'low' })).toBe(false);
  });

  it('is false with "Automatic" on (the governor handles it)', () => {
    expect(canHintLowerLevel({ auto: true, level: 'high' })).toBe(false);
  });
});

describe('levelAfterContextLoss (rule 4)', () => {
  it('with "Automatic" on, a level above low goes to low and is saved', () => {
    expect(levelAfterContextLoss({ auto: true, level: 'high' })).toEqual({
      level: 'low',
      persist: true,
      hint: false,
    });
    expect(levelAfterContextLoss({ auto: true, level: 'medium' })).toEqual({
      level: 'low',
      persist: true,
      hint: false,
    });
  });

  it('with "Automatic" on at low nothing changes and nothing is saved', () => {
    expect(levelAfterContextLoss({ auto: true, level: 'low' })).toEqual({
      level: 'low',
      persist: false,
      hint: false,
    });
  });

  it('a manual level above low stays and the player gets a hint', () => {
    for (const level of ['medium', 'high']) {
      expect(levelAfterContextLoss({ auto: false, level })).toEqual({
        level,
        persist: false,
        hint: true,
      });
    }
  });

  it('a manual low level needs no hint: there is nothing lower to pick', () => {
    expect(levelAfterContextLoss({ auto: false, level: 'low' })).toEqual({
      level: 'low',
      persist: false,
      hint: false,
    });
  });

  it('a loss while the page is in the background is no overload: nothing changes, no hint', () => {
    for (const auto of [true, false]) {
      for (const level of GRAPHICS_LEVELS) {
        expect(levelAfterContextLoss({ auto, level, visible: false })).toEqual({
          level,
          persist: false,
          hint: false,
        });
      }
    }
  });

  it('a loss right after the page came back to the foreground is no overload either', () => {
    const justBack = { visible: true, sinceVisibilityChangeS: CONTEXT_LOSS_GRACE_S - 0.1 };
    expect(levelAfterContextLoss({ auto: true, level: 'high', ...justBack })).toEqual({
      level: 'high',
      persist: false,
      hint: false,
    });
    expect(levelAfterContextLoss({ auto: false, level: 'high', ...justBack }).hint).toBe(false);
  });

  it('a loss in the foreground after the grace time counts as before', () => {
    const settled = { visible: true, sinceVisibilityChangeS: CONTEXT_LOSS_GRACE_S };
    expect(levelAfterContextLoss({ auto: true, level: 'high', ...settled })).toEqual({
      level: 'low',
      persist: true,
      hint: false,
    });
    expect(levelAfterContextLoss({ auto: false, level: 'high', ...settled }).hint).toBe(true);
  });

  it('without visibility information a loss counts (visible, no change seen)', () => {
    expect(levelAfterContextLoss({ auto: true, level: 'medium' }).level).toBe('low');
  });

  it('the hint follows the same rule as the "level too high" hint', () => {
    for (const auto of [true, false]) {
      for (const level of GRAPHICS_LEVELS) {
        expect(levelAfterContextLoss({ auto, level }).hint).toBe(
          canHintLowerLevel({ auto, level }),
        );
      }
    }
  });
});

describe('presetFor', () => {
  it('maps level names to presets and passes preset objects through', () => {
    expect(presetFor('medium')).toBe(QUALITY_PRESETS.medium);
    const custom = { ...QUALITY_PRESETS.high, pixelRatio: 1.25 };
    expect(presetFor(custom)).toBe(custom);
    expect(presetFor('nope')).toBeUndefined();
    expect(presetFor(null)).toBeUndefined();
  });

  it('every preset knows its level name', () => {
    for (const level of GRAPHICS_LEVELS) expect(QUALITY_PRESETS[level].level).toBe(level);
  });
});

// a 4 GiB tablet: 1280 × 800 CSS pixels at a device pixel ratio of 2
const TABLET = { cssWidth: 1280, cssHeight: 800, devicePixelRatio: 2, antialias: true };

describe('estimateGpuMemoryMB', () => {
  const estimate = (level, ctx = {}) =>
    estimateGpuMemoryMB(QUALITY_PRESETS[level], { ...TABLET, ...ctx });

  it('adds drawing buffer, shadow map, environment map, textures and scenery (high on a tablet)', () => {
    // 2560 × 1600 px × 40 B (MSAA ×4 colour+depth, two colour buffers) = 156.25 MiB; shadow map
    // 2048² × 8 B = 32 MiB; environment map 6 MiB; 3 textures with mipmaps 4 MiB; baseline 16 MiB;
    // scenery 2 MiB, grass tufts 2, flowers 0.5, decoration 0.5, animals 0.1, two grazing horses
    // 0.86, hoof dust 0.01, extra vertices of horse and rider 0.22
    expect(estimate('high')).toBeCloseTo(220.44, 1);
  });

  it('counts the details of the scenery and scales them with the preset', () => {
    const base = QUALITY_PRESETS.high;
    const cost = (key, off = 0) =>
      estimateGpuMemoryMB(base, TABLET) - estimateGpuMemoryMB({ ...base, [key]: off }, TABLET);
    expect(cost('grassTufts')).toBeCloseTo(2, 5);
    expect(cost('flowers')).toBeCloseTo(0.5, 5);
    expect(cost('decor')).toBeCloseTo(0.5, 5);
    // birds and butterflies share one small cost: only both off saves it
    expect(cost('birds')).toBe(0);
    const noAnimals = estimateGpuMemoryMB({ ...base, birds: 0, butterflies: 0 }, TABLET);
    expect(estimateGpuMemoryMB(base, TABLET) - noAnimals).toBeCloseTo(0.1, 5);
    // half the flowers cost half
    expect(cost('flowers', 0.5)).toBeCloseTo(0.25, 5);
  });

  it('counts the grazing horses by their model, the dust and the vertices of horse and rider', () => {
    const cost = (preset, key, off) =>
      estimateGpuMemoryMB(preset, TABLET) - estimateGpuMemoryMB({ ...preset, [key]: off }, TABLET);
    // two horses of the low model (167 KB) on medium, of the medium model (430 KB) on high
    expect(cost(QUALITY_PRESETS.medium, 'grazingHorses', 0)).toBeCloseTo((2 * 167) / 1024, 5);
    expect(cost(QUALITY_PRESETS.high, 'grazingHorses', 0)).toBeCloseTo((2 * 430) / 1024, 5);
    expect(cost(QUALITY_PRESETS.high, 'grazingHorses', 1)).toBeCloseTo(430 / 1024, 5);
    expect(cost(QUALITY_PRESETS.high, 'hoofDust', false)).toBeCloseTo(0.01, 5);
    // rider +16 / +80 / +150 KB, horse +0 / +36 / +80 KB per character detail
    const characters = (level) =>
      cost({ ...QUALITY_PRESETS[level], grazingHorses: 0 }, 'characterDetail', 'none') * 1024;
    expect(characters('low')).toBeCloseTo(16, 3);
    expect(characters('medium')).toBeCloseTo(116, 3);
    expect(characters('high')).toBeCloseTo(230, 3);
  });

  it('every detail that a level shows is part of its estimate (low carries none)', () => {
    const low = QUALITY_PRESETS.low;
    const stripped = {
      ...low,
      grassTufts: 0,
      flowers: 0,
      decor: 0,
      birds: 0,
      butterflies: 0,
      grazingHorses: 0,
      hoofDust: false,
    };
    expect(estimateGpuMemoryMB(low, TABLET)).toBeCloseTo(estimateGpuMemoryMB(stripped, TABLET), 9);
    for (const level of ['medium', 'high']) {
      const preset = QUALITY_PRESETS[level];
      const without = {
        ...preset,
        flowers: 0,
        decor: 0,
        birds: 0,
        butterflies: 0,
        grassTufts: 0,
        grazingHorses: 0,
        hoofDust: false,
      };
      expect(estimateGpuMemoryMB(preset, TABLET)).toBeGreaterThan(
        estimateGpuMemoryMB(without, TABLET),
      );
    }
  });

  it('copes with presets that know none of the newer details', () => {
    const old = { ...QUALITY_PRESETS.medium };
    for (const key of ['flowers', 'decor', 'birds', 'butterflies', 'grazingHorses', 'hoofDust']) {
      delete old[key];
    }
    expect(Number.isFinite(estimateGpuMemoryMB(old, TABLET))).toBe(true);
  });

  it('grows with the square of the pixel ratio', () => {
    const at = (ratio) => estimate('high', { pixelRatio: ratio });
    // 4× the pixels at ratio 2 vs 1: the difference is 3 × the buffer at ratio 1
    const buffer1 = (1280 * 800 * 40) / (1024 * 1024);
    expect(at(2) - at(1)).toBeCloseTo(3 * buffer1, 1);
  });

  it('is capped by the device pixel ratio', () => {
    expect(estimate('high', { devicePixelRatio: 1 })).toBeCloseTo(
      estimate('high', { pixelRatio: 1 }),
      5,
    );
  });

  it('antialiasing costs a lot: multisampled colour and depth on top', () => {
    const aa = estimate('high', { antialias: true });
    const plain = estimate('high', { antialias: false });
    expect(aa - plain).toBeCloseTo((2560 * 1600 * (4 * 8 + 8 - 12)) / (1024 * 1024), 1);
  });

  it('takes the context attribute, not the preset, for antialiasing', () => {
    const preset = QUALITY_PRESETS.low; // wants no antialiasing
    expect(estimateGpuMemoryMB(preset, { ...TABLET, antialias: true })).toBeGreaterThan(
      estimateGpuMemoryMB(preset, { ...TABLET, antialias: false }),
    );
  });

  it('a shadow map costs size² × 8 bytes, none without shadows', () => {
    const noShadow = estimateGpuMemoryMB({ ...QUALITY_PRESETS.medium, shadows: false }, TABLET);
    expect(estimate('medium') - noShadow).toBeCloseTo(8, 5); // 1024² × 8 B
    const big = estimateGpuMemoryMB({ ...QUALITY_PRESETS.medium, shadowMapSize: 2048 }, TABLET);
    expect(big - estimate('medium')).toBeCloseTo(24, 5);
  });

  it('the environment map and the normal maps only count when the level uses them', () => {
    const base = QUALITY_PRESETS.medium;
    const noEnv = estimateGpuMemoryMB({ ...base, envMap: false }, TABLET);
    expect(estimate('medium') - noEnv).toBeCloseTo(6, 5);
    const noNormal = estimateGpuMemoryMB({ ...base, normalMaps: false }, TABLET);
    expect(estimate('medium') - noNormal).toBeCloseTo((512 * 512 * 4 * (4 / 3)) / (1024 * 1024), 5);
  });

  it('counts the textures it is given', () => {
    const none = estimate('medium', { textures: [] });
    const one = estimate('medium', { textures: [{ width: 1024, height: 1024, normal: false }] });
    expect(one - none).toBeCloseTo((1024 * 1024 * 4 * (4 / 3)) / (1024 * 1024), 5);
  });

  it('orders the levels: low < medium < high', () => {
    const ctx = { antialias: true };
    const [low, medium, high] = GRAPHICS_LEVELS.map((l) => estimate(l, ctx));
    expect(low).toBeLessThan(medium);
    expect(medium).toBeLessThan(high);
  });
});

describe('gpuBudgetMB', () => {
  it('is smaller on touch devices than on desktops', () => {
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 4 })).toBeLessThan(
      gpuBudgetMB({ isTouch: false, deviceMemory: 4 }),
    );
    expect(gpuBudgetMB({ isTouch: true })).toBeLessThan(gpuBudgetMB({ isTouch: false }));
  });

  it('follows the device memory, within the class limits', () => {
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 4 })).toBe(160);
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 8 })).toBe(320);
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 2 })).toBe(96); // floor
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 0.5 })).toBe(96);
    expect(gpuBudgetMB({ isTouch: false, deviceMemory: 8 })).toBe(512);
    expect(gpuBudgetMB({ isTouch: false, deviceMemory: 16 })).toBe(1024); // ceiling
  });

  it('uses a conservative value when the browser does not report memory', () => {
    expect(gpuBudgetMB({ isTouch: true })).toBe(160);
    expect(gpuBudgetMB({ isTouch: false })).toBe(512);
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: undefined })).toBe(160);
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 0 })).toBe(160);
    expect(gpuBudgetMB()).toBe(512);
  });

  it('gives a weak GPU a quarter less', () => {
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 4, rendererString: 'Mali-G52' })).toBe(120);
    expect(gpuBudgetMB({ isTouch: true, deviceMemory: 4, rendererString: 'Apple GPU' })).toBe(160);
  });
});

describe('fitPresetToBudget', () => {
  const fit = (level, budget, ctx = {}) =>
    fitPresetToBudget(QUALITY_PRESETS[level], { ...TABLET, ...ctx }, budget);

  it('changes nothing when the level fits (the very same preset object)', () => {
    const result = fit('medium', 160);
    expect(result.preset).toBe(QUALITY_PRESETS.medium);
    expect(result.fits).toBe(true);
    expect(result.capped).toEqual({ pixelRatio: null, shadowMapSize: null, scenery: false });
    expect(result.estimateMB).toBeCloseTo(result.requestedMB, 9);
  });

  it('lowers only the pixel ratio first, in steps of 0.05, and keeps every effect', () => {
    const result = fit('high', 160);
    expect(result.fits).toBe(true);
    expect(result.estimateMB).toBeLessThanOrEqual(160);
    expect(result.preset.pixelRatio).toBe(1.55);
    expect(result.capped.pixelRatio).toEqual({ from: 2, to: 1.55 });
    expect(result.capped.shadowMapSize).toBeNull();
    expect(result.capped.scenery).toBe(false);
    expect({ ...result.preset, pixelRatio: 2 }).toEqual(QUALITY_PRESETS.high);
  });

  it('takes the biggest ratio that fits (a bit more would not)', () => {
    const result = fit('high', 160);
    const next = estimateGpuMemoryMB(result.preset, {
      ...TABLET,
      pixelRatio: result.preset.pixelRatio + 0.05,
    });
    expect(next).toBeGreaterThan(160);
  });

  it('goes down to the ratio 1, then halves the shadow map', () => {
    const atRatio1 = estimateGpuMemoryMB(QUALITY_PRESETS.high, { ...TABLET, pixelRatio: 1 });
    const result = fit('high', atRatio1 - 5);
    expect(result.preset.pixelRatio).toBe(1);
    expect(result.preset.shadowMapSize).toBe(1024);
    expect(result.capped.shadowMapSize).toEqual({ from: 2048, to: 1024 });
    expect(result.capped.scenery).toBe(false);
    expect(result.fits).toBe(true);
  });

  it('takes the tufts and then the density of the scenery as the last lever', () => {
    const base = fit('high', 1000);
    expect(base.preset).toBe(QUALITY_PRESETS.high);
    const noShadowGain = estimateGpuMemoryMB(
      { ...QUALITY_PRESETS.high, pixelRatio: 1, shadowMapSize: 1024 },
      TABLET,
    );
    const result = fit('high', noShadowGain - 0.5);
    expect(result.preset.grassTufts).toBe(0);
    expect(result.capped.scenery).toBe(true);
    expect(result.preset.envDensity).toBe(1); // the tufts were enough
    const tiny = fit('high', 20);
    expect(tiny.preset.grassTufts).toBe(0);
    expect(tiny.preset.flowers).toBe(0);
    expect(tiny.preset.butterflies).toBe(0);
    expect(tiny.preset.birds).toBe(0);
    expect(tiny.preset.decor).toBe(0);
    expect(tiny.preset.envDensity).toBe(0.55);
    expect(tiny.preset.pixelRatio).toBe(1);
    expect(tiny.preset.shadowMapSize).toBe(1024);
    expect(tiny.fits).toBe(false); // best effort: the level still plays
  });

  it('drops the flowers after the tufts and before the trees and bushes', () => {
    const atRatio1 = { ...TABLET, pixelRatio: 1 };
    const cheapest = (p) => estimateGpuMemoryMB({ ...p, shadowMapSize: 1024 }, atRatio1);
    const justWithout = cheapest({ ...QUALITY_PRESETS.high, grassTufts: 0 });
    const result = fit('high', justWithout - 0.1);
    expect(result.preset.grassTufts).toBe(0);
    expect(result.preset.flowers).toBe(0);
    expect(result.preset.envDensity).toBe(1); // the flowers were enough
    expect(result.preset.decor).toBe(1); // the decoration and the animals stay
    expect(result.preset.birds).toBe(1);
    expect(result.capped.scenery).toBe(true);
  });

  it('drops the grazing horses after the flowers and before the trees and bushes', () => {
    const atRatio1 = { ...TABLET, pixelRatio: 1 };
    const cheapest = (p) => estimateGpuMemoryMB({ ...p, shadowMapSize: 1024 }, atRatio1);
    const withoutFlowers = { ...QUALITY_PRESETS.high, grassTufts: 0, flowers: 0 };
    const result = fit('high', cheapest(withoutFlowers) - 0.1);
    expect(result.preset.flowers).toBe(0);
    expect(result.preset.grazingHorses).toBe(0);
    expect(result.preset.envDensity).toBe(1); // the horses were enough
    expect(result.preset.hoofDust).toBe(true);
    expect(result.capped.scenery).toBe(true);
    // the horses are the last of the "grass and surroundings" group, after the resolution
    expect(result.preset.pixelRatio).toBe(1);
    expect(fit('high', 160).preset.grazingHorses).toBe(2);
  });

  it('takes the butterflies with the flowers they hover over', () => {
    const atRatio1 = { ...TABLET, pixelRatio: 1 };
    const cheapest = (p) => estimateGpuMemoryMB({ ...p, shadowMapSize: 1024 }, atRatio1);
    const noTufts = { ...QUALITY_PRESETS.high, grassTufts: 0 };
    const result = fit('high', cheapest(noTufts) - 0.1);
    expect(result.preset.flowers).toBe(0);
    expect(result.preset.butterflies).toBe(0);
    expect(result.preset.birds).toBe(1);
  });

  it('then takes the birds and the decoration, before the trees and bushes', () => {
    const atRatio1 = { ...TABLET, pixelRatio: 1 };
    const cheapest = (p) => estimateGpuMemoryMB({ ...p, shadowMapSize: 1024 }, atRatio1);
    const bare = {
      ...QUALITY_PRESETS.high,
      grassTufts: 0,
      flowers: 0,
      butterflies: 0,
      grazingHorses: 0,
    };
    const birdsGone = fit('high', cheapest(bare) - 0.01);
    expect(birdsGone.preset.birds).toBe(0);
    expect(birdsGone.preset.decor).toBe(1);
    expect(birdsGone.preset.envDensity).toBe(1);
    const decorGone = fit('high', cheapest({ ...bare, birds: 0 }) - 0.01);
    expect(decorGone.preset.decor).toBe(0);
    expect(decorGone.preset.envDensity).toBe(1);
    expect(decorGone.capped.scenery).toBe(true);
    // only now do the trees and bushes get thinner
    const treesThinner = fit('high', cheapest({ ...bare, birds: 0, decor: 0 }) - 0.01);
    expect(treesThinner.preset.envDensity).toBe(0.55);
  });

  it('is monotonic: a bigger budget never gives a lower ratio or fewer features', () => {
    const contexts = [TABLET, { ...TABLET, devicePixelRatio: 3 }, { ...TABLET, antialias: false }];
    for (const level of GRAPHICS_LEVELS) {
      for (const ctx of contexts) {
        let previous = null;
        for (let budget = 5; budget <= 600; budget += 5) {
          const { preset } = fit(level, budget, ctx);
          if (previous) {
            expect(preset.pixelRatio).toBeGreaterThanOrEqual(previous.pixelRatio);
            expect(preset.shadowMapSize).toBeGreaterThanOrEqual(previous.shadowMapSize);
            expect(preset.grassTufts).toBeGreaterThanOrEqual(previous.grassTufts);
            expect(preset.flowers).toBeGreaterThanOrEqual(previous.flowers);
            expect(preset.grazingHorses).toBeGreaterThanOrEqual(previous.grazingHorses);
            expect(preset.butterflies).toBeGreaterThanOrEqual(previous.butterflies);
            expect(preset.birds).toBeGreaterThanOrEqual(previous.birds);
            expect(preset.decor).toBeGreaterThanOrEqual(previous.decor);
            expect(preset.envDensity).toBeGreaterThanOrEqual(previous.envDensity);
          }
          previous = preset;
        }
      }
    }
  });

  it('never goes below ratio 1 (a lower device ratio is simply used as it is)', () => {
    expect(fit('high', 20).preset.pixelRatio).toBe(1);
    const slow = fit('high', 20, { devicePixelRatio: 0.75 });
    expect(Math.min(0.75, slow.preset.pixelRatio)).toBe(0.75);
    expect(slow.capped.pixelRatio).toBeNull();
  });

  it('does not touch what is already small: medium keeps its shadow map', () => {
    const result = fit('medium', 20);
    expect(result.preset.shadowMapSize).toBe(1024);
    expect(result.capped.shadowMapSize).toBeNull();
    expect(result.preset.pixelRatio).toBe(1);
  });

  it('does not cap the ratio when the device ratio is below the level cap already', () => {
    const result = fit('high', 1000, { devicePixelRatio: 1.25 });
    expect(result.preset).toBe(QUALITY_PRESETS.high);
  });

  it('does not change the presets (copies, frozen)', () => {
    const before = JSON.stringify(QUALITY_PRESETS);
    const result = fit('high', 100);
    expect(JSON.stringify(QUALITY_PRESETS)).toBe(before);
    expect(Object.isFrozen(result.preset)).toBe(true);
    expect(result.preset.level).toBe('high');
  });

  it('reports the estimate of the fitted preset and the requested one', () => {
    const result = fit('high', 160);
    expect(result.requestedMB).toBeCloseTo(220.44, 1);
    expect(result.estimateMB).toBeCloseTo(estimateGpuMemoryMB(result.preset, TABLET), 9);
    expect(result.budgetMB).toBe(160);
  });
});

describe('chooseAntialias', () => {
  const ctx = { cssWidth: 1280, cssHeight: 800, devicePixelRatio: 2 };

  it('keeps antialiasing when the budget carries it with the other levers used up', () => {
    expect(chooseAntialias(QUALITY_PRESETS.high, ctx, 160)).toBe(true);
    expect(chooseAntialias(QUALITY_PRESETS.medium, ctx, 160)).toBe(true);
  });

  it('drops it when the multisampled buffers alone would not fit', () => {
    expect(chooseAntialias(QUALITY_PRESETS.high, ctx, 40)).toBe(false);
  });

  it('is off for a level that does not want it', () => {
    expect(chooseAntialias(QUALITY_PRESETS.low, ctx, 1000)).toBe(false);
  });
});
