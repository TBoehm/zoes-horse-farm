// Short toast for instantly awarded badges, without interrupting the ride (rule 49).
import { t } from '../../i18n.js';
import { h } from '../../dom.js';
import { describeBadge } from '../../../../application/badge-overview.js';
import { badgeEmblem } from './badges-screen.js';

export function showBadgeToast(layer, badgeId, durationMs = 3200) {
  const badge = describeBadge(badgeId);
  if (!badge) return null;
  const el = h(
    'div',
    { class: 'badge-toast', role: 'status', dataset: { toast: badgeId } },
    badgeEmblem(h, badgeId, true),
    h('span', {}, t('badge.toast', { name: t(badge.nameKey) })),
  );
  // several toasts are stacked
  const offset = layer.querySelectorAll('.badge-toast').length;
  el.style.setProperty('--toast-index', String(offset));
  layer.append(el);
  setTimeout(() => el.classList.add('is-leaving'), durationMs - 400);
  setTimeout(() => el.remove(), durationMs);
  return el;
}
