// Freier Modus (Regel 41): feste Aufstellung, keine Wertung, Rückmeldung bei Abwurf/Verweigerung,
// Stangen werden nach etwa 3 s wieder aufgebaut, beide Sprungrichtungen gültig.
import { FREE_LAYOUT } from '../../domain/course/courses.js';

export const REBUILD_DELAY_S = 3;

export function createFreeMode(ctx) {
  const { store } = ctx;
  return {
    id: 'free',
    obstacles: FREE_LAYOUT.obstacles,
    flags: false,
    lines: null,
    quitLabelKey: 'pause.toMenu',
    quitScreen: 'menu',
    rules: { canRefuse: () => true },
    startPose() {
      return FREE_LAYOUT.startPose;
    },
    onEvents(events, api) {
      for (const e of events) {
        if (e.type === 'railDown') api.rebuildIn(e.elementId, REBUILD_DELAY_S);
        if (e.type === 'refusal') api.feedback('feedback.refusal');
        if (e.type === 'landed' && e.knocked) api.feedback('feedback.knockdown');
      }
    },
    update() {},
    /** Absprung-Hilfe: vor dem gerade angerittenen Hindernis (Regel 42). */
    aidTarget(api) {
      if (!store.get('settings').aidFree) return null;
      const a = api.sim.approach;
      return a ? { elementId: a.elementId, dir: a.dir } : null;
    },
  };
}
