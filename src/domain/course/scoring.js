// Course scoring (concept rules 32, 33, 36; glossary best result, ride time).
import { TUNING } from '../sim/tuning.js';

export const KNOCKDOWN_FAULTS = 4;
export const REFUSAL_FAULTS = 4;
export const TIME_FAULT_STEP_CS = 400; // one fault per started 4 s
export const ALLOWED_TIME_FACTOR = 1.5;

function mid(line) {
  return [(line.a[0] + line.b[0]) / 2, (line.a[1] + line.b[1]) / 2];
}

/**
 * Ideal line as a sequence of points: start center → (turn waypoints) → element centers of the
 * obstacle → … → (waypoints) → finish center. course.track[i] are the optional waypoints
 * of the leg before obstacle i or (i = number of obstacles) before the finish.
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

/** Length of the ideal line (m). */
export function idealLineLength(course) {
  const points = idealLine(course);
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  return length;
}

/** Reference speed for the allowed time: medium canter, course 1 medium trot (rule 33). */
export function referenceSpeed(course, tuning = TUNING) {
  return course.pace === 'trot' ? tuning.speeds.trotMedium : tuning.speeds.canterMedium;
}

/** Allowed time in whole seconds: ideal line / speed × 1.5, rounded up. */
export function allowedTime(course, speed = referenceSpeed(course)) {
  const seconds = (idealLineLength(course) / speed) * ALLOWED_TIME_FACTOR;
  // small tolerance against rounding noise for round values
  return Math.ceil(seconds - 1e-9);
}

/** Milliseconds → hundredths (truncated, like a stopwatch). */
export function toCentiseconds(ms) {
  return Math.floor(ms / 10 + 1e-6);
}

/** Time faults: 1 point per started 4 s over the allowed time, computed in hundredths. */
export function timeFaults(overMs) {
  const overCs = Math.round(overMs / 10);
  if (overCs <= 0) return 0;
  return Math.ceil(overCs / TIME_FAULT_STEP_CS);
}

/** Stars: 0 faults = 3, 1–4 = 2, more = 1 (rule 36). */
export function starsFor(totalFaults) {
  if (totalFaults <= 0) return 3;
  if (totalFaults <= 4) return 2;
  return 1;
}

function totalOf(result) {
  return typeof result.faults === 'number' ? result.faults : result.faults.total;
}

/**
 * Is candidate a new best result compared to best? Fewer faults, on a tie the shorter time
 * (hundredths). best null → always true. Results: { faults: number | {total}, timeCs }.
 */
export function isBetterResult(candidate, best) {
  if (!best) return true;
  const a = totalOf(candidate);
  const b = totalOf(best);
  if (a !== b) return a < b;
  return candidate.timeCs < best.timeCs;
}
