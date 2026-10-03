// Parcours-Layouts (Konzept Regel 25) und feste Übungsaufstellung des freien Modus (Regel 41).
// Koordinaten laut docs/specs/springreiten-trainer/architecture.md: Meter, Platz x∈[-20,20],
// z∈[-35,35]; rot so, dass +n = (sin rot, cos rot) die Sprungrichtung ist.
//
// Gebaut nach einfachen Parcoursbau-Regeln: Hindernisse auf Linien mit verwandten Distanzen
// (5 Galoppsprünge), Wendungen mit Radien, die im Galopp reitbar sind (Sim: ca. 8 m bei
// mittlerem Galopp), Handwechsel ab Parcours 2, erstes Hindernis niedrig und gerade
// anzureiten. `track` enthält je Teilstrecke Wegpunkte der Wendungen; daraus ergibt sich die
// Ideallinie für die erlaubte Zeit (scoring.js).
import { COMBI_DISTANCE } from '../sim/tuning.js';
import { allowedTime } from './scoring.js';

const N = 0; // Sprung nach +z
const S = Math.PI; // nach −z
const LINE_LENGTH = 6;
const START_BACK = 5; // Halt so weit vor der Startlinie
const STRIDE = 3.7; // Galoppsprung (m)
const TAKEOFF_LANDING = 1.8; // Landung bzw. Absprung (m)
const LANDING_FREE = 8; // gerade Strecke nach der Landung vor einer Wendung
const DIAG = (15 * Math.PI) / 180; // Winkel der Diagonalen zur Längsachse
const DIAG_EXIT_Z = -21.9; // hier beginnt die Wendung am Ende der Diagonale
const DEG = Math.PI / 180;

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

/**
 * Verwandte Distanz auf einer Linie, Mitte zu Mitte: n Galoppsprünge + Landung + Absprung
 * (Kante zu Kante n · 3,7 m + 3,6 m), dazu die halben Oxer-Tiefen.
 */
export function relatedDistance(strides, spreadFrom = 0, spreadTo = 0) {
  return strides * STRIDE + 2 * TAKEOFF_LANDING + spreadFrom / 2 + spreadTo / 2;
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

// ---- Wegpunkte der Wendungen (Draufsicht, Winkel in Grad wie in der Mathematik: x, z) ----

function arc(cx, cz, r, a0, a1, steps = 3) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const a = (a0 + ((a1 - a0) * i) / steps) * DEG;
    out.push([cx + r * Math.cos(a), cz + r * Math.sin(a)]);
  }
  return out;
}

/**
 * Wendung von einer Längslinie x1 auf die Gegenrichtung in Linie x2, an der oberen (side = +1)
 * oder unteren (side = −1) Querseite: Viertelbogen, Gerade, Viertelbogen mit Radius r.
 */
function turnAround(x1, x2, zc, side, r) {
  const s = Math.sign(x2 - x1);
  const mid = side > 0 ? 90 : s > 0 ? 270 : -90;
  const a0 = s > 0 ? 180 : 0;
  const a1 = side > 0 ? (s > 0 ? 0 : 180) : s > 0 ? 360 : -180;
  return [...arc(x1 + s * r, zc, r, a0, mid, 2), ...arc(x2 - s * r, zc, r, mid, a1, 2)];
}

/** x der Diagonale durch e (nach −z, zur Seite sx) auf Höhe z. */
function diagX(e, sx, z) {
  return e[0] + sx * Math.tan(DIAG) * (e[1] - z);
}

const diagDown = (sx) => heading(sx * Math.sin(DIAG), -Math.cos(DIAG));

/** Von einer Längslinie (nach +z) über die obere Querseite in die Diagonale durch e. */
function intoDiag(fromX, zc, e, sx, r = 8) {
  const endZ = zc - r * Math.sin(DIAG);
  const cx = diagX(e, sx, endZ) + sx * r * Math.cos(DIAG);
  return [
    ...arc(fromX - sx * r, zc, r, sx > 0 ? 0 : 180, 90, 2),
    ...arc(cx, zc, r, 90, 90 + sx * 105),
  ];
}

/** Ende der Diagonale durch e: Wendung auf die Längslinie laneX (nach +z). */
function outOfDiag(e, sx, laneX) {
  const px = diagX(e, sx, DIAG_EXIT_Z);
  const r = Math.abs(laneX - px) / (1 + Math.cos(DIAG));
  const cx = px - sx * r * Math.cos(DIAG);
  const cz = DIAG_EXIT_Z - r * Math.sin(DIAG);
  return arc(cx, cz, r, sx > 0 ? 15 : 165, sx > 0 ? -180 : 360, 4);
}

function course(id, pace, { start, finish, obstacles, track }) {
  const c = {
    id,
    pace,
    obstacles,
    start,
    finish,
    startPose: poseBefore(start),
    track,
    allowedTimeS: 0,
  };
  c.allowedTimeS = allowedTime(c);
  return c;
}

const P2_DIAG = [-4, 3];
const P3_DIAG = [4, 3];
const P4_DIAG = [-4, 3];

export const COURSES = [
  // P1: 4 Kreuze auf einem großen Oval mit zwei geraden Linien – im Trab reitbar
  course(1, 'trot', {
    start: line(-1, -28, Math.PI / 2),
    finish: line(-10, -25, S),
    obstacles: [
      single(1, 'p1-1', 'cross', 0.4, 10, -11, N),
      single(2, 'p1-2', 'cross', 0.45, 10, -11 + relatedDistance(5), N),
      single(3, 'p1-3', 'cross', 0.5, -10, 11, S),
      single(4, 'p1-4', 'cross', 0.5, -10, 11 - relatedDistance(5), S),
    ],
    track: [arc(4, -22, 6, -90, 0, 2), [], turnAround(10, -10, 25, 1, 5), [], []],
  }),
  // P2: Kreuze und Steilsprünge; Achterfigur mit Handwechsel über die Diagonale
  course(2, 'canter', {
    start: line(13, -26, N),
    finish: line(-13, 25, N),
    obstacles: [
      single(1, 'p2-1', 'cross', 0.5, 13, -11, N),
      single(2, 'p2-2', 'vertical', 0.6, 13, -11 + relatedDistance(5), N),
      single(3, 'p2-3', 'vertical', 0.6, ...P2_DIAG, diagDown(1)),
      single(4, 'p2-4', 'cross', 0.5, -13, -10, N),
      single(5, 'p2-5', 'vertical', 0.6, -13, -10 + relatedDistance(5), N),
    ],
    track: [
      [],
      [],
      intoDiag(13, -11 + relatedDistance(5) + LANDING_FREE, P2_DIAG, 1),
      outOfDiag(P2_DIAG, 1, -13),
      [],
      [],
    ],
  }),
  // P3: erster Oxer; gespiegelte Achterfigur, zum Schluss durch die Mitte
  course(3, 'canter', {
    start: line(-14, -26, N),
    finish: line(-7, -12, S),
    obstacles: [
      single(1, 'p3-1', 'cross', 0.5, -14, -11, N),
      single(2, 'p3-2', 'vertical', 0.6, -14, -11 + relatedDistance(5), N),
      single(3, 'p3-3', 'vertical', 0.65, ...P3_DIAG, diagDown(-1)),
      single(4, 'p3-4', 'oxer', 0.7, 14, -9.5, N),
      single(5, 'p3-5', 'vertical', 0.65, 14, -9.5 + relatedDistance(5, 0.7), N),
      single(6, 'p3-6', 'vertical', 0.7, -7, 4, S),
    ],
    track: [
      [],
      [],
      intoDiag(-14, -11 + relatedDistance(5) + LANDING_FREE, P3_DIAG, -1),
      outOfDiag(P3_DIAG, -1, 14),
      [],
      turnAround(14, -7, -9.5 + relatedDistance(5, 0.7) + LANDING_FREE, 1, 8),
      [],
    ],
  }),
  // P4: Steilsprünge und Oxer gemischt; Achterfigur und eine Schlusslinie
  course(4, 'canter', {
    start: line(14, -26, N),
    finish: line(7, -24, S),
    obstacles: [
      single(1, 'p4-1', 'vertical', 0.7, 14, -11, N),
      single(2, 'p4-2', 'oxer', 0.75, 14, -11 + relatedDistance(5, 0, 0.8), N),
      single(3, 'p4-3', 'vertical', 0.75, ...P4_DIAG, diagDown(1)),
      single(4, 'p4-4', 'oxer', 0.8, -14, -9.5, N),
      single(5, 'p4-5', 'vertical', 0.8, -14, -9.5 + relatedDistance(5, 0.8), N),
      single(6, 'p4-6', 'oxer', 0.8, 7, 10, S),
      single(7, 'p4-7', 'vertical', 0.8, 7, 10 - relatedDistance(5, 0.8), S),
    ],
    track: [
      [],
      [],
      intoDiag(14, -11 + relatedDistance(5, 0, 0.8) + 0.4 + LANDING_FREE, P4_DIAG, 1),
      outOfDiag(P4_DIAG, 1, -14),
      [],
      turnAround(-14, 7, 10 + 0.4 + 14, 1, 8),
      [],
      [],
    ],
  }),
  // P5: Handwechsel durch die Mitte (ohne Sprung), Kombination nach weiter Wendung,
  // Schlusslinie mit verwandter Distanz
  course(5, 'canter', {
    start: line(14, -26, N),
    finish: line(-7, 23, N),
    obstacles: [
      single(1, 'p5-1', 'vertical', 0.75, 14, -11, N),
      single(2, 'p5-2', 'oxer', 0.8, 14, -11 + relatedDistance(5, 0, 0.8), N),
      single(3, 'p5-3', 'oxer', 0.8, -14, -11, N),
      single(4, 'p5-4', 'vertical', 0.85, -14, -11 + relatedDistance(5, 0.8), N),
      combination(5, 'p5-5', ['vertical', 0.8], ['oxer', 0.85], 7, 11.5, S),
      single(6, 'p5-6', 'vertical', 0.85, 7, 11.5 - COMBI_DISTANCE - relatedDistance(5, 0.9), S),
      single(7, 'p5-7', 'oxer', 0.85, -7, -11.5, N),
      single(8, 'p5-8', 'vertical', 0.85, -7, -11.5 + relatedDistance(5, 0.9), N),
    ],
    track: [
      [],
      [],
      [
        ...turnAround(14, 0, -11 + relatedDistance(5, 0, 0.8) + 0.4 + LANDING_FREE, 1, 7),
        ...turnAround(0, -14, -11 - 0.4 - 14, -1, 7),
      ],
      [],
      turnAround(-14, 7, 11.5 + 14, 1, 8),
      [],
      turnAround(7, -7, 11.5 - COMBI_DISTANCE - relatedDistance(5, 0.9) - LANDING_FREE, -1, 7),
      [],
      [],
    ],
  }),
];

// Freier Modus: ungerichtet, ohne Nummern; jede Linie aus beiden Richtungen anzureiten,
// an den Querseiten und zwischen den Linien Platz zum Wenden
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
