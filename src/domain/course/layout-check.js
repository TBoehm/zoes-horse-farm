// Geometric check of obstacle layouts (courses and free mode).
// Used as a test oracle (courses.test.js, layout-check.test.js), not by the game itself; pure,
// no three.js.
import { ARENA, POLE_LENGTH, STAND_WIDTH } from '../sim/tuning.js';
import { axisOf, toLocal } from '../sim/geometry.js';

const LAYOUT_LIMITS = Object.freeze({
  approach: 14, // m of straight approach before the leading edge
  landing: 8, // m clear after landing (rear pole)
  corridorHalfWidth: POLE_LENGTH / 2 + 1,
  fenceClearance: 4, // m from the element to the fence
  minGap: 3, // m between elements of different obstacles
  lineClearance: 2, // m between start/finish line and elements
});

const HALF_X = ARENA.width / 2;
const HALF_Z = ARENA.length / 2;

/** Oriented rectangle: center, axis u (unit vector), half extent along u and across. */
function rect(cx, cz, u, halfAlong, halfAcross) {
  return { cx, cz, ux: u.x, uz: u.z, halfAlong, halfAcross };
}

function rectCorners(r) {
  const vx = -r.uz;
  const vz = r.ux;
  const out = [];
  for (const [sa, sc] of [
    [1, 1],
    [1, -1],
    [-1, -1],
    [-1, 1],
  ]) {
    out.push({
      x: r.cx + r.ux * r.halfAlong * sa + vx * r.halfAcross * sc,
      z: r.cz + r.uz * r.halfAlong * sa + vz * r.halfAcross * sc,
    });
  }
  return out;
}

function grow(r, margin) {
  return { ...r, halfAlong: r.halfAlong + margin, halfAcross: r.halfAcross + margin };
}

/** Footprint of an element (poles + stands, for oxers including the depth). */
export function footprint(element) {
  return rect(
    element.x,
    element.z,
    axisOf(element),
    (element.spread || 0) / 2 + 0.3,
    POLE_LENGTH / 2 + STAND_WIDTH,
  );
}

/**
 * Approach corridor of an obstacle: straight approach before the first element up to clear
 * space after landing behind the last. Undirected (free mode): approach from both directions.
 */
export function corridorOf(obstacle, limits = LAYOUT_LIMITS) {
  const first = obstacle.elements[0];
  const last = obstacle.elements[obstacle.elements.length - 1];
  const n = axisOf(first);
  const lastAlong = toLocal(first, last.x, last.z).along;
  const front = -(first.spread || 0) / 2 - limits.approach;
  const back =
    lastAlong + (last.spread || 0) / 2 + (obstacle.directed ? limits.landing : limits.approach);
  const mid = (front + back) / 2;
  return rect(
    first.x + n.x * mid,
    first.z + n.z * mid,
    n,
    (back - front) / 2,
    limits.corridorHalfWidth,
  );
}

function project(corners, ax, az) {
  let min = Infinity;
  let max = -Infinity;
  for (const p of corners) {
    const v = p.x * ax + p.z * az;
    if (v < min) min = v;
    if (v > max) max = v;
  }
  return [min, max];
}

/** Do two oriented rectangles overlap (separating axis test)? */
export function rectsOverlap(a, b) {
  const ca = rectCorners(a);
  const cb = rectCorners(b);
  for (const [ax, az] of [
    [a.ux, a.uz],
    [-a.uz, a.ux],
    [b.ux, b.uz],
    [-b.uz, b.ux],
  ]) {
    const [minA, maxA] = project(ca, ax, az);
    const [minB, maxB] = project(cb, ax, az);
    if (maxA <= minB || maxB <= minA) return false;
  }
  return true;
}

/** Does the segment p→q intersect the rectangle? (treated as a very thin rectangle) */
export function segmentHitsRect(p, q, r) {
  const dx = q[0] - p[0];
  const dz = q[1] - p[1];
  const len = Math.hypot(dx, dz);
  const seg = rect(
    (p[0] + q[0]) / 2,
    (p[1] + q[1]) / 2,
    { x: dx / len, z: dz / len },
    len / 2,
    1e-3,
  );
  return rectsOverlap(seg, r);
}

function insideArena(p, margin) {
  return Math.abs(p.x) <= HALF_X - margin + 1e-9 && Math.abs(p.z) <= HALF_Z - margin + 1e-9;
}

/**
 * Checks a layout and returns a list of problems (empty = fine).
 * lines: optional start/finish lines { name, a:[x,z], b:[x,z] }.
 */
export function checkLayout(obstacles, { lines = [], limits = LAYOUT_LIMITS } = {}) {
  const issues = [];
  const all = [];
  obstacles.forEach((obstacle, oi) => {
    for (const element of obstacle.elements) all.push({ oi, element, fp: footprint(element) });
  });

  for (const { element, fp } of all) {
    if (!rectCorners(fp).every((c) => insideArena(c, limits.fenceClearance))) {
      issues.push(`${element.id}: less than ${limits.fenceClearance} m from the fence`);
    }
  }

  obstacles.forEach((obstacle, oi) => {
    const corridor = corridorOf(obstacle, limits);
    const label = obstacle.elements[0].id;
    if (!rectCorners(corridor).every((c) => insideArena(c, 0))) {
      issues.push(`${label}: approach corridor extends outside the arena`);
    }
    for (const other of all) {
      if (other.oi !== oi && rectsOverlap(corridor, other.fp)) {
        issues.push(`${label}: ${other.element.id} is in the approach corridor`);
      }
    }
  });

  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      if (all[i].oi === all[j].oi) continue;
      const half = limits.minGap / 2;
      if (rectsOverlap(grow(all[i].fp, half), grow(all[j].fp, half))) {
        issues.push(`${all[i].element.id}/${all[j].element.id}: too close together`);
      }
    }
  }

  for (const line of lines) {
    for (const p of [line.a, line.b]) {
      if (!insideArena({ x: p[0], z: p[1] }, 3))
        issues.push(`${line.name}: end point too close to the fence`);
    }
    for (const { element, fp } of all) {
      if (segmentHitsRect(line.a, line.b, grow(fp, limits.lineClearance))) {
        issues.push(`${line.name}: too close to ${element.id}`);
      }
    }
    obstacles.forEach((obstacle) => {
      if (segmentHitsRect(line.a, line.b, corridorOf(obstacle, limits))) {
        issues.push(`${line.name}: crosses the approach corridor of ${obstacle.elements[0].id}`);
      }
    });
  }
  return issues;
}
