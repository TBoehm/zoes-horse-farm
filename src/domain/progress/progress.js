// Progress (concept rules 36, 37, 44, 47, 48): pure and immutable, no DOM.
// All functions return new objects; unknown fields are preserved unchanged.
import { isPlainObject } from '../../shared/math.js';
import { BADGE_IDS, COURSE_COUNT, COURSE_IDS } from './badges.js';
import { isBetterResult } from '../course/scoring.js';

export const PROGRESS_DEFAULTS = Object.freeze({
  unlocked: 1,
  courses: Object.freeze({}),
  jumps: 0,
  finishedRides: 0,
  badges: Object.freeze({}),
});

function toCount(value) {
  return Number.isFinite(value) && value >= 0 ? Math.floor(value) : 0;
}

function sanitizeCourse(entry) {
  if (!isPlainObject(entry)) return null;
  const { faults, timeCs, stars } = entry;
  if (!Number.isFinite(faults) || faults < 0) return null;
  if (!Number.isFinite(timeCs) || timeCs < 0) return null;
  if (!Number.isInteger(stars) || stars < 1 || stars > 3) return null;
  return { ...entry, faults: Math.floor(faults), timeCs: Math.floor(timeCs), stars };
}

// Own property even for keys like "__proto__" (JSON.parse creates them as plain data).
function setOwn(target, key, value) {
  Object.defineProperty(target, key, {
    value,
    enumerable: true,
    writable: true,
    configurable: true,
  });
}

function sanitizeCourses(raw) {
  const courses = {};
  if (!isPlainObject(raw)) return courses;
  // Unknown keys (later versions) stay untouched (rule 47); courses 1..5 are cleaned
  for (const [key, value] of Object.entries(raw)) {
    if (!COURSE_IDS.includes(key)) setOwn(courses, key, value);
  }
  for (const key of COURSE_IDS) {
    const entry = sanitizeCourse(raw[key]);
    if (entry) courses[key] = entry;
  }
  return courses;
}

function sanitizeBadges(raw) {
  if (!isPlainObject(raw)) return {};
  const badges = {};
  for (const [id, value] of Object.entries(raw)) {
    if (!BADGE_IDS.includes(id)) {
      setOwn(badges, id, value);
    } else if (typeof value === 'string' && Number.isFinite(Date.parse(value))) {
      badges[id] = value;
    }
  }
  return badges;
}

/** Sanitized copy: invalid → default, readable values are kept, unknown fields are kept. */
export function sanitizeProgress(raw) {
  const source = isPlainObject(raw) ? raw : {};
  const unlocked = Number.isFinite(source.unlocked) ? Math.floor(source.unlocked) : 1;
  return {
    ...source,
    unlocked: Math.min(COURSE_COUNT, Math.max(1, unlocked)),
    courses: sanitizeCourses(source.courses),
    jumps: toCount(source.jumps),
    finishedRides: toCount(source.finishedRides),
    badges: sanitizeBadges(source.badges),
  };
}

/**
 * Applies a FINISHED ride (never pass aborted rides, rule 40).
 * @returns {{ progress: object, isNewBest: boolean, unlockedCourse: number|null }}
 */
export function applyFinishedRide(progress, result) {
  const key = String(result.courseId);
  const courseId = Number(result.courseId);
  if (!Number.isInteger(courseId) || courseId < 1 || courseId > COURSE_COUNT) {
    return { progress, isNewBest: false, unlockedCourse: null };
  }
  const stars = Math.min(3, Math.max(1, Math.floor(result.stars)));
  const candidate = { faults: result.faults.total, timeCs: result.timeCs };
  const previous = progress.courses?.[key];
  const isNewBest = isBetterResult(candidate, previous);
  const best = isNewBest ? candidate : { faults: previous.faults, timeCs: previous.timeCs };

  const next = Math.min(COURSE_COUNT, courseId + 1);
  const unlockedCourse = next > progress.unlocked ? next : null;

  return {
    progress: {
      ...progress,
      unlocked: unlockedCourse ?? progress.unlocked,
      courses: {
        ...progress.courses,
        [key]: { ...previous, ...best, stars: Math.max(previous?.stars ?? 0, stars) },
      },
      finishedRides: progress.finishedRides + 1,
    },
    isNewBest,
    unlockedCourse,
  };
}

/** Counts one counted jump (rule 40). */
export function addJump(progress) {
  return { ...progress, jumps: progress.jumps + 1 };
}

/** "Delete progress" (rule 48): only these fields, everything else is kept. */
export function resetProgress(progress) {
  return {
    ...progress,
    unlocked: PROGRESS_DEFAULTS.unlocked,
    courses: {},
    jumps: PROGRESS_DEFAULTS.jumps,
    finishedRides: PROGRESS_DEFAULTS.finishedRides,
    badges: {},
  };
}
