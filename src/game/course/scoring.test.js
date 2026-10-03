import { describe, expect, it } from 'vitest';
import { TUNING } from '../sim/tuning.js';
import {
  allowedTime,
  formatSeconds,
  formatTime,
  idealLine,
  idealLineLength,
  isBetterResult,
  starsFor,
  timeFaults,
  toCentiseconds,
} from './scoring.js';
import { COURSES } from './courses.js';

const el = (id, x, z) => ({ id, kind: 'cross', height: 0.4, spread: 0, x, z, rot: 0 });

// Start-Mitte (0,0) → (0,30) → (40,30) → Ziel-Mitte (40,0): 30 + 40 + 30 = 100 m
const square = {
  id: 2,
  pace: 'canter',
  start: { a: [-3, 0], b: [3, 0], dir: [0, 1] },
  finish: { a: [37, 0], b: [43, 0], dir: [0, -1] },
  obstacles: [
    { number: 1, elements: [el('x1', 0, 30)], directed: true },
    { number: 2, elements: [el('x2a', 40, 30), el('x2b', 40, 20)], directed: true },
  ],
};

describe('Ideallinie', () => {
  it('verbindet Start-Mitte, alle Element-Mitten (auch a und b) und Ziel-Mitte', () => {
    expect(idealLineLength(square)).toBeCloseTo(30 + 40 + 10 + 20, 6);
  });

  it('nimmt Wegpunkte der Wendungen (track) je Teilstrecke mit', () => {
    const withTrack = { ...square, track: [[], [[20, 40]], []] };
    const detour = 2 * Math.hypot(20, 10);
    expect(idealLineLength(withTrack)).toBeCloseTo(30 + detour + 10 + 20, 6);
    expect(idealLine(withTrack)[2]).toEqual([20, 40]);
  });
});

describe('erlaubte Zeit (Regel 33)', () => {
  it('ist Ideallinie / mittleres Galopptempo × 1,5, aufgerundet', () => {
    const len = idealLineLength(square);
    expect(allowedTime(square)).toBe(Math.ceil((len / TUNING.speeds.canterMedium) * 1.5));
  });

  it('nutzt für Parcours mit pace "trot" (Parcours 1) das mittlere Trabtempo', () => {
    const trot = { ...square, pace: 'trot' };
    const len = idealLineLength(trot);
    expect(allowedTime(trot)).toBe(Math.ceil((len / TUNING.speeds.trotMedium) * 1.5));
  });

  it('rundet glatte Werte nicht auf die nächste Sekunde', () => {
    // 100 m / 6 m/s × 1,5 = 25 s
    expect(allowedTime(square, 6)).toBe(25);
    expect(allowedTime(square, 5.9)).toBe(26);
  });

  it('steht in COURSES berechnet, nicht fest eingetragen; Parcours 1 im Trab', () => {
    for (const c of COURSES) expect(c.allowedTimeS).toBe(allowedTime(c));
    expect(COURSES[0].pace).toBe('trot');
    expect(COURSES.slice(1).every((c) => c.pace === 'canter')).toBe(true);
    const p1 = COURSES[0];
    expect(p1.allowedTimeS).toBe(Math.ceil((idealLineLength(p1) / TUNING.speeds.trotMedium) * 1.5));
  });
});

describe('Zeitfehler (Regel 33)', () => {
  it.each([
    [-5000, 0],
    [0, 0],
    [10, 1],
    [3990, 1],
    [4000, 1],
    [4010, 2],
    [8000, 2],
    [8010, 3],
    [60000, 15],
  ])('%i ms über der erlaubten Zeit → %i Punkte', (overMs, faults) => {
    expect(timeFaults(overMs)).toBe(faults);
  });

  it('rechnet in Hundertsteln (Rundungsrauschen zählt nicht)', () => {
    expect(timeFaults(4000.0000001)).toBe(1);
    expect(timeFaults(0.4)).toBe(0);
  });
});

describe('Hundertstel', () => {
  it('schneidet wie eine Stoppuhr ab, ohne Rundungsrauschen', () => {
    expect(toCentiseconds(48279)).toBe(4827);
    expect(toCentiseconds(48269.99999999)).toBe(4827);
    expect(toCentiseconds(0)).toBe(0);
  });
});

describe('Sterne (Regel 36)', () => {
  it.each([
    [0, 3],
    [1, 2],
    [4, 2],
    [5, 1],
    [8, 1],
    [40, 1],
  ])('%i Fehler → %i Sterne', (faults, stars) => {
    expect(starsFor(faults)).toBe(stars);
  });
});

describe('Bestleistung (Begriffe)', () => {
  it('erster beendeter Ritt ist immer Bestleistung', () => {
    expect(isBetterResult({ faults: 12, timeCs: 9999 }, null)).toBe(true);
    expect(isBetterResult({ faults: 12, timeCs: 9999 }, undefined)).toBe(true);
  });

  it('weniger Fehler gewinnt, auch bei längerer Zeit', () => {
    expect(isBetterResult({ faults: 0, timeCs: 9000 }, { faults: 4, timeCs: 4000 })).toBe(true);
    expect(isBetterResult({ faults: 8, timeCs: 3000 }, { faults: 4, timeCs: 4000 })).toBe(false);
  });

  it('bei gleichen Fehlern entscheidet die Zeit auf Hundertstel', () => {
    expect(isBetterResult({ faults: 4, timeCs: 4826 }, { faults: 4, timeCs: 4827 })).toBe(true);
    expect(isBetterResult({ faults: 4, timeCs: 4827 }, { faults: 4, timeCs: 4827 })).toBe(false);
    expect(isBetterResult({ faults: 4, timeCs: 4828 }, { faults: 4, timeCs: 4827 })).toBe(false);
  });

  it('versteht auch Ritt-Ergebnisse mit aufgeschlüsselten Fehlern', () => {
    const result = {
      faults: { knockdowns: 0, refusals: 0, timeFaults: 1, total: 1 },
      timeCs: 5000,
    };
    expect(isBetterResult(result, { faults: 4, timeCs: 3000 })).toBe(true);
    expect(isBetterResult({ faults: 4, timeCs: 3000 }, result)).toBe(false);
  });
});

describe('Zeitformat', () => {
  it('m:ss,hh', () => {
    expect(formatTime(4827)).toBe('0:48,27');
    expect(formatTime(6250)).toBe('1:02,50');
    expect(formatTime(5)).toBe('0:00,05');
    expect(formatTime(0)).toBe('0:00,00');
  });

  it('ss,hh s', () => {
    expect(formatSeconds(4827)).toBe('48,27 s');
    expect(formatSeconds(6250)).toBe('62,50 s');
    expect(formatSeconds(7)).toBe('0,07 s');
  });
});
