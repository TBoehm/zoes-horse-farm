// Course mode for the ride screen (SRT-004): prestart, ride, scoring, end of ride.
// No DOM and no i18n here: the HUD is exposed as plain data (hudModel) and rendered by the UI
// adapter (adapters/ui/screens/courses/course-hud.js); texts are passed as keys.
import { courseById } from '../../domain/course/courses.js';
import { createCourseRun } from '../../domain/course/course-run.js';
import { applyFinishedRide } from '../../domain/progress/progress.js';
import { checkRideEndBadges } from '../../domain/progress/badges.js';

export const WRONG_REBUILD_S = 3;

/**
 * @param {{ store: object, clock: { nowIso(): string } }} ctx ports: save store and clock
 * @param {{ courseId?: number|string }} params
 */
export function createCourseMode(ctx, params) {
  const { store, clock } = ctx;
  const course = courseById(params.courseId);
  let run = createCourseRun(course);
  let clockMs = 0;
  let missingTimer = 0;

  function finish(api) {
    const result = run.result;
    const nowIso = clock.nowIso();
    let summary = null;
    store.update('progress', (p) => {
      const applied = applyFinishedRide(p, result);
      const badges = checkRideEndBadges(applied.progress, result, nowIso);
      summary = { ...applied, awarded: badges.awarded };
      return badges.progress;
    });
    // The host resets the touch gallop, plays the finish signal and opens the results screen.
    api.finish({
      screen: 'results',
      params: {
        courseId: course.id,
        result,
        isNewBest: summary.isNewBest,
        unlockedCourse: summary.unlockedCourse,
        awarded: summary.awarded,
      },
    });
  }

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
      missingTimer = 0;
    },
    onEvents(events, api) {
      for (const e of events) {
        if (e.type === 'landed') {
          const res = run.onLanded(e.elementId, e.dir, e.knocked);
          if (e.knocked) api.feedback('feedback.knockdown');
          if (res?.rebuildAfterS) api.rebuildIn(e.elementId, res.rebuildAfterS);
        }
        if (e.type === 'refusal') {
          run.onRefusal(e.elementId, e.dir);
          api.feedback('feedback.refusal');
        }
      }
    },
    update(dt, api, prev) {
      const s = api.sim.horse;
      if (run.phase === 'riding') clockMs += dt * 1000;
      const before = run.missingHint;
      run.onLineCross(prev, { x: s.x, z: s.z }, clockMs);
      run.update(s, clockMs);
      for (const id of run.drainRebuilds()) api.rebuildNow(id);
      if (run.missingHint !== before && run.missingHint !== null) missingTimer = 4;
      if (missingTimer > 0) missingTimer -= dt;
      const hl = run.highlight;
      api.world.highlight(hl?.elementId ?? null, hl?.number);
      api.world.setFinishMarked?.(Boolean(run.finishMarked));
      if (run.phase === 'finished') finish(api);
    },
    aidTarget() {
      if (!store.get('settings').aidCourse) return null;
      const cur = run.current;
      return cur ? { elementId: cur.elementId, dir: 1 } : null;
    },
    get run() {
      return run;
    },
  };
}
