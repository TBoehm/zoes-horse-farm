// Overview of all badges (rule 50). Display only: the list comes from application/badge-overview.
import { getLang } from '../../i18n.js';
import { badgeSummary, listBadges } from '../../../../application/badge-overview.js';

export function formatDate(iso, lang = getLang()) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString(lang === 'de' ? 'de-DE' : 'en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

export function badgeEmblem(h, id, earned) {
  const icons = {
    firstJump: '🐎',
    jumpMouse: '🐭',
    clean: '✨',
    oxerPro: '🏅',
    comboPro: '🎯',
    allOpen: '🔓',
    starRider: '⭐',
    busy: '🐝',
  };
  return h(
    'div',
    { class: `badge-emblem ${earned ? 'is-earned' : ''}`, 'aria-hidden': 'true' },
    icons[id] ?? '🏆',
  );
}

export function createBadgesScreen(ctx) {
  const { t, h, store, app } = ctx;
  const { earned, total } = badgeSummary(store);
  const grid = h(
    'ul',
    { class: 'badge-grid' },
    listBadges(store).map((b) => {
      const date = b.earnedAt;
      return h(
        'li',
        { class: `badge-card ${date ? 'is-earned' : 'is-missing'}`, dataset: { badge: b.id } },
        badgeEmblem(h, b.id, Boolean(date)),
        h('strong', { class: 'badge-name' }, t(b.nameKey)),
        h('span', { class: 'badge-condition' }, t(b.conditionKey)),
        h(
          'span',
          { class: 'badge-status' },
          date ? t('badges.earned', { date: formatDate(date) }) : t('badges.missing'),
        ),
      );
    }),
  );
  const el = h(
    'section',
    { class: 'panel panel-wide panel-badges' },
    h(
      'header',
      { class: 'panel-head' },
      h('h2', {}, t('badges.title')),
      h('span', { class: 'chip' }, t('badges.count', { count: earned, total })),
    ),
    grid,
    h(
      'div',
      { class: 'panel-actions' },
      h(
        'button',
        {
          class: 'btn btn-secondary',
          type: 'button',
          dataset: { action: 'back' },
          onclick: () => app.go('menu'),
        },
        t('common.back'),
      ),
    ),
  );
  return { el, music: true };
}
