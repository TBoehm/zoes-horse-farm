// Fortschritt (Konzept Regeln 36, 37, 44, 47, 48): rein und unveränderlich, ohne DOM.
// Alle Funktionen geben neue Objekte zurück; unbekannte Felder bleiben unverändert erhalten.
import { BADGE_IDS, COURSE_COUNT } from './badges.js';

export const PROGRESS_DEFAULTS = Object.freeze({
  unlocked: 1,
  courses: Object.freeze({}),
  jumps: 0,
  finishedRides: 0,
  badges: Object.freeze({}),
});

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

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

function sanitizeCourses(raw) {
  const courses = {};
  if (!isPlainObject(raw)) return courses;
  for (let i = 1; i <= COURSE_COUNT; i++) {
    const entry = sanitizeCourse(raw[String(i)]);
    if (entry) courses[String(i)] = entry;
  }
  return courses;
}

function sanitizeBadges(raw) {
  if (!isPlainObject(raw)) return {};
  const badges = {};
  for (const [id, value] of Object.entries(raw)) {
    if (!BADGE_IDS.includes(id)) {
      Object.defineProperty(badges, id, {
        value,
        enumerable: true,
        writable: true,
        configurable: true,
      });
    } else if (typeof value === 'string' && Number.isFinite(Date.parse(value))) {
      badges[id] = value;
    }
  }
  return badges;
}

/** Bereinigte Kopie: Ungültiges → Default, Lesbares bleibt, unbekannte Felder bleiben. */
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

/** Bestleistung: zuerst weniger Fehler, bei Gleichstand kürzere Zeit (Hundertstel). */
export function isBetterResult(candidate, best) {
  if (!best) return true;
  if (candidate.faults !== best.faults) return candidate.faults < best.faults;
  return candidate.timeCs < best.timeCs;
}

/**
 * Wertet einen BEENDETEN Ritt ein (abgebrochene Ritte nie übergeben, Regel 40).
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

/** Zählt einen gezählten Sprung (Regel 40). */
export function addJump(progress) {
  return { ...progress, jumps: progress.jumps + 1 };
}

/** „Fortschritt löschen" (Regel 48): nur diese Felder, alles andere bleibt. */
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
