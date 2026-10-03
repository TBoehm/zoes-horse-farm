// Kurze Einblendung sofort vergebener Auszeichnungen, ohne das Spiel zu unterbrechen (Regel 49).
import { t } from '../core/i18n.js';
import { h } from '../app/dom.js';
import { BADGES } from '../progress/badges.js';
import { badgeEmblem } from './badges-screen.js';

export function showBadgeToast(layer, badgeId, durationMs = 3200) {
  const badge = BADGES.find((b) => b.id === badgeId);
  if (!badge) return null;
  const el = h(
    'div',
    { class: 'badge-toast', role: 'status', dataset: { toast: badgeId } },
    badgeEmblem(h, badgeId, true),
    h('span', {}, t('badge.toast', { name: t(badge.nameKey) })),
  );
  // mehrere Einblendungen untereinander
  const offset = layer.querySelectorAll('.badge-toast').length;
  el.style.setProperty('--toast-index', String(offset));
  layer.append(el);
  setTimeout(() => el.classList.add('is-leaving'), durationMs - 400);
  setTimeout(() => el.remove(), durationMs);
  return el;
}
