// Course mode for the ride screen (SRT-004): prestart, ride, scoring, end of ride.
// No DOM and no i18n here: the HUD is exposed as plain data (hudModel) and rendered by the UI
// adapter (adapters/ui/screens/courses/course-hud.js); texts are passed as keys.
import { courseById } from '../../domain/course/courses.js';
import { createCourseRun } from '../../domain/course/course-run.js';

/**
 * Pure strategy: knows the course run, but neither the store nor the progress. The ride session
 * saves the finished ride (progress-service) and the settings it passes decide the jump aid.
 * @param {{ courseId?: number|string }} params
 */
export function createCourseMode(params = {}) {
  const course = courseById(params.courseId);
  let run = createCourseRun(course);
  let clockMs = 0;

  return {
    id: 'course',
    course,
    obstacles: course.obstacles,
    flags: true,
    /** Start/finish lines; the host translates the label keys. */
    get lines() {
      return {
        start: course.start,
        finish: course.finish,
        labelKeys: { start: 'prestart.legendStart', finish: 'prestart.legendFinish' },
      };
    },
    quitLabelKey: 'pause.toSelect',
    quitScreen: 'courseSelect',
    // Delegation, because "restart" creates a new run
    rules: { canRefuse: (id, dir) => run.rules.canRefuse(id, dir) },
    startPose: () => course.startPose,
    /** Obstacle that is due next (with its number), shown highlighted in the arena. */
    get highlight() {
      return run.highlight ?? null;
    },
    get finishMarked() {
      return Boolean(run.finishMarked);
    },
    /** Plain-data HUD model for the UI adapter. */
    hudModel() {
      return {
        phase: run.phase,
        timeMs: run.timeMs,
        allowedS: course.allowedTimeS,
        faults: run.faults.total,
        nextLabel: run.nextLabel,
        missingHint: run.missingHint ?? null,
      };
    },
    onRestart() {
      run = createCourseRun(course);
      clockMs = 0;
    },
    onEvents(events, host) {
      for (const e of events) {
        if (e.type === 'landed') {
          const res = run.onLanded(e.elementId, e.dir, e.knocked);
          if (e.knocked) host.feedback('feedback.knockdown');
          if (res?.rebuildAfterS) host.rebuildIn(e.elementId, res.rebuildAfterS);
        }
        if (e.type === 'refusal') {
          run.onRefusal(e.elementId, e.dir);
          host.feedback('feedback.refusal');
        }
      }
    },
    /**
     * @param {number} dt seconds
     * @param {{ horse: {x:number,z:number}, prev: {x:number,z:number} }} state horse position now
     *   and before the step
     * @returns {{ finished: { screen: string, result: object, params: object } }|null}
     */
    update(dt, { horse, prev }, host) {
      if (run.phase === 'riding') clockMs += dt * 1000;
      run.onLineCross(prev, { x: horse.x, z: horse.z }, clockMs);
      run.update(horse, clockMs);
      for (const id of run.drainRebuilds()) host.rebuildNow(id);
      if (run.phase !== 'finished') return null;
      return { finished: { screen: 'results', result: run.result, params: { courseId: course.id } } };
    },
    aidTarget({ settings }) {
      if (!settings.aidCourse) return null;
      const cur = run.current;
      return cur ? { elementId: cur.elementId, dir: 1 } : null;
    },
    get run() {
      return run;
    },
  };
}
