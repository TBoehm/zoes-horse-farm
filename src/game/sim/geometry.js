// Geometrie-Helfer für Hindernis-Elemente (siehe docs/specs/springreiten-trainer/architecture.md).
import { POLE_LENGTH } from './tuning.js';

/** Sprungachse n eines Elements. */
export function axisOf(element) {
  return { x: Math.sin(element.rot), z: Math.cos(element.rot) };
}

/** Querachse t (rechts, wenn man in +n springt). */
export function crossAxisOf(element) {
  return { x: -Math.cos(element.rot), z: Math.sin(element.rot) };
}

/** Lokale Koordinaten eines Punktes im Element-System: along = entlang n, across = entlang t. */
export function toLocal(element, x, z) {
  const n = axisOf(element);
  const t = crossAxisOf(element);
  const dx = x - element.x;
  const dz = z - element.z;
  return { along: dx * n.x + dz * n.z, across: dx * t.x + dz * t.z };
}

/** Weltkoordinaten zu lokalen Element-Koordinaten. */
export function fromLocal(element, along, across) {
  const n = axisOf(element);
  const t = crossAxisOf(element);
  return {
    x: element.x + along * n.x + across * t.x,
    z: element.z + along * n.z + across * t.z,
  };
}

export function forwardOf(heading) {
  return { x: Math.sin(heading), z: Math.cos(heading) };
}

/** Blickrichtung zu einem Richtungsvektor (Umkehrung von forwardOf). */
export function headingOf(x, z) {
  return Math.atan2(x, z);
}

/** Winkel auf (−π, π]. */
export function wrapAngle(a) {
  let r = a % (2 * Math.PI);
  if (r <= -Math.PI) r += 2 * Math.PI;
  if (r > Math.PI) r -= 2 * Math.PI;
  return r;
}

/**
 * Anreit-Info eines Pferdes zu einem Element (Konzept §Begriffe „Anreiten").
 * Liefert null, wenn das Pferd sich nicht auf das Element zubewegt.
 * - dir: +1 = Sprung in Richtung +n, −1 = in Richtung −n
 * - distance: Abstand (m) von der Vorderkante (Stange auf der Anreitseite), entlang n gemessen
 * - angle: Abweichung des Kurses von der Senkrechten zum Hindernis (rad, ≥ 0)
 * - crossing: Querversatz (m) am Punkt, an dem der Kurs die Hindernis-Ebene trifft
 * - onLine: Kurs trifft das Hindernis zwischen den Ständern
 * - approaching: onLine && distance < approachDistance
 */
export function approachInfo(element, horse, approachDistance) {
  const local = toLocal(element, horse.x, horse.z);
  const f = forwardOf(horse.heading);
  const n = axisOf(element);
  const t = crossAxisOf(element);
  const fAlong = f.x * n.x + f.z * n.z;
  const fAcross = f.x * t.x + f.z * t.z;
  if (Math.abs(fAlong) < 1e-6) return null;
  const dir = local.along < 0 ? 1 : -1;
  // bewegt sich das Pferd auf die Ebene zu?
  if (Math.sign(fAlong) !== dir) return null;
  const halfSpread = (element.spread || 0) / 2;
  const distance = Math.abs(local.along) - halfSpread;
  if (distance < -halfSpread) return null;
  const angle = Math.acos(Math.min(1, Math.abs(fAlong)));
  // Querversatz am Schnittpunkt mit der Mittel-Ebene des Elements
  const travel = Math.abs(local.along) / Math.abs(fAlong);
  const crossing = local.across + fAcross * travel;
  const onLine = Math.abs(crossing) <= POLE_LENGTH / 2;
  return {
    dir,
    distance,
    angle,
    crossing,
    onLine,
    approaching: onLine && distance < approachDistance,
  };
}
