import { describe, expect, it } from 'vitest';
import {
  channelGain,
  clamp01,
  generateImpulse,
  midiToFreq,
  mulberry32,
  normalizeSettings,
  planSteps,
  shouldMusicRun,
  volumeToGain,
} from './logic.js';

describe('Lautstärke-Mapping', () => {
  it('ist an den Rändern exakt und dazwischen quadratisch', () => {
    expect(volumeToGain(0)).toBe(0);
    expect(volumeToGain(1)).toBe(1);
    expect(volumeToGain(0.5)).toBeCloseTo(0.25);
  });

  it('ist monoton steigend', () => {
    let last = -1;
    for (let i = 0; i <= 20; i++) {
      const g = volumeToGain(i / 20);
      expect(g).toBeGreaterThan(last);
      last = g;
    }
  });

  it('begrenzt und bereinigt ungültige Werte', () => {
    expect(volumeToGain(-3)).toBe(0);
    expect(volumeToGain(7)).toBe(1);
    expect(clamp01(NaN)).toBe(0.5);
    expect(clamp01('laut')).toBe(0.5);
    expect(clamp01(undefined, 0.2)).toBe(0.2);
  });

  it('Stumm setzt den Kanal auf 0, ohne die Lautstärke zu verändern', () => {
    expect(channelGain(0.8, true)).toBe(0);
    expect(channelGain(0.8, false)).toBeCloseTo(0.64);
  });

  it('normalizeSettings setzt mittlere Lautstärke und beide Kanäle an', () => {
    expect(normalizeSettings()).toEqual({
      musicVolume: 0.5,
      musicMuted: false,
      sfxVolume: 0.5,
      sfxMuted: false,
    });
    expect(normalizeSettings({ musicVolume: 2, sfxMuted: true, musicMuted: 'ja' })).toEqual({
      musicVolume: 1,
      musicMuted: false,
      sfxVolume: 0.5,
      sfxMuted: true,
    });
  });
});

describe('shouldMusicRun', () => {
  it('läuft nur bei Wunsch, sichtbar und nicht stumm', () => {
    expect(shouldMusicRun({ wanted: true, hidden: false, muted: false })).toBe(true);
    expect(shouldMusicRun({ wanted: false, hidden: false, muted: false })).toBe(false);
    expect(shouldMusicRun({ wanted: true, hidden: true, muted: false })).toBe(false);
    expect(shouldMusicRun({ wanted: true, hidden: false, muted: true })).toBe(false);
  });
});

describe('midiToFreq', () => {
  it('A4 = 440 Hz, Oktave verdoppelt', () => {
    expect(midiToFreq(69)).toBeCloseTo(440);
    expect(midiToFreq(81)).toBeCloseTo(880);
    expect(midiToFreq(60)).toBeCloseTo(261.63, 1);
  });
});

describe('planSteps', () => {
  const base = { lookahead: 0.15, stepDuration: 0.3, loopSteps: 4 };

  it('plant alle Schritte im Fenster und merkt sich den nächsten', () => {
    const p = planSteps({ ...base, now: 0, nextTime: 0.05, step: 0 });
    expect(p.events).toEqual([{ step: 0, time: 0.05 }]);
    expect(p.step).toBe(1);
    expect(p.nextTime).toBeCloseTo(0.35);
    // Fenster ohne Schritt
    const q = planSteps({ ...base, now: 0.1, nextTime: p.nextTime, step: p.step });
    expect(q.events).toEqual([]);
    expect(q.nextTime).toBeCloseTo(0.35);
  });

  it('läuft ohne Drift und umlaufend über viele Aufrufe', () => {
    let state = { nextTime: 0.1, step: 0 };
    const times = [];
    const steps = [];
    for (let now = 0; now < 6; now += 0.03) {
      const p = planSteps({ ...base, now, ...state });
      for (const e of p.events) {
        times.push(e.time);
        steps.push(e.step);
      }
      state = { nextTime: p.nextTime, step: p.step };
    }
    expect(steps.slice(0, 9)).toEqual([0, 1, 2, 3, 0, 1, 2, 3, 0]);
    times.slice(1).forEach((t, i) => expect(t - times[i]).toBeCloseTo(0.3, 6));
  });

  it('setzt nach langer Pause neu auf, statt einen Stau nachzuholen', () => {
    const p = planSteps({ ...base, now: 100, nextTime: 3, step: 2 });
    expect(p.events).toHaveLength(1);
    expect(p.events[0].step).toBe(2);
    expect(p.events[0].time).toBeGreaterThanOrEqual(100);
    expect(p.events[0].time).toBeLessThan(100.1);
  });

  it('plant nie in die Vergangenheit', () => {
    const p = planSteps({ ...base, now: 10, nextTime: 9.9, step: 0 });
    expect(p.events[0].time).toBe(10);
  });
});

describe('mulberry32', () => {
  it('ist deterministisch und liegt in [0, 1)', () => {
    const a = mulberry32(5);
    const b = mulberry32(5);
    for (let i = 0; i < 100; i++) {
      const x = a();
      expect(x).toBe(b());
      expect(x).toBeGreaterThanOrEqual(0);
      expect(x).toBeLessThan(1);
    }
  });
});

describe('generateImpulse', () => {
  it('liefert zwei unterschiedliche, begrenzte und ausklingende Kanäle', () => {
    const [l, r] = generateImpulse({ sampleRate: 8000, seconds: 1 });
    expect(l).toHaveLength(8000);
    expect(r).toHaveLength(8000);
    expect(l).not.toEqual(r);
    const peak = (d, from, to) => Math.max(...d.slice(from, to).map(Math.abs));
    expect(peak(l, 0, 8000)).toBeLessThanOrEqual(1);
    expect(peak(l, 0, 2000)).toBeGreaterThan(peak(l, 6000, 8000));
    expect(Math.abs(l[7999])).toBeLessThan(0.01);
    expect(l.every(Number.isFinite)).toBe(true);
  });

  it('ist mit gleichem Zufallsgenerator reproduzierbar', () => {
    const a = generateImpulse({ sampleRate: 1000, seconds: 0.5, rng: mulberry32(3) });
    const b = generateImpulse({ sampleRate: 1000, seconds: 0.5, rng: mulberry32(3) });
    expect(a[0]).toEqual(b[0]);
  });
});
