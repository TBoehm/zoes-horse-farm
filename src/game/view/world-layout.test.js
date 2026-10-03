import { describe, it, expect } from 'vitest';
import { POLE_LENGTH, ARENA } from '../sim/tuning.js';
import { crossAxisOf } from '../sim/geometry.js';
import {
  axesOf,
  flagSides,
  localToWorld,
  polesOf,
  standRows,
  standHeight,
  labelOf,
  highlightText,
  fallCurve,
  endProgress,
  fallPoint,
  fallTarget,
  aidPlacement,
  lineSegment,
  planLines,
  planFence,
  FENCE,
  GATE,
  terrainHeight,
  isBlocked,
  scatter,
  instanceCount,
  POLE_RADIUS,
  STAND_X,
} from './world-layout.js';

const seq = (...values) => {
  let i = 0;
  return () => values[i++ % values.length];
};

describe('Achsen und Fahnen', () => {
  it('t entspricht crossAxisOf der Simulation', () => {
    for (const rot of [0, 0.7, Math.PI, -2]) {
      const { t } = axesOf(rot);
      const ref = crossAxisOf({ rot });
      expect(t.x).toBeCloseTo(ref.x);
      expect(t.z).toBeCloseTo(ref.z);
    }
  });

  it('rote Fahne steht auf der +t-Seite', () => {
    for (const rot of [0, 1.2, Math.PI, 4]) {
      const el = { x: 3, z: -2, rot };
      const p = localToWorld(el, flagSides().red * STAND_X, 0);
      const { t } = axesOf(rot);
      expect((p.x - el.x) * t.x + (p.z - el.z) * t.z).toBeCloseTo(STAND_X);
      const w = localToWorld(el, flagSides().white * STAND_X, 0);
      expect((w.x - el.x) * t.x + (w.z - el.z) * t.z).toBeCloseTo(-STAND_X);
    }
  });

  it('lokales +Z zeigt in Sprungrichtung n', () => {
    const el = { x: 1, z: 1, rot: 0.9 };
    const p = localToWorld(el, 0, 2);
    const { n } = axesOf(0.9);
    expect(p.x).toBeCloseTo(1 + 2 * n.x);
    expect(p.z).toBeCloseTo(1 + 2 * n.z);
  });
});

describe('polesOf', () => {
  it('Steilsprung: obere Stange rail 0 mit Oberkante auf height', () => {
    const poles = polesOf({ kind: 'vertical', height: 0.6 });
    expect(poles.filter((p) => p.rail === 0)).toHaveLength(1);
    expect(poles[0].a[1] + POLE_RADIUS).toBeCloseTo(0.6);
    expect(poles.every((p) => p.rail <= 0)).toBe(true);
  });

  it('hoher Steilsprung hat zusätzlich eine feste Füllstange', () => {
    const poles = polesOf({ kind: 'vertical', height: 0.85 });
    expect(poles.filter((p) => p.rail === -1)).toHaveLength(1);
  });

  it('Oxer: vordere rail 0 bei −spread/2, hintere rail 1 bei +spread/2, beide auf height', () => {
    const poles = polesOf({ kind: 'oxer', height: 0.8, spread: 1 });
    const r0 = poles.find((p) => p.rail === 0);
    const r1 = poles.find((p) => p.rail === 1);
    expect(r0.a[2]).toBeCloseTo(-0.5);
    expect(r1.a[2]).toBeCloseTo(0.5);
    expect(r0.a[1]).toBeCloseTo(r1.a[1]);
    expect(r0.a[1] + POLE_RADIUS).toBeCloseTo(0.8);
  });

  it('Kreuz: zwei gekreuzte Stangen als rail 0, Kreuzungspunkt auf height, plus Bodenstange', () => {
    const poles = polesOf({ kind: 'cross', height: 0.5 });
    const crossed = poles.filter((p) => p.rail === 0);
    expect(crossed).toHaveLength(2);
    for (const p of crossed) {
      const mid = (p.a[1] + p.b[1]) / 2;
      expect(mid + POLE_RADIUS).toBeCloseTo(0.5);
    }
    expect(poles.filter((p) => p.rail === -1)).toHaveLength(1);
  });

  it('Stangen liegen zwischen den Ständern (Länge < POLE_LENGTH)', () => {
    const [p] = polesOf({ kind: 'vertical', height: 0.6 });
    expect(p.b[0] - p.a[0]).toBeLessThanOrEqual(POLE_LENGTH);
    expect(p.b[0] - p.a[0]).toBeGreaterThan(POLE_LENGTH - 0.05);
  });

  it('Ständerreihen und -höhe', () => {
    expect(standRows({ kind: 'oxer', spread: 1.2 })).toEqual([-0.6, 0.6]);
    expect(standRows({ kind: 'cross' })).toEqual([0]);
    expect(standHeight({ height: 0.4 })).toBeGreaterThan(0.4 + 0.5);
    expect(standHeight({ height: 1.2 })).toBeGreaterThan(1.2);
  });
});

describe('Bezeichnungen', () => {
  it('Nummer, Kombination mit a/b, freier Modus ohne', () => {
    expect(labelOf({ number: 3, elements: [{}] }, 0)).toBe('3');
    expect(labelOf({ number: 5, elements: [{}, {}] }, 1)).toBe('5b');
    expect(labelOf({ number: null, elements: [{}] }, 0)).toBeNull();
    expect(highlightText({ number: 5, elements: [{}, {}] }, 0, 5)).toBe('5a');
    expect(highlightText({ number: 2, elements: [{}] }, 0, 2)).toBe('2');
    expect(highlightText({ number: null, elements: [{}] }, 0, null)).toBeNull();
  });
});

describe('Stangenfall', () => {
  it('Fallkurve startet bei 0, endet bei 1, mit kleinem Nachhüpfen', () => {
    expect(fallCurve(0)).toBe(0);
    expect(fallCurve(1)).toBe(1);
    expect(fallCurve(0.78)).toBeCloseTo(1);
    expect(fallCurve(0.89)).toBeLessThan(1);
    expect(fallCurve(0.89)).toBeGreaterThan(0.85);
    for (let t = 0; t < 0.78; t += 0.05)
      expect(fallCurve(t + 0.05)).toBeGreaterThanOrEqual(fallCurve(t));
  });

  it('ein Ende fällt zuerst, beide kommen bei t = 1 an', () => {
    const mid = endProgress(0.5, 0);
    expect(mid.a).toBeGreaterThan(mid.b);
    const other = endProgress(0.5, 1);
    expect(other.b).toBeGreaterThan(other.a);
    expect(endProgress(1, 0)).toEqual({ a: 1, b: 1 });
    expect(endProgress(0, 1)).toEqual({ a: 0, b: 0 });
  });

  it('fallPoint interpoliert von Auflage zu Boden', () => {
    expect(fallPoint([0, 0.8, 0], [0.2, 0.05, 1], 0)).toEqual([0, 0.8, 0]);
    const end = fallPoint([0, 0.8, 0], [0.2, 0.05, 1], 1);
    expect(end[0]).toBeCloseTo(0.2);
    expect(end[1]).toBeCloseTo(0.05);
    expect(end[2]).toBeCloseTo(1);
  });

  it('gefallene Stange liegt auf dem Sand auf der Fallseite, Länge bleibt', () => {
    for (const side of [1, -1]) {
      const t = fallTarget([0, 0.6, 0.4], 3.48, side, seq(0.5, 0.9, 0.1, 0.3, 0.7));
      expect(t.a[1]).toBe(POLE_RADIUS);
      expect(t.b[1]).toBe(POLE_RADIUS);
      expect(Math.sign((t.a[2] + t.b[2]) / 2 - 0.4)).toBe(side);
      expect(Math.hypot(t.b[0] - t.a[0], t.b[2] - t.a[2])).toBeCloseTo(3.48);
      expect(Math.sign(t.roll)).toBe(side);
    }
  });
});

describe('aidPlacement', () => {
  const zone = { far: 3, near: 1 };

  it('Steilsprung rot 0, Anreiten in +n: Band vor dem Hindernis (z < 0)', () => {
    const p = aidPlacement({ kind: 'vertical', x: 0, z: 0, rot: 0 }, 1, zone);
    expect(p.x).toBeCloseTo(0);
    expect(p.z).toBeCloseTo(-2);
    expect(p.depth).toBeCloseTo(2);
    expect(p.width).toBe(POLE_LENGTH);
  });

  it('Oxer: gemessen ab der Vorderkante (spread/2), beide Richtungen', () => {
    const el = { kind: 'oxer', spread: 1.2, x: 5, z: 5, rot: Math.PI / 2 };
    const p = aidPlacement(el, 1, zone);
    // n = (1, 0): Vorderkante bei x = 4,4, Bandmitte 2 m davor
    expect(p.x).toBeCloseTo(2.4);
    expect(p.z).toBeCloseTo(5);
    const q = aidPlacement(el, -1, zone);
    expect(q.x).toBeCloseTo(7.6);
    expect(q.rotY).toBeCloseTo(Math.PI / 2);
  });

  it('vertauschte oder leere Zonen', () => {
    const el = { kind: 'vertical', x: 0, z: 0, rot: 0 };
    expect(aidPlacement(el, 1, { far: 1, near: 3 }).z).toBeCloseTo(-2);
    expect(aidPlacement(el, 1, { far: 2, near: 2 })).toBeNull();
    expect(aidPlacement(null, 1, zone)).toBeNull();
    expect(aidPlacement(el, 1, null)).toBeNull();
  });
});

describe('Linien', () => {
  it('lineSegment akzeptiert Arrays und Objekte', () => {
    const s = lineSegment([0, 0], { x: 0, z: 4 });
    expect(s.length).toBeCloseTo(4);
    expect(s.cz).toBeCloseTo(2);
    expect(s.angle).toBeCloseTo(0);
  });

  it('planLines: getrennte Schilder mit übergebenen Texten', () => {
    const plan = planLines({
      start: { a: [0, 0], b: [4, 0] },
      finish: { a: [0, 10], b: [4, 10] },
      labels: { start: 'Start', finish: 'Finish' },
    });
    expect(plan.map((p) => p.text)).toEqual(['Start', 'Finish']);
    expect(plan[1].finish).toBe(true);
  });

  it('planLines: gleiche Linie ergibt ein gemeinsames Schild', () => {
    const line = { a: [0, 0], b: [4, 0] };
    const plan = planLines({ start: line, finish: line, labels: { start: 'S', finish: 'Z' } });
    expect(plan).toHaveLength(1);
    expect(plan[0].text).toBe('S · Z');
    expect(plan[0].finish).toBe(true);
    expect(planLines(null)).toEqual([]);
  });
});

describe('planFence', () => {
  const plan = planFence({ pathFence: [{ a: [-21, 24], b: [-35, 24] }] });

  it('Pfostenabstand höchstens 2,5 m, Zaun außerhalb der Reitfläche', () => {
    const arena = plan.segments.filter((s) => s.style === 'arena');
    expect(arena.every((s) => s.len <= FENCE.spacing + 1e-9)).toBe(true);
    const posts = plan.posts.filter((p) => p.style === 'arena');
    expect(
      posts.every((p) => Math.abs(p.x) >= ARENA.width / 2 || Math.abs(p.z) >= ARENA.length / 2),
    ).toBe(true);
  });

  it('Torlücke ohne Zaunteile', () => {
    const inGap = plan.segments.filter(
      (s) =>
        s.style === 'arena' &&
        s.x < 0 &&
        Math.abs(s.z - GATE.z) < GATE.width / 2 - 0.1 &&
        Math.abs(s.x + 20.18) < 0.1,
    );
    expect(inGap).toHaveLength(0);
    expect(plan.gate.z1 - plan.gate.z0).toBeCloseTo(GATE.width);
  });

  it('keine doppelten Pfosten, Wegzaun aus Holz', () => {
    const keys = plan.posts.map((p) => `${p.x.toFixed(2)},${p.z.toFixed(2)}`);
    expect(new Set(keys).size).toBe(keys.length);
    expect(plan.posts.some((p) => p.style === 'wood')).toBe(true);
  });
});

describe('Umgebung', () => {
  it('Gelände ist um die Anlage flach und steigt zum Horizont', () => {
    expect(terrainHeight(0, 0)).toBe(0);
    expect(terrainHeight(60, 40)).toBe(0);
    expect(terrainHeight(300, 0)).toBeGreaterThan(5);
  });

  it('Reitplatz ist für Pflanzen gesperrt', () => {
    expect(isBlocked(0, 0)).toBe(true);
    expect(isBlocked(60, 60)).toBe(false);
  });

  it('scatter liefert Punkte im Ring außerhalb gesperrter Flächen', () => {
    let s = 1;
    const rng = () => (s = (s * 16807) % 2147483647) / 2147483647;
    const pts = scatter(rng, 50, 30, 80, 1);
    expect(pts).toHaveLength(50);
    for (const [x, z] of pts) {
      const r = Math.hypot(x, z);
      expect(r).toBeGreaterThanOrEqual(30 - 1e-9);
      expect(r).toBeLessThanOrEqual(80 + 1e-9);
      expect(isBlocked(x, z, 1)).toBe(false);
    }
  });

  it('instanceCount: Pflicht-Instanzen immer, Rest nach Dichte', () => {
    expect(instanceCount(100, 10, 0)).toBe(10);
    expect(instanceCount(100, 10, 1)).toBe(100);
    expect(instanceCount(100, 10, 0.5)).toBe(55);
    expect(instanceCount(5, 10, 0.5)).toBe(5);
  });
});
