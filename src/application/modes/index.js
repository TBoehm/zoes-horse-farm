// Mode factory: the ride screen asks for a mode by id and gets a DOM-free strategy.
import { createCourseMode } from './course-mode.js';
import { createFreeMode } from './free-mode.js';

const MODES = { free: createFreeMode, course: createCourseMode };

/** @param {{ mode?: 'free'|'course', courseId?: number|string }} [params] */
export function createRideMode(params = {}) {
  const factory = MODES[params.mode ?? 'free'];
  if (!factory) throw new Error(`Unknown ride mode: ${params.mode}`);
  return factory(params);
}
