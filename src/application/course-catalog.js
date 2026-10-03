// Course catalog for the selection screen: which courses are open, stars and best results
// (rules 35, 36, 37). The unlock rule itself lives in domain/progress (applyFinishedRide).
import { COURSES, courseById } from '../domain/course/courses.js';

const isOpen = (progress, id) => id <= progress.unlocked;

/**
 * @returns {{
 *   id: number,
 *   obstacleCount: number,
 *   open: boolean,
 *   stars: number,
 *   best: { faults: number, timeCs: number }|null,
 * }[]}
 */
export function listCourses(store) {
  const progress = store.get('progress');
  return COURSES.map((course) => {
    const entry = progress.courses?.[String(course.id)];
    return {
      id: course.id,
      obstacleCount: course.obstacles.length,
      open: isOpen(progress, course.id),
      stars: entry?.stars ?? 0,
      best: entry ? { faults: entry.faults, timeCs: entry.timeCs } : null,
    };
  });
}

/** A course can be started when it exists and is open. */
export function canStart(store, id) {
  const courseId = Number(id);
  return COURSES.some((c) => c.id === courseId) && isOpen(store.get('progress'), courseId);
}

/** The following course if it exists and is open, else null. */
export function nextCourse(store, id) {
  const next = Number(id) + 1;
  return canStart(store, next) ? next : null;
}

/** Course data (obstacles, start/finish, allowed time) for the prestart map. */
export function getCourse(id) {
  return courseById(id);
}
