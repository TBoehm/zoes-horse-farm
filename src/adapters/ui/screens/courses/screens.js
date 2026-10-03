// Parcours-Auswahl, Vorstart-Karte und Ergebnis (Regeln 26, 35, 55).
import { getLang } from '../../i18n.js';
import { toggleRow } from '../../settings-screen.js';
import { COURSES, courseById } from '../../../../domain/course/courses.js';
import { displayName } from '../../../../domain/horse/horse-name.js';
import { BADGES } from '../../../../domain/progress/badges.js';
import { badgeEmblem } from '../profile/badges-screen.js';
import { drawCoursePlan } from './plan.js';
import { formatCs } from './format.js';

function stars(h, count, label) {
  return h(
    'span',
    { class: 'stars', role: 'img', 'aria-label': label, dataset: { stars: String(count) } },
    [1, 2, 3].map((i) => h('span', { class: `star ${i <= count ? 'is-on' : ''}` }, '★')),
  );
}

function backButton(h, t, onclick) {
  return h(
    'button',
    { class: 'btn btn-secondary', type: 'button', dataset: { action: 'back' }, onclick },
    t('common.back'),
  );
}

export function createCourseSelectScreen(ctx) {
  const { t, h, store, app } = ctx;
  const progress = store.get('progress');
  const cards = COURSES.map((course) => {
    const open = course.id <= progress.unlocked;
    const best = progress.courses[String(course.id)];
    const card = h(
      'button',
      {
        class: `course-card ${open ? 'is-open' : 'is-locked'}`,
        type: 'button',
        disabled: !open,
        'aria-disabled': String(!open),
        dataset: { course: String(course.id) },
        onclick: () => open && app.go('prestart', { courseId: course.id }),
      },
      h('span', { class: 'course-number' }, String(course.id)),
      h('strong', {}, t('courses.number', { n: course.id })),
      h('span', {}, t('courses.obstacles', { count: course.obstacles.length })),
      open
        ? stars(h, best?.stars ?? 0, t('courses.starsLabel', { count: best?.stars ?? 0 }))
        : h('span', { class: 'lock', 'aria-hidden': 'true' }, '🔒'),
      h(
        'span',
        { class: 'course-best' },
        !open
          ? t('courses.locked')
          : best
            ? t('courses.best', { faults: best.faults, time: formatCs(best.timeCs, getLang()) })
            : t('courses.noBest'),
      ),
    );
    return card;
  });
  const el = h(
    'section',
    { class: 'panel panel-wide panel-courses' },
    h('h2', {}, t('courses.title')),
    h('div', { class: 'course-grid' }, cards),
    h(
      'div',
      { class: 'panel-actions' },
      backButton(h, t, () => app.go('menu')),
    ),
  );
  return { el, music: true };
}

export function createPrestartScreen(ctx, params) {
  const { t, h, store, app, services } = ctx;
  const course = courseById(params.courseId);
  const canvas = h('canvas', {
    class: 'course-plan',
    role: 'img',
    'aria-label': t('prestart.planLabel'),
  });
  const go = h(
    'button',
    { class: 'btn btn-menu', type: 'button', dataset: { action: 'go' } },
    t('prestart.go'),
  );
  go.addEventListener('click', () => {
    // Startsignal nur bei „Los" (Regel 26)
    services.audio?.sfx.startSignal();
    app.go('ride', { mode: 'course', courseId: course.id });
  });
  const el = h(
    'section',
    { class: 'panel panel-wide panel-prestart' },
    h('h2', {}, t('prestart.title', { n: course.id })),
    h('div', { class: 'plan-wrap' }, canvas),
    h(
      'div',
      { class: 'prestart-info' },
      h('span', { class: 'chip' }, t('prestart.allowedTime', { seconds: course.allowedTimeS })),
      toggleRow({
        name: 'aidCourse',
        label: t('prestart.aid'),
        value: store.get('settings').aidCourse,
        // ändert die gespeicherte Einstellung „im Parcours" (Regel 42)
        onChange: (v) => store.update('settings', (s) => ({ ...s, aidCourse: v })),
      }),
    ),
    h(
      'div',
      { class: 'panel-actions' },
      go,
      backButton(h, t, () => app.go('courseSelect')),
    ),
  );
  const draw = () =>
    drawCoursePlan(canvas, course, {
      startLabel: t('prestart.legendStart'),
      finishLabel: t('prestart.legendFinish'),
    });
  requestAnimationFrame(draw);
  window.addEventListener('resize', draw);
  return { el, music: true, destroy: () => window.removeEventListener('resize', draw) };
}

export function createResultsScreen(ctx, params) {
  const { t, h, store, app } = ctx;
  const { result, isNewBest, awarded = [], courseId } = params;
  const progress = store.get('progress');
  const nextId = courseId + 1;
  const nextOpen = nextId <= COURSES.length && nextId <= progress.unlocked;
  const row = (label, value, field) =>
    h(
      'div',
      { class: 'result-row', dataset: { result: field } },
      h('span', {}, label),
      h('strong', {}, value),
    );
  const f = result.faults;
  const el = h(
    'section',
    { class: 'panel panel-results' },
    h('h2', {}, t('results.title')),
    h(
      'p',
      { class: 'results-horse' },
      t('results.horse', { name: displayName(store.get('horse'), t) }),
    ),
    stars(h, result.stars, t('courses.starsLabel', { count: result.stars })),
    isNewBest
      ? h('p', { class: 'new-best', dataset: { result: 'newBest' } }, t('results.newBest'))
      : null,
    h(
      'div',
      { class: 'result-table' },
      row(t('results.time'), formatCs(result.timeCs, ctx.store && getLang()), 'time'),
      row(
        t('results.knockdowns'),
        t('results.points', { count: f.knockdowns, points: f.knockdowns * 4 }),
        'knockdowns',
      ),
      row(
        t('results.refusals'),
        t('results.points', { count: f.refusals, points: f.refusals * 4 }),
        'refusals',
      ),
      row(t('results.timeFaults'), String(f.timeFaults), 'timeFaults'),
      row(t('results.total'), String(f.total), 'total'),
    ),
    params.unlockedCourse
      ? h('p', { class: 'unlocked-note' }, t('results.unlocked', { n: params.unlockedCourse }))
      : null,
    awarded.length
      ? h(
          'div',
          { class: 'new-badges', dataset: { result: 'badges' } },
          h('h3', {}, t('results.newBadges')),
          h(
            'ul',
            { class: 'badge-grid' },
            awarded.map((id) => {
              const b = BADGES.find((x) => x.id === id);
              return h(
                'li',
                { class: 'badge-card is-earned', dataset: { badge: id } },
                badgeEmblem(h, id, true),
                h('strong', { class: 'badge-name' }, t(b.nameKey)),
              );
            }),
          ),
        )
      : null,
    h(
      'div',
      { class: 'panel-actions' },
      h(
        'button',
        {
          class: 'btn',
          type: 'button',
          dataset: { action: 'again' },
          onclick: () => app.go('prestart', { courseId }),
        },
        t('results.again'),
      ),
      nextOpen
        ? h(
            'button',
            {
              class: 'btn',
              type: 'button',
              dataset: { action: 'next' },
              onclick: () => app.go('prestart', { courseId: nextId }),
            },
            t('results.next'),
          )
        : null,
      h(
        'button',
        {
          class: 'btn btn-secondary',
          type: 'button',
          dataset: { action: 'toSelect' },
          onclick: () => app.go('courseSelect'),
        },
        t('results.toSelect'),
      ),
    ),
  );
  return { el, music: true };
}
