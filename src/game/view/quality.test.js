import { describe, it, expect, vi } from 'vitest';
import {
  createQualityGovernor,
  pickInitialLevel,
  lowerLevel,
  QUALITY_PRESETS,
  QUALITY_LEVELS,
} from './quality.js';

/** Lässt den Governor `seconds` lang mit konstanter Bildrate laufen. */
function run(gov, seconds, fps, measuring = true) {
  const dt = 1 / fps;
  const n = Math.round(seconds * fps);
  for (let i = 0; i < n; i += 1) gov.frame(dt, measuring);
}

describe('pickInitialLevel', () => {
  it('wählt low für Software-Renderer', () => {
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

  it('wählt high für starken Desktop', () => {
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

  it('Touch-Geräte höchstens medium, schwache low', () => {
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

  it('schwacher Desktop medium oder low', () => {
    expect(
      pickInitialLevel({ hardwareConcurrency: 4, rendererString: 'Intel(R) UHD Graphics 620' }),
    ).toBe('medium');
    expect(pickInitialLevel({ hardwareConcurrency: 2 })).toBe('low');
    expect(pickInitialLevel({})).toBe('medium');
  });
});

describe('lowerLevel', () => {
  it('geht eine Stufe runter, nie unter low', () => {
    expect(lowerLevel('high')).toBe('medium');
    expect(lowerLevel('medium')).toBe('low');
    expect(lowerLevel('low')).toBe('low');
  });
});

describe('QUALITY_PRESETS', () => {
  it('hat alle Stufen mit Pixelratio- und Schattenwerten', () => {
    expect(Object.keys(QUALITY_PRESETS)).toEqual(QUALITY_LEVELS);
    expect(QUALITY_PRESETS.low.pixelRatio).toBe(1);
    expect(QUALITY_PRESETS.medium.pixelRatio).toBe(1.5);
    expect(QUALITY_PRESETS.high.pixelRatio).toBe(2);
    expect(QUALITY_PRESETS.low.shadows).toBe(false);
    expect(QUALITY_PRESETS.medium.shadowMapSize).toBe(1024);
    expect(QUALITY_PRESETS.high.shadowMapSize).toBe(2048);
    expect(QUALITY_PRESETS.low.material).toBe('lambert');
  });
});

describe('createQualityGovernor', () => {
  it('stuft nach 3 s Karenz + 5 s unter 50 fps eine Stufe runter', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', auto: true, onChange });
    run(gov, 3, 40); // Karenz
    expect(gov.level).toBe('high');
    run(gov, 4.9, 40);
    expect(gov.level).toBe('high');
    run(gov, 0.2, 40);
    expect(gov.level).toBe('medium');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('medium');
  });

  it('bleibt bei mindestens 50 fps', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 60, 50);
    run(gov, 60, 60);
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('wartet nach einer Anpassung mindestens 10 s', () => {
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

  it('nie unter low', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'low', onChange });
    run(gov, 60, 10);
    expect(gov.level).toBe('low');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('misst nicht ohne measuring und braucht danach 3 s Karenz', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 30, 20, false); // Pause/Menü: nie messen
    expect(gov.level).toBe('high');
    run(gov, 7.9, 20); // 3 s Karenz + 4,9 s Messung
    expect(gov.level).toBe('high');
    run(gov, 0.2, 20);
    expect(gov.level).toBe('medium');
  });

  it('Unterbrechung setzt Messfenster und Karenz zurück', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 7, 30); // 3 s Karenz + 4 s gemessen
    gov.frame(1 / 30, false);
    run(gov, 7, 30); // wieder 3 s Karenz + nur 4 s gemessen
    expect(gov.level).toBe('high');
    run(gov, 1.1, 30);
    expect(gov.level).toBe('medium');
  });

  it('ein Frame über 0,5 s gilt als Unterbrechung', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 7, 30);
    gov.frame(0.8, true); // z. B. Tab war versteckt
    run(gov, 7, 30);
    expect(gov.level).toBe('high');
    run(gov, 1.1, 30);
    expect(gov.level).toBe('medium');
  });

  it('kurze Einbrüche werden über 5 s gemittelt', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', onChange });
    run(gov, 3, 60);
    // 4 s mit 60 fps, 1 s mit 30 fps: Mittel = 270 Frames / 5 s = 54 fps
    for (let k = 0; k < 6; k += 1) {
      run(gov, 4, 60);
      run(gov, 1, 30);
    }
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('stuft nie hoch', () => {
    const gov = createQualityGovernor({ level: 'high' });
    run(gov, 9, 30);
    expect(gov.level).toBe('medium');
    run(gov, 120, 144);
    expect(gov.level).toBe('medium');
  });

  it('auto=false ändert nie', () => {
    const onChange = vi.fn();
    const gov = createQualityGovernor({ level: 'high', auto: false, onChange });
    run(gov, 60, 10);
    expect(gov.level).toBe('high');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('setAuto(true) startet mit Karenz, setLevel setzt manuell', () => {
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

  it('nutzt now(), wenn kein dt übergeben wird', () => {
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

  it('liefert den gleitenden Durchschnitt', () => {
    const gov = createQualityGovernor({ level: 'high' });
    run(gov, 3, 60);
    expect(gov.averageFps).toBeNull();
    run(gov, 5, 60);
    expect(gov.averageFps).toBeCloseTo(60, 0);
  });
});
