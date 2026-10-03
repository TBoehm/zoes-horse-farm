// Parcours-Modus für den Reit-Bildschirm (SRT-004): Vorstart, Ritt, Wertung, Rittende.
import { getLang, t } from '../core/i18n.js';
import { h } from '../app/dom.js';
import { COURSES } from '../game/course/courses.js';
import { createCourseRun } from '../game/course/course-run.js';
import { applyFinishedRide } from '../progress/progress.js';
import { checkRideEndBadges } from '../progress/badges.js';
import { formatSeconds } from './format.js';

export const WRONG_REBUILD_S = 3;

export function courseById(id) {
  return COURSES.find((c) => c.id === Number(id)) ?? COURSES[0];
}

export function createCourseMode(ctx, params) {
  const { store, services } = ctx;
  const course = courseById(params.courseId);
  let run = createCourseRun(course);
  let clockMs = 0;
  let missingTimer = 0;

  const chip = (field) => {
    const label = h('small', {});
    const value = h('span', { dataset: { hud: field } });
    return {
      el: h('div', { class: 'hud-chip', dataset: { chip: field } }, label, value),
      label,
      value,
    };
  };
  const time = chip('time');
  const allowed = chip('allowed');
  const faults = chip('faults');
  const next = chip('next');
  const notice = h('div', { class: 'hud-chip hud-notice', dataset: { hud: 'notice' } });
  const hud = h('div', { class: 'hud-row' }, time.el, allowed.el, faults.el, next.el, notice);

  function renderHud() {
    const lang = getLang();
    const riding = run.phase !== 'prestart';
    time.value.textContent = `${formatSeconds(Math.floor(run.timeMs / 10), lang)} s`;
    allowed.value.textContent = `${course.allowedTimeS} s`;
    faults.value.textContent = String(run.faults.total);
    next.value.textContent = run.nextLabel === 'finish' ? t('hud.finish') : String(run.nextLabel);
    time.el.hidden = !riding;
    faults.el.hidden = !riding;
    time.el.classList.toggle('is-warning', run.timeMs > course.allowedTimeS * 1000);
    if (run.phase === 'prestart') {
      notice.textContent = t('ride.prestartHint');
      notice.hidden = false;
    } else if (run.missingHint !== null && run.missingHint !== undefined) {
      notice.textContent = t('hud.missing', { n: run.missingHint });
      notice.hidden = false;
    } else {
      notice.hidden = true;
    }
  }

  function finish(api) {
    const result = run.result;
    const nowIso = new Date().toISOString();
    let summary = null;
    store.update('progress', (p) => {
      const applied = applyFinishedRide(p, result);
      const badges = checkRideEndBadges(applied.progress, result, nowIso);
      summary = { ...applied, awarded: badges.awarded };
      return badges.progress;
    });
    api.input.resetTouchGallop();
    services.audio?.sfx.finishSignal();
    api.app.go('results', {
      courseId: course.id,
      result,
      isNewBest: summary.isNewBest,
      unlockedCourse: summary.unlockedCourse,
      awarded: summary.awarded,
    });
  }

  return {
    id: 'course',
    course,
    obstacles: course.obstacles,
    flags: true,
    get lines() {
      return {
        start: course.start,
        finish: course.finish,
        labels: { start: t('prestart.legendStart'), finish: t('prestart.legendFinish') },
      };
    },
    hud,
    quitLabelKey: 'pause.toSelect',
    // Delegation, weil „Neu starten" einen neuen Ritt erzeugt
    rules: { canRefuse: (id, dir) => run.rules.canRefuse(id, dir) },
    startPose: () => course.startPose,
    renderTexts() {
      time.label.textContent = t('hud.time');
      allowed.label.textContent = t('hud.allowed');
      faults.label.textContent = t('hud.faults');
      next.label.textContent = t('hud.next');
      renderHud();
    },
    onRestart(api) {
      run = createCourseRun(course);
      clockMs = 0;
      missingTimer = 0;
      api.world.setLines?.(this.lines);
      renderHud();
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
      renderHud();
      if (run.phase === 'finished') finish(api);
    },
    aidTarget() {
      if (!store.get('settings').aidCourse) return null;
      const cur = run.current;
      return cur ? { elementId: cur.elementId, dir: 1 } : null;
    },
    quit(app) {
      app.go('courseSelect');
    },
    get run() {
      return run;
    },
  };
}
