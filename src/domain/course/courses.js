// Course layouts (concept rule 25) and the fixed practice layout of free mode (rule 41).
// Coordinates per docs/specs/springreiten-trainer/architecture.md: meters, arena x∈[-20,20],
// z∈[-35,35]; rot such that +n = (sin rot, cos rot) is the jump direction.
//
// Built following simple course-building rules: obstacles on lines with related distances
// (5 canter strides), turns with radii that are rideable at canter (sim: about 8 m at
// medium canter), change of rein from course 2, first obstacle low and approached
// straight. `track` holds the turn waypoints per leg; they yield the ideal line for the
// allowed time (scoring.js).
import { COMBI_DISTANCE, TUNING } from '../sim/tuning.js';
import { allowedTime } from './scoring.js';

const N = 0; // jump toward +z
const S = Math.PI; // toward −z
const LINE_LENGTH = 6;
const START_BACK = 5; // halt this far before the start line
const LANDING_FREE = TUNING.course.landingFree; // straight stretch after landing before a turn
const DIAG = (15 * Math.PI) / 180; // angle of the diagonal to the longitudinal axis
const DIAG_EXIT_Z = -21.9; // the turn at the end of the diagonal starts here
const DEG = Math.PI / 180;

function heading(dx, dz) {
  return Math.atan2(dx, dz);
}

function spreadFor(kind, height) {
  if (kind !== 'oxer') return 0;
  const { byMaxHeight, tall } = TUNING.course.oxerSpread;
  return byMaxHeight.find((e) => height <= e.maxHeight)?.spread ?? tall;
}

/** Oxer depth for a given oxer height (tuning). */
const oxerSpread = (height) => spreadFor('oxer', height);

function element(id, kind, height, x, z, rot) {
  return { id, kind, height, spread: spreadFor(kind, height), x, z, rot };
}

function single(number, id, kind, height, x, z, rot, directed = true) {
  return { number, elements: [element(id, kind, height, x, z, rot)], directed };
}

/** Double combination: a at (x, z), b at distance COMBI_DISTANCE in direction +n. */
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
 * Related distance on a line, center to center: n canter strides + landing + takeoff
 * (edge to edge n · 3.7 m + 3.6 m), plus half the oxer depths.
 */
export function relatedDistance(strides, spreadFrom = 0, spreadTo = 0) {
  const { stride, takeoffLanding } = TUNING.course;
  return strides * stride + 2 * takeoffLanding + spreadFrom / 2 + spreadTo / 2;
}

/** Line (about 6 m) across the riding direction rot through (x, z). */
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

// ---- Turn waypoints (top view, angles in degrees as in mathematics: x, z) ----

function arc(cx, cz, r, a0, a1, steps = 3) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const a = (a0 + ((a1 - a0) * i) / steps) * DEG;
    out.push([cx + r * Math.cos(a), cz + r * Math.sin(a)]);
  }
  return out;
}

/**
 * Turn from a longitudinal line x1 to the opposite direction on line x2, at the upper (side = +1)
 * or lower (side = −1) short side: quarter arc, straight, quarter arc with radius r.
 */
function turnAround(x1, x2, zc, side, r) {
  const s = Math.sign(x2 - x1);
  const mid = side > 0 ? 90 : s > 0 ? 270 : -90;
  const a0 = s > 0 ? 180 : 0;
  const a1 = side > 0 ? (s > 0 ? 0 : 180) : s > 0 ? 360 : -180;
  return [...arc(x1 + s * r, zc, r, a0, mid, 2), ...arc(x2 - s * r, zc, r, mid, a1, 2)];
}

/** x of the diagonal through e (toward −z, to side sx) at height z. */
function diagX(e, sx, z) {
  return e[0] + sx * Math.tan(DIAG) * (e[1] - z);
}

const diagDown = (sx) => heading(sx * Math.sin(DIAG), -Math.cos(DIAG));

/** From a longitudinal line (toward +z) across the upper short side into the diagonal through e. */
function intoDiag(fromX, zc, e, sx, r = 8) {
  const endZ = zc - r * Math.sin(DIAG);
  const cx = diagX(e, sx, endZ) + sx * r * Math.cos(DIAG);
  return [
    ...arc(fromX - sx * r, zc, r, sx > 0 ? 0 : 180, 90, 2),
    ...arc(cx, zc, r, 90, 90 + sx * 105),
  ];
}

/** End of the diagonal through e: turn onto the longitudinal line laneX (toward +z). */
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
  // P1: 4 crosses on a large oval with two straight lines – rideable at trot
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
  // P2: crosses and verticals; figure eight with a change of rein across the diagonal
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
  // P3: first oxer; mirrored figure eight, finishing through the middle
  course(3, 'canter', {
    start: line(-14, -26, N),
    finish: line(-7, -12, S),
    obstacles: [
      single(1, 'p3-1', 'cross', 0.5, -14, -11, N),
      single(2, 'p3-2', 'vertical', 0.6, -14, -11 + relatedDistance(5), N),
      single(3, 'p3-3', 'vertical', 0.65, ...P3_DIAG, diagDown(-1)),
      single(4, 'p3-4', 'oxer', 0.7, 14, -9.5, N),
      single(5, 'p3-5', 'vertical', 0.65, 14, -9.5 + relatedDistance(5, oxerSpread(0.7)), N),
      single(6, 'p3-6', 'vertical', 0.7, -7, 4, S),
    ],
    track: [
      [],
      [],
      intoDiag(-14, -11 + relatedDistance(5) + LANDING_FREE, P3_DIAG, -1),
      outOfDiag(P3_DIAG, -1, 14),
      [],
      turnAround(14, -7, -9.5 + relatedDistance(5, oxerSpread(0.7)) + LANDING_FREE, 1, 8),
      [],
    ],
  }),
  // P4: verticals and oxers mixed; figure eight and a final line
  course(4, 'canter', {
    start: line(14, -26, N),
    finish: line(7, -24, S),
    obstacles: [
      single(1, 'p4-1', 'vertical', 0.7, 14, -11, N),
      single(2, 'p4-2', 'oxer', 0.75, 14, -11 + relatedDistance(5, 0, oxerSpread(0.75)), N),
      single(3, 'p4-3', 'vertical', 0.75, ...P4_DIAG, diagDown(1)),
      single(4, 'p4-4', 'oxer', 0.8, -14, -9.5, N),
      single(5, 'p4-5', 'vertical', 0.8, -14, -9.5 + relatedDistance(5, oxerSpread(0.8)), N),
      single(6, 'p4-6', 'oxer', 0.8, 7, 10, S),
      single(7, 'p4-7', 'vertical', 0.8, 7, 10 - relatedDistance(5, oxerSpread(0.8)), S),
    ],
    track: [
      [],
      [],
      intoDiag(
        14,
        -11 + relatedDistance(5, 0, oxerSpread(0.75)) + oxerSpread(0.75) / 2 + LANDING_FREE,
        P4_DIAG,
        1,
      ),
      outOfDiag(P4_DIAG, 1, -14),
      [],
      turnAround(-14, 7, 10 + oxerSpread(0.8) / 2 + 14, 1, 8),
      [],
      [],
    ],
  }),
  // P5: change of rein through the middle (without a jump), combination after a wide turn,
  // final line with a related distance
  course(5, 'canter', {
    start: line(14, -26, N),
    finish: line(-7, 23, N),
    obstacles: [
      single(1, 'p5-1', 'vertical', 0.75, 14, -11, N),
      single(2, 'p5-2', 'oxer', 0.8, 14, -11 + relatedDistance(5, 0, oxerSpread(0.8)), N),
      single(3, 'p5-3', 'oxer', 0.8, -14, -11, N),
      single(4, 'p5-4', 'vertical', 0.85, -14, -11 + relatedDistance(5, oxerSpread(0.8)), N),
      combination(5, 'p5-5', ['vertical', 0.8], ['oxer', 0.85], 7, 11.5, S),
      single(
        6,
        'p5-6',
        'vertical',
        0.85,
        7,
        11.5 - COMBI_DISTANCE - relatedDistance(5, oxerSpread(0.85)),
        S,
      ),
      single(7, 'p5-7', 'oxer', 0.85, -7, -11.5, N),
      single(8, 'p5-8', 'vertical', 0.85, -7, -11.5 + relatedDistance(5, oxerSpread(0.85)), N),
    ],
    track: [
      [],
      [],
      [
        ...turnAround(
          14,
          0,
          -11 + relatedDistance(5, 0, oxerSpread(0.8)) + oxerSpread(0.8) / 2 + LANDING_FREE,
          1,
          7,
        ),
        ...turnAround(0, -14, -11 - oxerSpread(0.8) / 2 - 14, -1, 7),
      ],
      [],
      turnAround(-14, 7, 11.5 + 14, 1, 8),
      [],
      turnAround(
        7,
        -7,
        11.5 - COMBI_DISTANCE - relatedDistance(5, oxerSpread(0.85)) - LANDING_FREE,
        -1,
        7,
      ),
      [],
      [],
    ],
  }),
];

/** The course with the given id (number or numeric string); falls back to the first course. */
export function courseById(id) {
  return COURSES.find((c) => c.id === Number(id)) ?? COURSES[0];
}

// Free mode: undirected, without numbers; every line can be approached from both directions,
// room to turn at the short sides and between the lines
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
