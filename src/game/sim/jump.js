// Sprung-Regeln je Element: Absprungzone, Sollbereiche, Springbarkeit, Abwurfrisiko
// (Konzept Regeln 15–20). Reine Funktionen ohne Zustand.
import { POLE_LENGTH, STAND_WIDTH } from './tuning.js';
import { clamp } from './movement.js';

const DEG = Math.PI / 180;

/** Schwierigkeit 0..1 aus Höhe und Spread. */
export function difficultyOf(element, tuning) {
  const d = tuning.jump.difficulty;
  const raw = (element.height - d.heightRef + d.spreadWeight * (element.spread || 0)) / d.range;
  return clamp(raw, 0, 1);
}

/** Springbarkeit nach Gangart (Regel 16). */
export function gaitAllows(element, gait) {
  if (gait === 'canter') return true;
  if (gait === 'trot') return element.kind === 'cross';
  return false;
}

/** Tempo-Sollbereich { min, max } des sicheren Kerns. */
export function speedBand(element, tuning) {
  const b = tuning.jump.speedBand;
  const s = tuning.speeds;
  if (element.kind === 'cross') return { min: b.crossMin, max: b.crossMax };
  const min = Math.max(
    s.canterMin,
    b.base + b.perHeight * element.height + b.perSpread * (element.spread || 0),
  );
  return { min, max: Math.min(s.canterMax, min + b.width) };
}

/** Mindesttempo für einen Selbstsprung am letzten Absprungpunkt (Regel 20). */
export function selfMinSpeed(element, tuning) {
  const b = tuning.jump.speedBand;
  if (element.kind === 'cross') return b.crossSelfMin;
  return speedBand(element, tuning).min - b.selfMargin;
}

/** Winkel-Toleranz des sicheren Kerns (rad). */
export function safeAngle(element, tuning) {
  const a = tuning.jump.safeAngle;
  return a.base - a.perDifficulty * difficultyOf(element, tuning);
}

/** Halbes Zeitfenster der Absprungzone (s). */
export function zoneWindow(element, tuning) {
  const w = tuning.jump.window;
  if (element.kind === 'cross') return w.cross;
  const v =
    w.base - w.perHeight * Math.max(0, element.height - 0.4) - w.perSpread * (element.spread || 0);
  return Math.max(w.min, v);
}

/**
 * Absprungzone und Reichweite in m vor der Vorderkante:
 * reach > far > near > lastPoint > 0. Hängt von Art, Höhe, Spread und Tempo ab.
 */
export function zoneForElement(element, speed, tuning) {
  const j = tuning.jump;
  const z = j.zone;
  const v = Math.max(speed || 0, z.minSpeed);
  const center = Math.max(
    z.minCenter,
    z.base +
      z.perHeight * element.height +
      z.perSpread * (element.spread || 0) +
      z.perSpeed * (v - z.speedRef),
  );
  const half = zoneWindow(element, tuning) * v;
  const far = center + half;
  const near = Math.max(z.minNear, center - half);
  const lastPoint = Math.min(near * 0.9, Math.max(j.lastPoint.min, near - j.lastPoint.lead * v));
  const reach = far + Math.max(j.reachMin, j.reachLead * v);
  return { far, near, lastPoint, reach, center };
}

/**
 * Abwurfrisiko eines Absprungs (Regeln 15, 18, 19, 20).
 * Im sicheren Kern (Gangart ok, Winkel ≤ Toleranz, Tempo im Sollbereich, Abstand in der Zone)
 * ist es exakt 0. Außerhalb steigt es monoton mit jeder Abweichung, skaliert mit der Schwierigkeit.
 * Ein Selbstsprung (self) trägt immer ein zusätzliches Grundrisiko.
 */
export function takeoffRisk(element, { gait, speed, distance, angle, self = false }, tuning) {
  const r = tuning.jump.risk;
  if (!gaitAllows(element, gait) || angle > tuning.jump.maxAngle) return r.max;
  const zone = zoneForElement(element, speed, tuning);
  const band = speedBand(element, tuning);
  const difficulty = difficultyOf(element, tuning);
  const severity = r.severityBase + r.severityGain * difficulty;
  const dv = Math.max(0, band.min - speed, speed - band.max);
  const dd = Math.max(0, zone.near - distance, distance - zone.far);
  const da = Math.max(0, angle - safeAngle(element, tuning)) / DEG;
  const parts = [r.perSpeed * dv, r.perDistance * dd, r.perDegree * da].map((p) =>
    Math.min(r.factorCap, severity * p),
  );
  if (self) parts.push(Math.min(r.factorCap, r.selfBase + r.selfPerDifficulty * difficulty));
  let clean = 1;
  for (const p of parts) clean *= 1 - p;
  return Math.min(r.max, 1 - clean);
}

/** Stangen eines Elements mit ihrer lokalen Lage entlang n (rail 0 bei −spread/2). */
export function railLayout(element) {
  if (element.kind === 'oxer') {
    const hs = (element.spread || 0) / 2;
    return [
      { rail: 0, along: -hs },
      { rail: 1, along: hs },
    ];
  }
  return [{ rail: 0, along: 0 }];
}

/** Halbe Ausdehnung des Sperrbereichs (lokal), in den das Pferd ohne Sprung nicht hinein darf. */
export function blockExtents(element, tuning) {
  return {
    along: (element.spread || 0) / 2 + tuning.horse.frontMargin,
    across: POLE_LENGTH / 2 + STAND_WIDTH + tuning.horse.halfWidth,
  };
}

/** Landeabstand hinter der hinteren Stange. */
export function landingDistance(element, takeoffDistance, tuning) {
  const f = tuning.jump.flight;
  return clamp(
    f.landBase + f.landPerTakeoff * takeoffDistance + f.landPerHeight * element.height,
    f.landMin,
    f.landMax,
  );
}
