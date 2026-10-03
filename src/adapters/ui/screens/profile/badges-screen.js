// Übersicht aller Auszeichnungen (Regel 50).
import { getLang } from '../core/i18n.js';
import { BADGES } from '../progress/badges.js';

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
  const badges = store.get('progress').badges ?? {};
  const earnedCount = BADGES.filter((b) => badges[b.id]).length;
  const grid = h(
    'ul',
    { class: 'badge-grid' },
    BADGES.map((b) => {
      const date = badges[b.id];
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
      h('span', { class: 'chip' }, t('badges.count', { count: earnedCount, total: BADGES.length })),
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
