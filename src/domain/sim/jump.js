// Jump rules per element: takeoff zone, target ranges, jumpability, knockdown risk
// (concept rules 15–20). Pure stateless functions.
import { POLE_LENGTH, STAND_WIDTH } from './tuning.js';
import { clamp } from '../../shared/math.js';

const DEG = Math.PI / 180;

/** Difficulty 0..1 from height and spread. */
export function difficultyOf(element, tuning) {
  const d = tuning.jump.difficulty;
  const raw = (element.height - d.heightRef + d.spreadWeight * (element.spread || 0)) / d.range;
  return clamp(raw, 0, 1);
}

/** Jumpability by gait (rule 16). */
export function gaitAllows(element, gait) {
  if (gait === 'canter') return true;
  if (gait === 'trot') return element.kind === 'cross';
  return false;
}

/** Target speed range { min, max } of the safe core. */
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

/** Minimum speed for a self jump at the last takeoff point (rule 20). */
export function selfMinSpeed(element, tuning) {
  const b = tuning.jump.speedBand;
  if (element.kind === 'cross') return b.crossSelfMin;
  return speedBand(element, tuning).min - b.selfMargin;
}

/** Angle tolerance of the safe core (rad). */
export function safeAngle(element, tuning) {
  const a = tuning.jump.safeAngle;
  return a.base - a.perDifficulty * difficultyOf(element, tuning);
}

/** Half time window of the takeoff zone (s). */
export function zoneWindow(element, tuning) {
  const w = tuning.jump.window;
  if (element.kind === 'cross') return w.cross;
  const v =
    w.base -
    w.perHeight * Math.max(0, element.height - w.heightRef) -
    w.perSpread * (element.spread || 0);
  return Math.max(w.min, v);
}

/**
 * Takeoff zone and reach in m before the leading edge:
 * reach > far > near > lastPoint > 0. Depends on kind, height, spread and speed.
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
  const lastPoint = Math.min(
    near * j.lastPoint.maxShareOfNear,
    Math.max(j.lastPoint.min, near - j.lastPoint.lead * v),
  );
  const reach = far + Math.max(j.reachMin, j.reachLead * v);
  return { far, near, lastPoint, reach, center };
}

/**
 * Knockdown risk of a takeoff (rules 15, 18, 19, 20).
 * In the safe core (gait ok, angle ≤ tolerance, speed in target range, distance in the zone)
 * it is exactly 0. Outside it rises monotonically with every deviation, scaled by difficulty.
 * A self jump (self) always carries an additional base risk.
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

/** Poles of an element with their local position along n (rail 0 at −spread/2). */
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

/**
 * Half extent of the blocked area (local) that the horse may not enter without jumping.
 * `alongMargin` is the distance kept before/behind the poles (front margin by default).
 */
export function blockExtents(element, tuning, alongMargin = tuning.horse.frontMargin) {
  return {
    along: (element.spread || 0) / 2 + alongMargin,
    across: POLE_LENGTH / 2 + STAND_WIDTH + tuning.horse.halfWidth,
  };
}

/** Landing distance behind the rear pole. */
export function landingDistance(element, takeoffDistance, tuning) {
  const f = tuning.jump.flight;
  return clamp(
    f.landBase + f.landPerTakeoff * takeoffDistance + f.landPerHeight * element.height,
    f.landMin,
    f.landMax,
  );
}
