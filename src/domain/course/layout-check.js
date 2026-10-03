// Geometrische Prüfung von Hindernis-Aufstellungen (Parcours und freier Modus).
// Wird von den Tests genutzt; rein, ohne three.js.
import { ARENA, POLE_LENGTH, STAND_WIDTH } from '../sim/tuning.js';
import { axisOf, toLocal } from '../sim/geometry.js';

export const LAYOUT_LIMITS = Object.freeze({
  approach: 14, // m gerader Anritt vor der Vorderkante
  landing: 8, // m frei nach der Landung (hintere Stange)
  corridorHalfWidth: POLE_LENGTH / 2 + 1,
  fenceClearance: 4, // m vom Element bis zum Zaun
  minGap: 3, // m zwischen Elementen verschiedener Hindernisse
  lineClearance: 2, // m zwischen Start-/Ziellinie und Elementen
});

const HALF_X = ARENA.width / 2;
const HALF_Z = ARENA.length / 2;

/** Orientiertes Rechteck: Mitte, Achse u (Einheitsvektor), halbe Ausdehnung entlang u und quer. */
function rect(cx, cz, u, halfAlong, halfAcross) {
  return { cx, cz, ux: u.x, uz: u.z, halfAlong, halfAcross };
}

export function rectCorners(r) {
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

/** Grundfläche eines Elements (Stangen + Ständer, bei Oxern die Tiefe). */
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
 * Anreit-Korridor eines Hindernisses: gerader Anritt vor dem ersten Element bis frei nach der
 * Landung hinter dem letzten. Ungerichtet (freier Modus): Anritt aus beiden Richtungen.
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

/** Überlappen sich zwei orientierte Rechtecke (Trennachsen-Test)? */
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

/** Schneidet die Strecke p→q das Rechteck? (als sehr dünnes Rechteck behandelt) */
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
 * Prüft eine Aufstellung und liefert eine Liste von Problemen (leer = in Ordnung).
 * lines: optionale Start-/Ziellinien { name, a:[x,z], b:[x,z] }.
 */
export function checkLayout(obstacles, { lines = [], limits = LAYOUT_LIMITS } = {}) {
  const issues = [];
  const all = [];
  obstacles.forEach((obstacle, oi) => {
    for (const element of obstacle.elements) all.push({ oi, element, fp: footprint(element) });
  });

  for (const { element, fp } of all) {
    if (!rectCorners(fp).every((c) => insideArena(c, limits.fenceClearance))) {
      issues.push(`${element.id}: weniger als ${limits.fenceClearance} m zum Zaun`);
    }
  }

  obstacles.forEach((obstacle, oi) => {
    const corridor = corridorOf(obstacle, limits);
    const label = obstacle.elements[0].id;
    if (!rectCorners(corridor).every((c) => insideArena(c, 0))) {
      issues.push(`${label}: Anreit-Korridor ragt aus dem Platz`);
    }
    for (const other of all) {
      if (other.oi !== oi && rectsOverlap(corridor, other.fp)) {
        issues.push(`${label}: ${other.element.id} steht im Anreit-Korridor`);
      }
    }
  });

  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      if (all[i].oi === all[j].oi) continue;
      const half = limits.minGap / 2;
      if (rectsOverlap(grow(all[i].fp, half), grow(all[j].fp, half))) {
        issues.push(`${all[i].element.id}/${all[j].element.id}: zu dicht beieinander`);
      }
    }
  }

  for (const line of lines) {
    for (const p of [line.a, line.b]) {
      if (!insideArena({ x: p[0], z: p[1] }, 3))
        issues.push(`${line.name}: Endpunkt zu nah am Zaun`);
    }
    for (const { element, fp } of all) {
      if (segmentHitsRect(line.a, line.b, grow(fp, limits.lineClearance))) {
        issues.push(`${line.name}: zu nah an ${element.id}`);
      }
    }
    obstacles.forEach((obstacle) => {
      if (segmentHitsRect(line.a, line.b, corridorOf(obstacle, limits))) {
        issues.push(`${line.name}: kreuzt den Anreit-Korridor von ${obstacle.elements[0].id}`);
      }
    });
  }
  return issues;
}
