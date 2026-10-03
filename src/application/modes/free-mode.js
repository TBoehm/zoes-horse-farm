// Free mode (rule 41): fixed layout, no scoring, feedback on knockdown/refusal, fallen rails are
// rebuilt after TUNING.rebuildDelayS, both jump directions count. No store access: the ride
// session passes the settings it needs.
import { FREE_LAYOUT } from '../../domain/course/courses.js';
import { TUNING } from '../../domain/sim/tuning.js';

/** @param {{ tuning?: object }} [options] */
export function createFreeMode({ tuning = TUNING } = {}) {
  return {
    id: 'free',
    obstacles: FREE_LAYOUT.obstacles,
    flags: false,
    lines: null,
    quitLabelKey: 'pause.toMenu',
    quitScreen: 'menu',
    rules: { canRefuse: () => true },
    highlight: null,
    finishMarked: false,
    startPose() {
      return FREE_LAYOUT.startPose;
    },
    onRestart() {},
    onEvents(events, host) {
      for (const e of events) {
        if (e.type === 'railDown') host.rebuildIn(e.elementId, tuning.rebuildDelayS);
        if (e.type === 'refusal') host.feedback('feedback.refusal');
        if (e.type === 'landed' && e.knocked) host.feedback('feedback.knockdown');
      }
    },
    /** A free ride never ends by itself. */
    update() {
      return null;
    },
    /** Jump aid in front of the element that is currently approached (rule 42). */
    aidTarget({ approach, settings }) {
      if (!settings.aidFree || !approach) return null;
      return { elementId: approach.elementId, dir: approach.dir };
    },
  };
}
