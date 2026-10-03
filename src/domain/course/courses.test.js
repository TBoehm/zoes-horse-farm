import { describe, expect, it } from 'vitest';
import { ARENA, COMBI_DISTANCE } from '../sim/tuning.js';
import { axisOf, toLocal } from '../sim/geometry.js';
import { COURSES, FREE_LAYOUT, courseById, relatedDistance } from './courses.js';
import { checkLayout, corridorOf, footprint, rectsOverlap } from './layout-check.js';

const elementsOf = (layout) => layout.obstacles.flatMap((o) => o.elements);
const kindsOf = (course) => new Set(elementsOf(course).map((e) => e.kind));
const maxHeight = (course) => Math.max(...elementsOf(course).map((e) => e.height));
const cm = (m) => Math.round(m * 100);
const isCombination = (o) => o.elements.length === 2;
const mid = (l) => [(l.a[0] + l.b[0]) / 2, (l.a[1] + l.b[1]) / 2];

describe('Regel 25: fünf Parcours', () => {
  it('gibt es mit den Nummern 1 bis 5', () => {
    expect(COURSES.map((c) => c.id)).toEqual([1, 2, 3, 4, 5]);
  });

  it('haben die vorgeschriebene Anzahl Hindernisse (Kombination zählt als eins)', () => {
    expect(COURSES.slice(0, 4).map((c) => c.obstacles.length)).toEqual([4, 5, 6, 7]);
    const n5 = COURSES[4].obstacles.length;
    expect(n5).toBeGreaterThanOrEqual(8);
    expect(n5).toBeLessThanOrEqual(10);
  });

  it('P1: nur Kreuze, 40–50 cm', () => {
    const p1 = COURSES[0];
    expect([...kindsOf(p1)]).toEqual(['cross']);
    for (const e of elementsOf(p1)) {
      expect(cm(e.height)).toBeGreaterThanOrEqual(40);
      expect(cm(e.height)).toBeLessThanOrEqual(50);
    }
  });

  it('P2: Kreuze und Steilsprünge, Steilsprünge 60 cm', () => {
    const p2 = COURSES[1];
    expect([...kindsOf(p2)].sort()).toEqual(['cross', 'vertical']);
    for (const e of elementsOf(p2)) {
      if (e.kind === 'vertical') expect(cm(e.height)).toBe(60);
      else expect(cm(e.height)).toBeLessThanOrEqual(60);
    }
  });

  it('P3: Kreuze, Steilsprünge, erster Oxer, bis 70 cm', () => {
    const p3 = COURSES[2];
    expect([...kindsOf(p3)].sort()).toEqual(['cross', 'oxer', 'vertical']);
    expect(cm(maxHeight(p3))).toBeLessThanOrEqual(70);
  });

  it('P4: Steilsprünge und Oxer gemischt, bis 80 cm', () => {
    const p4 = COURSES[3];
    expect([...kindsOf(p4)].sort()).toEqual(['oxer', 'vertical']);
    expect(cm(maxHeight(p4))).toBeLessThanOrEqual(80);
  });

  it('P5: Steilsprünge, Oxer, mindestens eine Zweifach-Kombination, bis 85 cm', () => {
    const p5 = COURSES[4];
    expect([...kindsOf(p5)].sort()).toEqual(['oxer', 'vertical']);
    expect(p5.obstacles.filter(isCombination).length).toBeGreaterThanOrEqual(1);
    expect(cm(maxHeight(p5))).toBeLessThanOrEqual(85);
  });

  it('Kombination nur in Parcours 5', () => {
    for (const c of COURSES.slice(0, 4)) expect(c.obstacles.some(isCombination)).toBe(false);
  });

  it('jeder Parcours wird schwerer oder bleibt gleich hoch', () => {
    const heights = COURSES.map(maxHeight);
    for (let i = 1; i < heights.length; i++) {
      expect(heights[i]).toBeGreaterThanOrEqual(heights[i - 1]);
    }
  });
});

describe('Hindernis-Daten im Parcours', () => {
  it('Nummern 1..n in Reihenfolge, alle gerichtet', () => {
    for (const c of COURSES) {
      expect(c.obstacles.map((o) => o.number)).toEqual(c.obstacles.map((_, i) => i + 1));
      expect(c.obstacles.every((o) => o.directed === true)).toBe(true);
    }
  });

  it('Element-IDs sind eindeutig (auch über Parcours und freien Modus hinweg)', () => {
    const ids = [...COURSES.flatMap(elementsOf), ...elementsOf(FREE_LAYOUT)].map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const c of COURSES) {
      for (const o of c.obstacles) {
        const ids = o.elements.map((e) => e.id);
        if (isCombination(o))
          expect(ids).toEqual([`p${c.id}-${o.number}a`, `p${c.id}-${o.number}b`]);
        else expect(ids).toEqual([`p${c.id}-${o.number}`]);
      }
    }
  });

  it('Oxer haben eine Tiefe, Kreuze und Steilsprünge nicht', () => {
    for (const e of [...COURSES.flatMap(elementsOf), ...elementsOf(FREE_LAYOUT)]) {
      if (e.kind === 'oxer') expect(e.spread).toBeGreaterThan(0.5);
      else expect(e.spread).toBe(0);
    }
  });

  it('Kombination: b liegt COMBI_DISTANCE hinter a in Sprungrichtung', () => {
    const combos = [...COURSES, FREE_LAYOUT].flatMap((l) => l.obstacles.filter(isCombination));
    expect(combos.length).toBeGreaterThan(0);
    for (const o of combos) {
      const [a, b] = o.elements;
      expect(b.rot).toBe(a.rot);
      const local = toLocal(a, b.x, b.z);
      expect(local.along).toBeCloseTo(COMBI_DISTANCE, 6);
      expect(local.across).toBeCloseTo(0, 6);
    }
  });

  it('Hindernisse auf einer Linie stehen in verwandter Distanz (5–6 Galoppsprünge)', () => {
    let lines = 0;
    for (const c of COURSES) {
      for (let i = 1; i < c.obstacles.length; i++) {
        const prev = c.obstacles[i - 1].elements.at(-1);
        const next = c.obstacles[i].elements[0];
        const local = toLocal(prev, next.x, next.z);
        if (prev.rot !== next.rot || Math.abs(local.across) > 1e-6 || local.along <= 0) continue;
        lines++;
        const ok = [5, 6].some(
          (n) => Math.abs(local.along - relatedDistance(n, prev.spread, next.spread)) < 1e-6,
        );
        expect(ok, `${prev.id} → ${next.id}: ${local.along.toFixed(2)} m`).toBe(true);
      }
    }
    expect(lines).toBeGreaterThanOrEqual(8);
  });

  it('erstes Hindernis ist das niedrigste (einladend)', () => {
    for (const c of COURSES) {
      const lowest = Math.min(...elementsOf(c).map((e) => e.height));
      expect(c.obstacles[0].elements[0].height).toBe(lowest);
    }
  });
});

describe('Platz, Anreit-Korridore, Linien', () => {
  it.each(COURSES.map((c) => [c.id, c]))(
    'Parcours %i: Zaunabstand, freie Korridore, keine Überlappung, Linien frei',
    (_id, c) => {
      const lines = [
        { name: 'start', ...c.start },
        { name: 'ziel', ...c.finish },
      ];
      expect(checkLayout(c.obstacles, { lines })).toEqual([]);
    },
  );

  it('Start- und Ziellinie: ca. 6 m, Richtung senkrecht zur Linie', () => {
    for (const c of COURSES) {
      for (const l of [c.start, c.finish]) {
        const dx = l.b[0] - l.a[0];
        const dz = l.b[1] - l.a[1];
        expect(Math.hypot(dx, dz)).toBeCloseTo(6, 6);
        expect(Math.hypot(l.dir[0], l.dir[1])).toBeCloseTo(1, 9);
        expect(dx * l.dir[0] + dz * l.dir[1]).toBeCloseTo(0, 9);
      }
    }
  });

  it('Startposition: im Halt einige Meter vor der Startlinie, Blick zur Linie', () => {
    for (const c of COURSES) {
      const [mx, mz] = mid(c.start);
      const p = c.startPose;
      const behind = (p.x - mx) * c.start.dir[0] + (p.z - mz) * c.start.dir[1];
      expect(behind).toBeLessThanOrEqual(-3);
      expect(behind).toBeGreaterThanOrEqual(-7);
      expect(Math.sin(p.heading)).toBeCloseTo(c.start.dir[0], 9);
      expect(Math.cos(p.heading)).toBeCloseTo(c.start.dir[1], 9);
      expect(Math.abs(p.x)).toBeLessThanOrEqual(ARENA.width / 2 - 3);
      expect(Math.abs(p.z)).toBeLessThanOrEqual(ARENA.length / 2 - 3);
    }
  });

  it('Ideallinien-Wegpunkte liegen im Platz (je Teilstrecke ein Eintrag)', () => {
    for (const c of COURSES) {
      expect(c.track.length).toBe(c.obstacles.length + 1);
      for (const [x, z] of c.track.flat()) {
        expect(Math.abs(x)).toBeLessThanOrEqual(ARENA.width / 2 - 1);
        expect(Math.abs(z)).toBeLessThanOrEqual(ARENA.length / 2 - 1);
      }
    }
  });

  it('Ideallinien-Wegpunkte liegen in keinem Hindernis', () => {
    for (const c of COURSES) {
      const fps = elementsOf(c).map(footprint);
      for (const [x, z] of c.track.flat()) {
        const dot = { cx: x, cz: z, ux: 0, uz: 1, halfAlong: 0.5, halfAcross: 0.5 };
        expect(fps.some((fp) => rectsOverlap(dot, fp))).toBe(false);
      }
    }
  });
});

describe('Regel 41: freier Modus', () => {
  const free = FREE_LAYOUT;

  it('enthält mindestens je ein Kreuz, einen Steilsprung, einen Oxer und eine Kombination', () => {
    const singles = free.obstacles.filter((o) => !isCombination(o)).flatMap((o) => o.elements);
    const kinds = new Set(singles.map((e) => e.kind));
    expect(kinds.has('cross')).toBe(true);
    expect(kinds.has('vertical')).toBe(true);
    expect(kinds.has('oxer')).toBe(true);
    expect(free.obstacles.some(isCombination)).toBe(true);
  });

  it('Höhen zwischen 40 und 85 cm, verteilt', () => {
    const heights = elementsOf(free).map((e) => cm(e.height));
    expect(Math.min(...heights)).toBe(40);
    expect(Math.max(...heights)).toBe(85);
    expect(new Set(heights).size).toBeGreaterThanOrEqual(5);
  });

  it('ungerichtet und ohne Nummern', () => {
    for (const o of free.obstacles) {
      expect(o.directed).toBe(false);
      expect(o.number).toBeNull();
    }
  });

  it('Platz zum Anreiten aus beiden Richtungen, Zaunabstand, keine Überlappung', () => {
    expect(checkLayout(free.obstacles)).toEqual([]);
  });

  it('Startposition liegt frei, außerhalb aller Korridore', () => {
    const p = free.startPose;
    const dot = { cx: p.x, cz: p.z, ux: 0, uz: 1, halfAlong: 1.5, halfAcross: 1.5 };
    for (const o of free.obstacles) expect(rectsOverlap(dot, corridorOf(o))).toBe(false);
    expect(Math.abs(p.x)).toBeLessThanOrEqual(ARENA.width / 2 - 4);
    expect(Math.abs(p.z)).toBeLessThanOrEqual(ARENA.length / 2 - 4);
    expect(axisOf({ rot: p.heading }).z).toBeCloseTo(1, 9);
  });
});

describe('courseById', () => {
  it('finds a course by number or numeric string', () => {
    expect(courseById(3)).toBe(COURSES[2]);
    expect(courseById('2')).toBe(COURSES[1]);
  });

  it('falls back to the first course for unknown ids', () => {
    expect(courseById(99)).toBe(COURSES[0]);
    expect(courseById(undefined)).toBe(COURSES[0]);
    expect(courseById('x')).toBe(COURSES[0]);
  });
});
