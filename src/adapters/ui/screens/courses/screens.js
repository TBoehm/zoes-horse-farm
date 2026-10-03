// Course selection, prestart map and results (rules 26, 35, 55). Display only: which courses are
// open, the stars and the result figures come from the application layer.
import { getLang } from '../../i18n.js';
import { toggleRow } from '../../settings-screen.js';
import { getCourse, listCourses } from '../../../../application/course-catalog.js';
import { displayName } from '../../../../application/horse-service.js';
import { KNOCKDOWN_FAULTS, REFUSAL_FAULTS } from '../../../../domain/course/scoring.js';
import { summarizeResult } from '../../../../application/result-summary.js';
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
  const cards = listCourses(store).map((course) => {
    const { id, open, best } = course;
    return h(
      'button',
      {
        class: `course-card ${open ? 'is-open' : 'is-locked'}`,
        type: 'button',
        disabled: !open,
        'aria-disabled': String(!open),
        dataset: { course: String(id) },
        onclick: () => open && app.go('prestart', { courseId: id }),
      },
      h('span', { class: 'course-number' }, String(id)),
      h('strong', {}, t('courses.number', { n: id })),
      h('span', {}, t('courses.obstacles', { count: course.obstacleCount })),
      open
        ? stars(h, course.stars, t('courses.starsLabel', { count: course.stars }))
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
  const course = getCourse(params.courseId);
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
    // Start signal only on "Go" (rule 26)
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
        // changes the saved "in courses" setting (rule 42)
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
  const summary = summarizeResult(store, params);
  const { courseId, rows } = summary;
  const row = (label, value, field) =>
    h(
      'div',
      { class: 'result-row', dataset: { result: field } },
      h('span', {}, label),
      h('strong', {}, value),
    );
  const el = h(
    'section',
    { class: 'panel panel-results' },
    h(
      'div',
      { class: 'results-body' },
      h('h2', {}, t('results.title')),
      h(
        'p',
        { class: 'results-horse' },
        t('results.horse', { name: displayName(store.get('horse'), t('horse.defaultName')) }),
      ),
      stars(h, summary.stars, t('courses.starsLabel', { count: summary.stars })),
      summary.isNewBest
        ? h('p', { class: 'new-best', dataset: { result: 'newBest' } }, t('results.newBest'))
        : null,
      h(
        'div',
        { class: 'result-table' },
        row(t('results.time'), formatCs(summary.timeCs, getLang()), 'time'),
        row(
          t('results.knockdowns'),
          t('results.points', { ...rows.knockdowns, each: KNOCKDOWN_FAULTS }),
          'knockdowns',
        ),
        row(
          t('results.refusals'),
          t('results.points', { ...rows.refusals, each: REFUSAL_FAULTS }),
          'refusals',
        ),
        row(t('results.timeFaults'), String(rows.timeFaults), 'timeFaults'),
        row(t('results.total'), String(rows.total), 'total'),
      ),
      summary.unlockedCourse
        ? h('p', { class: 'unlocked-note' }, t('results.unlocked', { n: summary.unlockedCourse }))
        : null,
      summary.badges.length
        ? h(
            'div',
            { class: 'new-badges', dataset: { result: 'badges' } },
            h('h3', {}, t('results.newBadges')),
            h(
              'ul',
              { class: 'badge-grid' },
              summary.badges.map((badge) =>
                h(
                  'li',
                  { class: 'badge-card is-earned', dataset: { badge: badge.id } },
                  badgeEmblem(h, badge.id, true),
                  h('strong', { class: 'badge-name' }, t(badge.nameKey)),
                ),
              ),
            ),
          )
        : null,
    ),
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
      summary.nextCourse
        ? h(
            'button',
            {
              class: 'btn',
              type: 'button',
              dataset: { action: 'next' },
              onclick: () => app.go('prestart', { courseId: summary.nextCourse }),
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
