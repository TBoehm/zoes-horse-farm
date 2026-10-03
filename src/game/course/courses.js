// Parcours-Layouts (Konzept Regel 25) und feste Übungsaufstellung des freien Modus (Regel 41).
// Koordinaten laut docs/specs/springreiten-trainer/architecture.md: Meter, Platz x∈[-20,20],
// z∈[-35,35]; rot so, dass +n = (sin rot, cos rot) die Sprungrichtung ist.
import { COMBI_DISTANCE } from '../sim/tuning.js';
import { allowedTime } from './scoring.js';

const N = 0; // Sprung nach +z
const S = Math.PI; // nach −z
const LINE_LENGTH = 6;
const START_BACK = 5; // Halt so weit vor der Startlinie
const STRIDE = 3.7; // Galoppsprung (m)
const TAKEOFF_LANDING = 1.8; // Landung bzw. Absprung (m)

function heading(dx, dz) {
  return Math.atan2(dx, dz);
}

function spreadFor(kind, height) {
  if (kind !== 'oxer') return 0;
  if (height <= 0.7) return 0.7;
  if (height <= 0.8) return 0.8;
  return 0.9;
}

function element(id, kind, height, x, z, rot) {
  return { id, kind, height, spread: spreadFor(kind, height), x, z, rot };
}

function single(number, id, kind, height, x, z, rot, directed = true) {
  return { number, elements: [element(id, kind, height, x, z, rot)], directed };
}

/** Zweifach-Kombination: a bei (x, z), b im Abstand COMBI_DISTANCE in Richtung +n. */
function combination(number, id, a, b, x, z, rot, directed = true) {
  const bx = x + Math.sin(rot) * COMBI_DISTANCE;
  const bz = z + Math.cos(rot) * COMBI_DISTANCE;
  return {
    number,
    elements: [
      element(`${id}a`, a[0], a[1], x, z, rot),
      element(`${id}b`, b[0], b[1], bx, bz, rot),
    ],
    directed,
  };
}

/** Linie (ca. 6 m) quer zur Ritt-Richtung rot durch (x, z). */
function line(x, z, rot) {
  const dir = [Math.sin(rot), Math.cos(rot)];
  const h = LINE_LENGTH / 2;
  return {
    a: [x + dir[1] * h, z - dir[0] * h],
    b: [x - dir[1] * h, z + dir[0] * h],
    dir,
  };
}

function poseBefore(start) {
  const mx = (start.a[0] + start.b[0]) / 2;
  const mz = (start.a[1] + start.b[1]) / 2;
  return {
    x: mx - start.dir[0] * START_BACK,
    z: mz - start.dir[1] * START_BACK,
    heading: heading(start.dir[0], start.dir[1]),
  };
}

function course(id, pace, start, finish, obstacles) {
  const c = { id, pace, obstacles, start, finish, startPose: poseBefore(start), allowedTimeS: 0 };
  c.allowedTimeS = allowedTime(c);
  return c;
}

// Diagonale mit 15° zur Längsachse, nach unten (−z) und zur Seite sx (+1 = +x, −1 = −x)
const DIAG = (15 * Math.PI) / 180;
const diagDown = (sx) => heading(sx * Math.sin(DIAG), -Math.cos(DIAG));

/** Punkt im Abstand d entlang der Diagonale. */
function alongDiag(x, z, sx, d) {
  return [x + sx * Math.sin(DIAG) * d, z - Math.cos(DIAG) * d];
}

/**
 * Lage des nächsten Hindernisses auf einer Linie: verwandte Distanz von n Galoppsprüngen
 * (Kante zu Kante n · 3,7 m + 1,8 m Landung + 1,8 m Absprung), Mitte zu Mitte inkl. Oxer-Tiefen.
 */
export function relatedDistance(strides, spreadFrom = 0, spreadTo = 0) {
  return strides * STRIDE + 2 * TAKEOFF_LANDING + spreadFrom / 2 + spreadTo / 2;
}

const [P5A_X, P5A_Z] = alongDiag(2.1, -21.9, 1, -(8.45 + COMBI_DISTANCE));

export const COURSES = [
  // P1: 4 Kreuze auf einem großen Oval, gerade Linien – im Trab reitbar
  course(1, 'trot', line(-1, -28, Math.PI / 2), line(-10, -25, S), [
    single(1, 'p1-1', 'cross', 0.4, 10, -11, N),
    single(2, 'p1-2', 'cross', 0.45, 10, -11 + relatedDistance(5), N),
    single(3, 'p1-3', 'cross', 0.5, -10, 11, S),
    single(4, 'p1-4', 'cross', 0.5, -10, 11 - relatedDistance(5), S),
  ]),
  // P2: Kreuze und Steilsprünge, Handwechsel über die Diagonale (Achterfigur)
  course(2, 'canter', line(13, -26, N), line(-13, 25, N), [
    single(1, 'p2-1', 'cross', 0.5, 13, -11, N),
    single(2, 'p2-2', 'vertical', 0.6, 13, -11 + relatedDistance(5), N),
    single(3, 'p2-3', 'vertical', 0.6, -4, 3, diagDown(1)),
    single(4, 'p2-4', 'cross', 0.5, -13, -10, N),
    single(5, 'p2-5', 'vertical', 0.6, -13, -10 + relatedDistance(5), N),
  ]),
  // P3: erster Oxer; gespiegelte Achterfigur, zum Schluss durch die Mitte
  course(3, 'canter', line(-14, -26, N), line(-7, -12, S), [
    single(1, 'p3-1', 'cross', 0.5, -14, -11, N),
    single(2, 'p3-2', 'vertical', 0.6, -14, -11 + relatedDistance(5), N),
    single(3, 'p3-3', 'vertical', 0.65, 4, 3, diagDown(-1)),
    single(4, 'p3-4', 'oxer', 0.7, 14, -9.5, N),
    single(5, 'p3-5', 'vertical', 0.65, 14, -9.5 + relatedDistance(5, 0.7), N),
    single(6, 'p3-6', 'vertical', 0.7, -7, 4, S),
  ]),
  // P4: Steilsprünge und Oxer gemischt; Achterfigur und Schlusslinie
  course(4, 'canter', line(14, -26, N), line(7, -24, S), [
    single(1, 'p4-1', 'vertical', 0.7, 14, -11, N),
    single(2, 'p4-2', 'oxer', 0.75, 14, -11 + relatedDistance(5, 0, 0.8), N),
    single(3, 'p4-3', 'vertical', 0.75, -4, 3, diagDown(1)),
    single(4, 'p4-4', 'oxer', 0.8, -14, -9.5, N),
    single(5, 'p4-5', 'vertical', 0.8, -14, -9.5 + relatedDistance(5, 0.8), N),
    single(6, 'p4-6', 'oxer', 0.8, 7, 10, S),
    single(7, 'p4-7', 'vertical', 0.8, 7, 10 - relatedDistance(5, 0.8), S),
  ]),
  // P5: Kombination auf der Diagonale nach weitem Bogen, Handwechsel, Schluss über die Mitte
  course(5, 'canter', line(14, -26, N), line(-7, 10, N), [
    single(1, 'p5-1', 'vertical', 0.75, 14, -11, N),
    single(2, 'p5-2', 'oxer', 0.8, 14, -11 + relatedDistance(5, 0, 0.8), N),
    combination(3, 'p5-3', ['vertical', 0.8], ['oxer', 0.85], P5A_X, P5A_Z, diagDown(1)),
    single(4, 'p5-4', 'oxer', 0.8, -14, -9.5, N),
    single(5, 'p5-5', 'vertical', 0.85, -14, -9.5 + relatedDistance(5, 0.8), N),
    single(6, 'p5-6', 'oxer', 0.85, 7, 10, S),
    single(7, 'p5-7', 'vertical', 0.85, 7, 10 - relatedDistance(5, 0.9), S),
    single(8, 'p5-8', 'oxer', 0.85, -7, -11, N),
  ]),
];

// Freier Modus: ungerichtet, ohne Nummern, aus beiden Richtungen anzureiten
export const FREE_LAYOUT = {
  obstacles: [
    single(null, 'f1', 'cross', 0.4, 13, -12, N, false),
    single(null, 'f2', 'vertical', 0.6, 13, 12, N, false),
    single(null, 'f3', 'oxer', 0.7, -13, -12, N, false),
    single(null, 'f4', 'oxer', 0.85, -13, 12, N, false),
    combination(null, 'f5', ['vertical', 0.65], ['oxer', 0.75], 0, -COMBI_DISTANCE / 2, N, false),
  ],
  startPose: { x: -6.5, z: -26, heading: 0 },
};
