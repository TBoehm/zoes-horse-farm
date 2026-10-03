// Wertung im Parcours (Konzept Regeln 32, 33, 36; Begriffe Bestleistung, Ritt-Zeit).
import { TUNING } from '../sim/tuning.js';

export const KNOCKDOWN_FAULTS = 4;
export const REFUSAL_FAULTS = 4;
export const TIME_FAULT_STEP_CS = 400; // je angefangene 4 s ein Fehlerpunkt
export const ALLOWED_TIME_FACTOR = 1.5;

function mid(line) {
  return [(line.a[0] + line.b[0]) / 2, (line.a[1] + line.b[1]) / 2];
}

/**
 * Ideallinie als Punktfolge: Start-Mitte → (Wegpunkte der Wendung) → Element-Mitten des
 * Hindernisses → … → (Wegpunkte) → Ziel-Mitte. course.track[i] sind die optionalen Wegpunkte
 * der Teilstrecke vor Hindernis i bzw. (i = Anzahl Hindernisse) vor dem Ziel.
 */
export function idealLine(course) {
  const track = course.track || [];
  const points = [mid(course.start)];
  course.obstacles.forEach((obstacle, i) => {
    points.push(...(track[i] || []));
    for (const element of obstacle.elements) points.push([element.x, element.z]);
  });
  points.push(...(track[course.obstacles.length] || []));
  points.push(mid(course.finish));
  return points;
}

/** Länge der Ideallinie (m). */
export function idealLineLength(course) {
  const points = idealLine(course);
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  return length;
}

/** Bezugstempo für die erlaubte Zeit: mittlerer Galopp, Parcours 1 mittlerer Trab (Regel 33). */
export function referenceSpeed(course, tuning = TUNING) {
  return course.pace === 'trot' ? tuning.speeds.trotMedium : tuning.speeds.canterMedium;
}

/** Erlaubte Zeit in vollen Sekunden: Ideallinie / Tempo × 1,5, aufgerundet. */
export function allowedTime(course, speed = referenceSpeed(course)) {
  const seconds = (idealLineLength(course) / speed) * ALLOWED_TIME_FACTOR;
  // kleine Toleranz gegen Rundungsrauschen bei glatten Werten
  return Math.ceil(seconds - 1e-9);
}

/** Millisekunden → Hundertstel (abgeschnitten, wie eine Stoppuhr). */
export function toCentiseconds(ms) {
  return Math.floor(ms / 10 + 1e-6);
}

/** Zeitfehler: je angefangene 4 s über der erlaubten Zeit 1 Punkt, gerechnet in Hundertsteln. */
export function timeFaults(overMs) {
  const overCs = Math.round(overMs / 10);
  if (overCs <= 0) return 0;
  return Math.ceil(overCs / TIME_FAULT_STEP_CS);
}

/** Sterne: 0 Fehler = 3, 1–4 = 2, mehr = 1 (Regel 36). */
export function starsFor(totalFaults) {
  if (totalFaults <= 0) return 3;
  if (totalFaults <= 4) return 2;
  return 1;
}

function totalOf(result) {
  return typeof result.faults === 'number' ? result.faults : result.faults.total;
}

/**
 * Ist candidate eine neue Bestleistung gegenüber best? Weniger Fehler, bei Gleichstand kürzere
 * Zeit (Hundertstel). best null → immer true. Ergebnisse: { faults: Zahl | {total}, timeCs }.
 */
export function isBetterResult(candidate, best) {
  if (!best) return true;
  const a = totalOf(candidate);
  const b = totalOf(best);
  if (a !== b) return a < b;
  return candidate.timeCs < best.timeCs;
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

/** Hundertstel → 'm:ss,hh' (z. B. 4827 → '0:48,27'). */
export function formatTime(cs) {
  const total = Math.max(0, Math.floor(cs));
  const minutes = Math.floor(total / 6000);
  const seconds = Math.floor((total % 6000) / 100);
  return `${minutes}:${pad2(seconds)},${pad2(total % 100)}`;
}

/** Hundertstel → 'ss,hh s' (z. B. 4827 → '48,27 s', 6250 → '62,50 s'). */
export function formatSeconds(cs) {
  const total = Math.max(0, Math.floor(cs));
  return `${Math.floor(total / 100)},${pad2(total % 100)} s`;
}
