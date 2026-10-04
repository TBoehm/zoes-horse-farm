// Pure calculations for the 3D world (no three.js): obstacle layout, flags, falling poles,
// take-off aid, lines, fence and environment planning. The three.js modules only use these.
import { ARENA, POLE_LENGTH, STAND_WIDTH } from '../../domain/sim/tuning.js';
import { clamp } from '../../shared/math.js';

export const POLE_RADIUS = 0.05;
export const POLE_GEOM_LENGTH = POLE_LENGTH - 0.02;
export const STAND_X = POLE_LENGTH / 2 + STAND_WIDTH / 2;
const CROSS_LOW_Y = 0.17; // lower ends of the cross poles in low cups

// --- Obstacles -------------------------------------------------------------------------------

/** Jump axis n and cross axis t (right-hand side when jumping in +n). */
export function axesOf(rot = 0) {
  return {
    n: { x: Math.sin(rot), z: Math.cos(rot) },
    t: { x: -Math.cos(rot), z: Math.sin(rot) },
  };
}

/**
 * Flag sides: red on +t, white on −t. In the element-local frame (+Z = n), +X points to −t, so
 * red stands at local x < 0. Returns the sign of the local x coordinate per color.
 */
export function flagSides() {
  return { red: -1, white: 1 };
}

/** Stand height for an element. */
export function standHeight(element) {
  return Math.max(1.45, element.height + 0.55);
}

/** Positions of the stand rows along n (oxer: front and back). */
export function standRows(element) {
  if (element.kind !== 'oxer') return [0];
  const s = element.spread || 0;
  return [-s / 2, s / 2];
}

/**
 * Rest poses of all poles of an element in the local frame (+Z = n, +X = −t).
 * [{ rail, a:[x,y,z], b:[x,y,z] }], rail = index of the falling rail, −1 = fixed (never falls).
 * Height = top of the highest pole (cross: at the crossing point).
 */
export function polesOf(element) {
  const h = element.height;
  const s = element.kind === 'oxer' ? element.spread || 0 : 0;
  const half = POLE_GEOM_LENGTH / 2;
  const top = h - POLE_RADIUS;
  const out = [];
  if (element.kind === 'cross') {
    const y1 = Math.max(CROSS_LOW_Y + 0.1, 2 * top - CROSS_LOW_Y);
    const dz = POLE_RADIUS + 0.004;
    out.push({ rail: 0, a: [-half, CROSS_LOW_Y, -dz], b: [half, y1, -dz] });
    out.push({ rail: 0, a: [-half, y1, dz], b: [half, CROSS_LOW_Y, dz] });
    out.push({ rail: -1, a: [-half, POLE_RADIUS, 0.32], b: [half, POLE_RADIUS, 0.32] });
  } else if (element.kind === 'vertical') {
    out.push({ rail: 0, a: [-half, top, 0], b: [half, top, 0] });
    if (h >= 0.7) {
      const y = (0.32 + top) / 2;
      out.push({ rail: -1, a: [-half, y, 0], b: [half, y, 0] });
    }
  } else {
    out.push({ rail: 0, a: [-half, top, -s / 2], b: [half, top, -s / 2] });
    out.push({ rail: 1, a: [-half, top, s / 2], b: [half, top, s / 2] });
    out.push({ rail: -1, a: [-half, h * 0.45, -s / 2], b: [half, h * 0.45, -s / 2] });
  }
  return out;
}

/** Element label: number, with a/b for combinations; null without a number. */
export function labelOf(obstacle, index) {
  if (obstacle.number === null || obstacle.number === undefined) return null;
  if (obstacle.elements.length > 1) return `${obstacle.number}${index === 0 ? 'a' : 'b'}`;
  return String(obstacle.number);
}

/** Highlight text: the given number, with a/b appended for combinations. */
export function highlightText(obstacle, index, number) {
  if (number === null || number === undefined) return labelOf(obstacle, index);
  const text = String(number);
  if (obstacle.elements.length > 1 && /^\d+$/.test(text)) return text + (index === 0 ? 'a' : 'b');
  return text;
}

// --- Falling poles -------------------------------------------------------------------------------

export const FALL_DURATION = 0.7;
export const RISE_DURATION = 0.45;
const FALL_LAG = 0.16; // the second end falls a little later

export const easeOut = (t) => 1 - (1 - t) * (1 - t);
export const easeInOut = (t) => (t < 0.5 ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t));

/** Height curve of a fall (gravity, then a small bounce): 0 → 1. */
export function fallCurve(t) {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  if (t < 0.78) return (t / 0.78) ** 2;
  return 1 - 0.1 * Math.sin(((t - 0.78) / 0.22) * Math.PI);
}

/** Progress of both ends; lead = 0: end a falls first. */
export function endProgress(t, lead = 0) {
  const first = clamp(t / (1 - FALL_LAG), 0, 1);
  const second = clamp((t - FALL_LAG) / (1 - FALL_LAG), 0, 1);
  return lead === 0 ? { a: first, b: second } : { a: second, b: first };
}

/** Progress of one end (a or b) without allocating; same values as endProgress. */
export function endProgressOf(t, lead, isA) {
  const first = clamp(t / (1 - FALL_LAG), 0, 1);
  const second = clamp((t - FALL_LAG) / (1 - FALL_LAG), 0, 1);
  return (lead === 0) === isA ? first : second;
}

/** Point of a falling end: eased horizontally, fall curve vertically. */
export function fallPoint(from, to, t) {
  const k = easeOut(t);
  const f = fallCurve(t);
  return [
    from[0] + (to[0] - from[0]) * k,
    from[1] + (to[1] - from[1]) * f,
    from[2] + (to[2] - from[2]) * k,
  ];
}

/** Like fallPoint, but for {x, y, z} objects (e.g. THREE.Vector3) and without allocating. */
export function fallPointInto(out, from, to, t) {
  const k = easeOut(t);
  const f = fallCurve(t);
  out.x = from.x + (to.x - from.x) * k;
  out.y = from.y + (to.y - from.y) * f;
  out.z = from.z + (to.z - from.z) * k;
  return out;
}

/**
 * Target pose of a fallen pole (local): lies on the sand, shifted towards `side` (±1 along n)
 * and slightly rotated. rnd: () → [0, 1).
 */
export function fallTarget(center, length, side, rnd) {
  const travel = 0.55 + rnd() * 0.6;
  const yaw = (rnd() - 0.5) * 0.5;
  const cx = (rnd() - 0.5) * 0.35;
  const cz = center[2] + side * travel;
  const hx = (Math.cos(yaw) * length) / 2;
  const hz = (-Math.sin(yaw) * length) / 2;
  return {
    a: [cx - hx, POLE_RADIUS, cz - hz],
    b: [cx + hx, POLE_RADIUS, cz + hz],
    roll: side * (travel / POLE_RADIUS) * (0.6 + rnd() * 0.3),
    lead: rnd() < 0.5 ? 0 : 1,
  };
}

// --- Take-off aid ----------------------------------------------------------------------------

/**
 * Take-off band placement: front edge = center − dir·n·spread/2; the band spans zone.near to
 * zone.far in front of the front edge (against the approach direction), as wide as the pole.
 * Returns { x, z, rotY, width, depth } or null.
 */
export function aidPlacement(element, dir, zone) {
  if (!element || !zone) return null;
  const near = Math.max(0, Math.min(zone.near, zone.far));
  const far = Math.max(zone.near, zone.far);
  const depth = far - near;
  if (!(depth > 0)) return null;
  const d = dir < 0 ? -1 : 1;
  const { n } = axesOf(element.rot || 0);
  const half = (element.kind === 'oxer' ? element.spread || 0 : 0) / 2;
  const s = -d * (half + (near + far) / 2);
  return {
    x: element.x + n.x * s,
    z: element.z + n.z * s,
    rotY: element.rot || 0,
    width: POLE_LENGTH,
    depth,
  };
}

// --- Start/finish lines --------------------------------------------------------------------------

function toXZ(p) {
  return Array.isArray(p) ? { x: p[0], z: p[1] } : { x: p.x, z: p.z };
}

/** Center, length and rotation (about Y, direction a→b) of a line. */
export function lineSegment(a, b) {
  const p = toXZ(a);
  const q = toXZ(b);
  const dx = q.x - p.x;
  const dz = q.z - p.z;
  return {
    a: p,
    b: q,
    cx: (p.x + q.x) / 2,
    cz: (p.z + q.z) / 2,
    length: Math.hypot(dx, dz),
    angle: Math.atan2(dx, dz),
  };
}

/**
 * Flag posts of a line. The domain puts end `a` on the rider's LEFT and `b` on the RIGHT (see
 * `line()` in domain/course/courses.js), and the flags follow the obstacle rule: red on the
 * right, white on the left, in riding direction.
 */
export function linePosts(seg) {
  return [
    { x: seg.a.x, z: seg.a.z, red: false },
    { x: seg.b.x, z: seg.b.z, red: true },
  ];
}

/** Signs for start/finish; if both lines coincide there is one shared sign. */
export function planLines(lines) {
  if (!lines) return [];
  // The texts are translated by the caller (i18n); there is no built-in fallback text
  const labels = { start: '', finish: '', ...(lines.labels || {}) };
  const s = lines.start && lineSegment(lines.start.a, lines.start.b);
  const f = lines.finish && lineSegment(lines.finish.a, lines.finish.b);
  const same =
    s &&
    f &&
    Math.hypot(s.a.x - f.a.x, s.a.z - f.a.z) + Math.hypot(s.b.x - f.b.x, s.b.z - f.b.z) < 0.5;
  const out = [];
  if (s)
    out.push({
      kind: 'start',
      seg: s,
      text: same ? `${labels.start} · ${labels.finish}` : labels.start,
      finish: same,
    });
  if (f && !same) out.push({ kind: 'finish', seg: f, text: labels.finish, finish: true });
  return out;
}

// --- Fence --------------------------------------------------------------------------------------

export const FENCE = Object.freeze({
  height: 1.2,
  offset: 0.18, // fence line outside the riding area
  spacing: 2.5,
  post: 0.12,
  board: 0.04,
});

// gate on the long side facing the stable (x = −20)
export const GATE = Object.freeze({ side: -1, z: 22, width: 3.6 });

function fenceRun(a, b, spacing, out, style) {
  const dx = b[0] - a[0];
  const dz = b[1] - a[1];
  const len = Math.hypot(dx, dz);
  const n = Math.max(1, Math.ceil(len / spacing - 1e-6));
  for (let i = 0; i <= n; i += 1)
    out.posts.push({ x: a[0] + (dx * i) / n, z: a[1] + (dz * i) / n, style });
  const ang = Math.atan2(dx, dz);
  for (let i = 0; i < n; i += 1) {
    const t = (i + 0.5) / n;
    out.segments.push({ x: a[0] + dx * t, z: a[1] + dz * t, len: len / n, ang, style });
  }
}

/** Plan of all fence parts: arena fence with gate gap + path fences. */
export function planFence({ pathFence = [] } = {}) {
  const out = { posts: [], segments: [], gate: null };
  const hx = ARENA.width / 2 + FENCE.offset;
  const hz = ARENA.length / 2 + FENCE.offset;
  const g0 = GATE.z - GATE.width / 2;
  const g1 = GATE.z + GATE.width / 2;
  const gx = GATE.side * hx;
  fenceRun([hx, -hz], [hx, hz], FENCE.spacing, out, 'arena');
  fenceRun([hx, hz], [-hx, hz], FENCE.spacing, out, 'arena');
  fenceRun([gx, hz], [gx, g1], FENCE.spacing, out, 'arena');
  fenceRun([gx, g0], [gx, -hz], FENCE.spacing, out, 'arena');
  fenceRun([-hx, -hz], [hx, -hz], FENCE.spacing, out, 'arena');
  out.gate = { x: gx, z0: g0, z1: g1 };
  for (const run of pathFence) fenceRun(run.a, run.b, run.spacing ?? 3, out, 'wood');
  out.posts = uniquePosts(out.posts);
  return out;
}

/** Drops posts that stand at the same place (corners of runs that meet). */
function uniquePosts(posts) {
  const seen = new Set();
  return posts.filter((p) => {
    const key = `${p.x.toFixed(2)},${p.z.toFixed(2)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// --- Environment ----------------------------------------------------------------------------

/** Placement of buildings, paths and path fences (world coordinates). */
export const SITE = Object.freeze({
  stable: { x: -47, z: 20, depth: 10, length: 30 },
  hut: { x: 25.6, z: -22 },
  // path from the gate (x = −20, z = 22) to the stable yard
  path: [
    { x: -28.6, z: 22, w: 3.6, l: 16.4, ry: Math.PI / 2 },
    { x: -38.8, z: 20, w: 6.4, l: 32 },
  ],
  pathFence: [
    { a: [-21.2, 24.3], b: [-35.4, 24.3] },
    { a: [-21.2, 19.7], b: [-35.4, 19.7] },
  ],
});

export const HILL_START = 90;

const smooth = (x, a, b) => {
  const t = clamp((x - a) / (b - a), 0, 1);
  return t * t * (3 - 2 * t);
};

/** Terrain height: flat around the facility, hills towards the horizon. */
export function terrainHeight(x, z) {
  const r = Math.hypot(x, z);
  if (r < HILL_START) return 0;
  const th = Math.atan2(z, x);
  const ang =
    0.55 +
    0.25 * Math.sin(2 * th + 0.7) +
    0.15 * Math.sin(5 * th + 2.1) +
    0.08 * Math.sin(11 * th + 4.0);
  const rise = smooth(r, HILL_START, 260);
  const far = smooth(r, 220, 460);
  const roll = Math.sin(x * 0.031 + 1.3) * Math.cos(z * 0.027) * 3;
  return rise * (6 + 26 * ang + roll) + far * 30 * ang;
}

/**
 * Is a world point on the sand of the riding area (inside its fence)? Hooves dust only there; the
 * path to the stable and the meadow do not. `margin` > 0 keeps that far inside.
 */
export function isOnArenaSand(x, z, margin = 0) {
  return (
    Math.abs(x) <= ARENA.width / 2 + FENCE.offset - margin &&
    Math.abs(z) <= ARENA.length / 2 + FENCE.offset - margin
  );
}

// --- Paddock ------------------------------------------------------------------------------------

/**
 * The paddock: a fenced piece of meadow west of the arena, south of the path to the stable, so
 * that it is seen from the arena. A rectangle on flat ground (terrain height 0).
 * `x`, `z`: center; `width` along the local u axis, `depth` along the local v axis (m, inside the
 * fence lines); `rotation`: yaw about +Y (rad, the same sense as an obstacle's `rot`; 0 = width
 * along world x). Grazing horses go inside: pick points with paddockPoint() and test with
 * paddockContains().
 */
export const PADDOCK = Object.freeze({ x: -34, z: -9, width: 20, depth: 14, rotation: 0 });

/**
 * World position of a point of the paddock. `u`, `v` ∈ [−1, 1] are fractions of the half width
 * and half depth from the center (0, 0 = center, ±1 = on the fence line).
 */
export function paddockPoint(u, v, paddock = PADDOCK) {
  const lx = (u * paddock.width) / 2;
  const lz = (v * paddock.depth) / 2;
  const c = Math.cos(paddock.rotation);
  const s = Math.sin(paddock.rotation);
  return { x: paddock.x + lx * c + lz * s, z: paddock.z - lx * s + lz * c };
}

/**
 * Is a world point inside the paddock? `margin` > 0 keeps that far away from the fence (for
 * animals), a negative margin grows the area.
 */
export function paddockContains(x, z, margin = 0, paddock = PADDOCK) {
  const dx = x - paddock.x;
  const dz = z - paddock.z;
  const c = Math.cos(paddock.rotation);
  const s = Math.sin(paddock.rotation);
  const lx = dx * c - dz * s;
  const lz = dx * s + dz * c;
  return Math.abs(lx) <= paddock.width / 2 - margin && Math.abs(lz) <= paddock.depth / 2 - margin;
}

/** Fence of the paddock (style 'paddock': three rails, higher posts), without a gate gap. */
export function planPaddockFence(paddock = PADDOCK) {
  const out = { posts: [], segments: [] };
  const corners = [
    paddockPoint(1, -1, paddock),
    paddockPoint(1, 1, paddock),
    paddockPoint(-1, 1, paddock),
    paddockPoint(-1, -1, paddock),
  ];
  corners.forEach((a, i) => {
    const b = corners[(i + 1) % corners.length];
    fenceRun([a.x, a.z], [b.x, b.z], FENCE.spacing, out, 'paddock');
  });
  out.posts = uniquePosts(out.posts);
  return out;
}

/** Axis-aligned box around the (rotated) paddock plus a clearance, in the format of BLOCKED. */
function paddockBlock(paddock, clearance) {
  const c = Math.abs(Math.cos(paddock.rotation));
  const s = Math.abs(Math.sin(paddock.rotation));
  return {
    x: paddock.x,
    z: paddock.z,
    hw: (paddock.width * c + paddock.depth * s) / 2 + clearance,
    hd: (paddock.width * s + paddock.depth * c) / 2 + clearance,
  };
}

/** Areas where no plants may stand. */
const BLOCKED = Object.freeze([
  { x: 0, z: 0, hw: ARENA.width / 2 + 2.5, hd: ARENA.length / 2 + 2.5 },
  {
    x: SITE.stable.x,
    z: SITE.stable.z,
    hw: SITE.stable.depth / 2 + 2,
    hd: SITE.stable.length / 2 + 2,
  },
  { x: -38.8, z: 20, hw: 4, hd: 17 },
  { x: -28.6, z: 22, hw: 9, hd: 3 },
  { x: SITE.hut.x, z: SITE.hut.z, hw: 3.5, hd: 4 },
  { x: 23.5, z: 12, hw: 2, hd: 9 }, // benches
  paddockBlock(PADDOCK, 1),
]);

export function isBlocked(x, z, margin = 0) {
  return BLOCKED.some(
    (r) => Math.abs(x - r.x) < r.hw + margin && Math.abs(z - r.z) < r.hd + margin,
  );
}

/** Random points in an annulus outside blocked areas. rng: () → [0, 1). */
export function scatter(rng, count, minR, maxR, margin = 0, accept = null) {
  const out = [];
  let guard = 0;
  while (out.length < count && guard < count * 60) {
    guard += 1;
    const a = rng() * Math.PI * 2;
    const r = Math.sqrt(minR * minR + rng() * (maxR * maxR - minR * minR));
    const x = Math.cos(a) * r;
    const z = Math.sin(a) * r;
    if (isBlocked(x, z, margin)) continue;
    if (accept && !accept(x, z)) continue;
    out.push([x, z]);
  }
  return out;
}

/** Visible instances per quality level: mandatory instances + share of the rest. */
export function instanceCount(total, priority, density) {
  const prio = clamp(priority || 0, 0, total);
  const d = clamp(density, 0, 1);
  return Math.min(total, prio + Math.round((total - prio) * d));
}
