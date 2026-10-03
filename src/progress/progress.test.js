import { describe, expect, it } from 'vitest';
import {
  PROGRESS_DEFAULTS,
  addJump,
  applyFinishedRide,
  isBetterResult,
  resetProgress,
  sanitizeProgress,
} from './progress.js';

const fresh = () => sanitizeProgress(undefined);

function ride(courseId, total, timeCs, stars) {
  return {
    courseId,
    timeCs,
    faults: { knockdowns: total, refusals: 0, timeFaults: 0, total },
    stars: stars ?? (total === 0 ? 3 : total <= 4 ? 2 : 1),
    cleanOxer: false,
    cleanCombination: false,
  };
}

describe('sanitizeProgress', () => {
  it('liefert Defaults für fehlende oder kaputte Daten', () => {
    for (const raw of [undefined, null, 'x', 42, [], true]) {
      expect(sanitizeProgress(raw)).toEqual(PROGRESS_DEFAULTS);
    }
  });

  it('übernimmt lesbare Felder und ersetzt kaputte einzeln', () => {
    const p = sanitizeProgress({
      unlocked: 3,
      courses: { 1: { faults: 2, timeCs: 5000, stars: 2 } },
      jumps: 'viel',
      finishedRides: 7,
      badges: [],
    });
    expect(p).toEqual({
      unlocked: 3,
      courses: { 1: { faults: 2, timeCs: 5000, stars: 2 } },
      jumps: 0,
      finishedRides: 7,
      badges: {},
    });
  });

  it('klemmt unlocked auf 1..5 und rundet ab', () => {
    expect(sanitizeProgress({ unlocked: 0 }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: -4 }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: 9 }).unlocked).toBe(5);
    expect(sanitizeProgress({ unlocked: 2.9 }).unlocked).toBe(2);
    expect(sanitizeProgress({ unlocked: NaN }).unlocked).toBe(1);
    expect(sanitizeProgress({ unlocked: '4' }).unlocked).toBe(1);
  });

  it('behält nur Parcours 1..5 mit gültigen Zahlen', () => {
    const p = sanitizeProgress({
      courses: {
        1: { faults: 0, timeCs: 100, stars: 3 },
        2: { faults: -1, timeCs: 100, stars: 2 },
        3: { faults: 1, timeCs: 'x', stars: 2 },
        4: { faults: 1, timeCs: 100, stars: 4 },
        5: null,
        6: { faults: 0, timeCs: 100, stars: 3 },
        0: { faults: 0, timeCs: 100, stars: 3 },
        foo: { faults: 0, timeCs: 100, stars: 3 },
      },
    });
    expect(Object.keys(p.courses)).toEqual(['1']);
  });

  it('prüft Auszeichnungs-Daten, behält aber unbekannte IDs', () => {
    const p = sanitizeProgress({
      badges: {
        firstJump: '2026-10-03T10:00:00.000Z',
        jumpMouse: 'kein Datum',
        clean: 12345,
        future: 'beliebig',
      },
    });
    expect(p.badges).toEqual({ firstJump: '2026-10-03T10:00:00.000Z', future: 'beliebig' });
  });

  it('behält unbekannte Felder unverändert', () => {
    const p = sanitizeProgress({ jumps: 3, later: { a: [1, 2] }, note: 'hi' });
    expect(p.later).toEqual({ a: [1, 2] });
    expect(p.note).toBe('hi');
    expect(p.jumps).toBe(3);
  });

  it('rundet Zähler auf ganze, nicht negative Zahlen', () => {
    const p = sanitizeProgress({ jumps: 4.7, finishedRides: -2 });
    expect(p.jumps).toBe(4);
    expect(p.finishedRides).toBe(0);
  });

  it('verändert die Eingabe nicht und teilt keine Objekte mit den Defaults', () => {
    const raw = { courses: { 1: { faults: 0, timeCs: 1, stars: 3 } } };
    const copy = JSON.parse(JSON.stringify(raw));
    const p = sanitizeProgress(raw);
    expect(raw).toEqual(copy);
    expect(p.courses).not.toBe(raw.courses);
    expect(sanitizeProgress(undefined).courses).not.toBe(PROGRESS_DEFAULTS.courses);
  });

  it('übersteht Schlüssel wie __proto__ aus JSON', () => {
    const raw = JSON.parse('{"badges":{"__proto__":{"x":1}},"__proto__":{"y":2}}');
    const p = sanitizeProgress(raw);
    expect(Object.getPrototypeOf(p)).toBe(Object.prototype);
    expect({}.y).toBeUndefined();
  });
});

describe('isBetterResult', () => {
  it('ist ohne bisherige Bestleistung immer besser', () => {
    expect(isBetterResult({ faults: 20, timeCs: 99999 }, undefined)).toBe(true);
    expect(isBetterResult({ faults: 20, timeCs: 99999 }, null)).toBe(true);
  });

  it('wertet zuerst Fehler, dann Zeit in Hundertstel', () => {
    const best = { faults: 4, timeCs: 5000 };
    expect(isBetterResult({ faults: 3, timeCs: 9000 }, best)).toBe(true);
    expect(isBetterResult({ faults: 5, timeCs: 1000 }, best)).toBe(false);
    expect(isBetterResult({ faults: 4, timeCs: 4999 }, best)).toBe(true);
    expect(isBetterResult({ faults: 4, timeCs: 5001 }, best)).toBe(false);
  });

  it('gleiche Fehler und gleiche Zeit sind keine Verbesserung', () => {
    expect(isBetterResult({ faults: 4, timeCs: 5000 }, { faults: 4, timeCs: 5000 })).toBe(false);
  });
});

describe('applyFinishedRide', () => {
  it('erster beendeter Ritt ist immer Bestleistung und speichert Fehler, Zeit, Sterne', () => {
    const r = applyFinishedRide(fresh(), ride(1, 8, 7000));
    expect(r.isNewBest).toBe(true);
    expect(r.progress.courses['1']).toEqual({ faults: 8, timeCs: 7000, stars: 1 });
  });

  it('ersetzt die Bestleistung nur, wenn besser', () => {
    let p = applyFinishedRide(fresh(), ride(1, 4, 6000)).progress;
    const worse = applyFinishedRide(p, ride(1, 4, 6001));
    expect(worse.isNewBest).toBe(false);
    expect(worse.progress.courses['1']).toEqual({ faults: 4, timeCs: 6000, stars: 2 });
    const better = applyFinishedRide(worse.progress, ride(1, 4, 5999));
    expect(better.isNewBest).toBe(true);
    expect(better.progress.courses['1']).toMatchObject({ faults: 4, timeCs: 5999 });
    p = applyFinishedRide(better.progress, ride(1, 1, 9000)).progress;
    expect(p.courses['1']).toMatchObject({ faults: 1, timeCs: 9000 });
  });

  it('beste Sterne sind das Maximum, auch wenn der Ritt schlechter war', () => {
    let p = applyFinishedRide(fresh(), ride(1, 0, 6000)).progress;
    p = applyFinishedRide(p, ride(1, 9, 5000)).progress;
    expect(p.courses['1'].stars).toBe(3);
    expect(p.courses['1']).toMatchObject({ faults: 0, timeCs: 6000 });
    let q = applyFinishedRide(fresh(), ride(2, 9, 5000)).progress;
    q = applyFinishedRide(q, ride(2, 2, 9000)).progress;
    expect(q.courses['2'].stars).toBe(2);
  });

  it('Bestleistung und Sterne verbessern sich unabhängig', () => {
    // schneller bei gleichen Fehlern: Sterne bleiben
    let p = applyFinishedRide(fresh(), ride(3, 2, 6000)).progress;
    p = applyFinishedRide(p, ride(3, 2, 5000)).progress;
    expect(p.courses['3']).toEqual({ faults: 2, timeCs: 5000, stars: 2 });
  });

  it('jeder beendete Ritt schaltet den nächsten Parcours frei (Regel 37)', () => {
    const r = applyFinishedRide(fresh(), ride(1, 12, 9000));
    expect(r.unlockedCourse).toBe(2);
    expect(r.progress.unlocked).toBe(2);
  });

  it('schaltet nichts frei, wenn der nächste Parcours schon offen ist', () => {
    const p = { ...fresh(), unlocked: 4 };
    const r = applyFinishedRide(p, ride(2, 0, 5000));
    expect(r.unlockedCourse).toBeNull();
    expect(r.progress.unlocked).toBe(4);
  });

  it('schaltet beim Erreichen des neuesten Parcours genau einen weiteren frei', () => {
    const r = applyFinishedRide({ ...fresh(), unlocked: 3 }, ride(3, 0, 5000));
    expect(r.unlockedCourse).toBe(4);
    expect(r.progress.unlocked).toBe(4);
  });

  it('Deckel bei 5', () => {
    const r = applyFinishedRide({ ...fresh(), unlocked: 5 }, ride(5, 0, 5000));
    expect(r.unlockedCourse).toBeNull();
    expect(r.progress.unlocked).toBe(5);
    const r4 = applyFinishedRide({ ...fresh(), unlocked: 4 }, ride(4, 0, 5000));
    expect(r4.unlockedCourse).toBe(5);
    expect(r4.progress.unlocked).toBe(5);
  });

  it('zählt beendete Ritte', () => {
    let p = fresh();
    for (let i = 0; i < 3; i++) p = applyFinishedRide(p, ride(1, 0, 5000 + i)).progress;
    expect(p.finishedRides).toBe(3);
  });

  it('ändert Eingabe nicht und lässt andere Felder unberührt', () => {
    const p = { ...fresh(), jumps: 12, extra: { a: 1 }, badges: { firstJump: '2026-01-01' } };
    const snapshot = JSON.parse(JSON.stringify(p));
    const r = applyFinishedRide(p, ride(1, 0, 5000));
    expect(p).toEqual(snapshot);
    expect(r.progress).not.toBe(p);
    expect(r.progress.courses).not.toBe(p.courses);
    expect(r.progress.jumps).toBe(12);
    expect(r.progress.extra).toEqual({ a: 1 });
    expect(r.progress.badges).toEqual({ firstJump: '2026-01-01' });
  });

  it('ignoriert eine ungültige Parcours-Nummer', () => {
    const p = fresh();
    const r = applyFinishedRide(p, ride(9, 0, 5000));
    expect(r).toEqual({ progress: p, isNewBest: false, unlockedCourse: null });
  });
});

describe('addJump', () => {
  it('erhöht nur jumps, unveränderlich', () => {
    const p = fresh();
    const q = addJump(p);
    expect(q.jumps).toBe(1);
    expect(p.jumps).toBe(0);
    expect(addJump(q).jumps).toBe(2);
  });
});

describe('resetProgress', () => {
  it('setzt nur die Felder aus Regel 48 zurück', () => {
    const p = {
      unlocked: 4,
      courses: { 1: { faults: 0, timeCs: 5000, stars: 3 } },
      jumps: 120,
      finishedRides: 9,
      badges: { firstJump: '2026-10-03T10:00:00.000Z', future: 'x' },
      someFutureField: { keep: true },
    };
    const r = resetProgress(p);
    expect(r).toEqual({
      unlocked: 1,
      courses: {},
      jumps: 0,
      finishedRides: 0,
      badges: {},
      someFutureField: { keep: true },
    });
    expect(p.jumps).toBe(120);
  });

  it('erzeugt keinen geteilten Zustand mit den Defaults', () => {
    const r = resetProgress(fresh());
    r.courses['1'] = { faults: 0, timeCs: 1, stars: 3 };
    expect(PROGRESS_DEFAULTS.courses).toEqual({});
  });
});
