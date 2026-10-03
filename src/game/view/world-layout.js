// Reine Berechnungen der 3D-Welt (ohne three.js): Hindernis-Aufbau, Fahnen, Stangenfall,
// Absprung-Hilfe, Linien, Zaun- und Umgebungsplanung. Die three.js-Module nutzen nur diese.
import { ARENA, POLE_LENGTH, STAND_WIDTH } from '../sim/tuning.js';

export const POLE_RADIUS = 0.05;
export const POLE_GEOM_LENGTH = POLE_LENGTH - 0.02;
export const STAND_X = POLE_LENGTH / 2 + STAND_WIDTH / 2;
export const CROSS_LOW_Y = 0.17; // untere Enden der Kreuzstangen in tiefen Auflagen

// --- Hindernisse -------------------------------------------------------------------------------

/** Sprungachse n und Querachse t (rechts, wenn man in +n springt). */
export function axesOf(rot = 0) {
  return {
    n: { x: Math.sin(rot), z: Math.cos(rot) },
    t: { x: -Math.cos(rot), z: Math.sin(rot) },
  };
}

/**
 * Fahnenseiten: rot auf +t, weiß auf −t. Im lokalen Element-System (+Z = n) zeigt +X nach −t,
 * daher steht Rot bei lokal x < 0. Liefert das Vorzeichen der lokalen x-Koordinate je Farbe.
 */
export function flagSides() {
  return { red: -1, white: 1 };
}

/** Welt-Position eines lokalen Punktes (lx, lz) eines Elements. */
export function localToWorld(element, lx, lz) {
  const rot = element.rot || 0;
  const c = Math.cos(rot);
  const s = Math.sin(rot);
  return { x: element.x + lx * c + lz * s, z: element.z - lx * s + lz * c };
}

/** Höhe der Ständer für ein Element. */
export function standHeight(element) {
  return Math.max(1.45, element.height + 0.55);
}

/** Lage der Ständerreihen entlang n (Oxer: vorne und hinten). */
export function standRows(element) {
  if (element.kind !== 'oxer') return [0];
  const s = element.spread || 0;
  return [-s / 2, s / 2];
}

/**
 * Ruhelagen aller Stangen eines Elements im lokalen System (+Z = n, +X = −t).
 * [{ rail, a:[x,y,z], b:[x,y,z] }], rail = Index der fallenden Stange, −1 = fest (fällt nie).
 * Höhe = Oberkante der obersten Stange (Kreuz: am Kreuzungspunkt).
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

/** Bezeichnung eines Elements: Nummer, bei Kombinationen mit a/b; null ohne Nummer. */
export function labelOf(obstacle, index) {
  if (obstacle.number === null || obstacle.number === undefined) return null;
  if (obstacle.elements.length > 1) return `${obstacle.number}${index === 0 ? 'a' : 'b'}`;
  return String(obstacle.number);
}

/** Text für die Hervorhebung: übergebene Nummer, bei Kombinationen mit a/b ergänzt. */
export function highlightText(obstacle, index, number) {
  if (number === null || number === undefined) return labelOf(obstacle, index);
  const text = String(number);
  if (obstacle.elements.length > 1 && /^\d+$/.test(text)) return text + (index === 0 ? 'a' : 'b');
  return text;
}

// --- Stangenfall -------------------------------------------------------------------------------

export const FALL_DURATION = 0.7;
export const RISE_DURATION = 0.45;
export const FALL_LAG = 0.16; // das zweite Ende fällt etwas später

export const easeOut = (t) => 1 - (1 - t) * (1 - t);
export const easeInOut = (t) => (t < 0.5 ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t));

/** Fallkurve der Höhe (Schwerkraft, dann kleines Nachhüpfen): 0 → 1. */
export function fallCurve(t) {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  if (t < 0.78) return (t / 0.78) ** 2;
  return 1 - 0.1 * Math.sin(((t - 0.78) / 0.22) * Math.PI);
}

/** Fortschritt beider Enden; lead = 0: Ende a fällt zuerst. */
export function endProgress(t, lead = 0) {
  const first = Math.min(1, Math.max(0, t / (1 - FALL_LAG)));
  const second = Math.min(1, Math.max(0, (t - FALL_LAG) / (1 - FALL_LAG)));
  return lead === 0 ? { a: first, b: second } : { a: second, b: first };
}

/** Punkt eines fallenden Endes: waagrecht ausgerollt, senkrecht mit Fallkurve. */
export function fallPoint(from, to, t) {
  const k = easeOut(t);
  const f = fallCurve(t);
  return [
    from[0] + (to[0] - from[0]) * k,
    from[1] + (to[1] - from[1]) * f,
    from[2] + (to[2] - from[2]) * k,
  ];
}

/**
 * Ziel-Lage einer gefallenen Stange (lokal): liegt auf dem Sand, zur Fallseite `side` (±1
 * entlang n) verschoben und leicht verdreht. rnd: () → [0, 1).
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

// --- Absprung-Hilfe ----------------------------------------------------------------------------

/**
 * Lage des Absprung-Bandes: Vorderkante = Mitte − dir·n·spread/2; das Band reicht von zone.near
 * bis zone.far vor der Vorderkante, entgegen der Anreitrichtung, so breit wie die Stange.
 * Liefert { x, z, rotY, width, depth } oder null.
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

// --- Start-/Ziellinie --------------------------------------------------------------------------

export function toXZ(p) {
  return Array.isArray(p) ? { x: p[0], z: p[1] } : { x: p.x, z: p.z };
}

/** Mitte, Länge und Drehung (um Y, Richtung a→b) einer Linie. */
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

/** Schilder für Start/Ziel; fallen beide Linien zusammen, gibt es ein gemeinsames Schild. */
export function planLines(lines) {
  if (!lines) return [];
  const labels = { start: 'Start', finish: 'Ziel', ...(lines.labels || {}) };
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

// --- Zaun --------------------------------------------------------------------------------------

export const FENCE = Object.freeze({
  height: 1.2,
  offset: 0.18, // Zaunlinie außerhalb der Reitfläche
  spacing: 2.5,
  post: 0.12,
  board: 0.04,
});

// Tor an der Langseite zum Stall (x = −20)
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

/** Plan aller Zaunteile: Reitplatz-Umzäunung mit Torlücke + Wegzäune. */
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
  const seen = new Set();
  out.posts = out.posts.filter((p) => {
    const key = `${p.x.toFixed(2)},${p.z.toFixed(2)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return out;
}

// --- Umgebung ----------------------------------------------------------------------------------

/** Lage der Gebäude, Wege und Wegzäune (Weltkoordinaten). */
export const SITE = Object.freeze({
  stable: { x: -47, z: 20, depth: 10, length: 30 },
  hut: { x: 25.6, z: -22 },
  // Weg vom Tor (x = −20, z = 22) zum Stallhof
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
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

/** Geländehöhe: flach um die Anlage, Hügel zum Horizont. */
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

/** Flächen, auf denen keine Pflanzen stehen dürfen. */
export const BLOCKED = Object.freeze([
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
  { x: 23.5, z: 12, hw: 2, hd: 9 }, // Bänke
]);

export function isBlocked(x, z, margin = 0) {
  return BLOCKED.some(
    (r) => Math.abs(x - r.x) < r.hw + margin && Math.abs(z - r.z) < r.hd + margin,
  );
}

/** Zufällige Punkte in einem Kreisring außerhalb gesperrter Flächen. rng: () → [0, 1). */
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

/** Sichtbare Instanzen je Grafikstufe: Pflicht-Instanzen + Anteil der übrigen. */
export function instanceCount(total, priority, density) {
  const prio = Math.min(total, Math.max(0, priority || 0));
  const d = Math.min(1, Math.max(0, density));
  return Math.min(total, prio + Math.round((total - prio) * d));
}
