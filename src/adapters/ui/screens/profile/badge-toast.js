// Short toast for instantly awarded badges, without interrupting the ride (rule 49).
// Small and at the bottom centre, so that it does not cover the course HUD (top left).
import { t } from '../../i18n.js';
import { h } from '../../dom.js';
import { describeBadge } from '../../../../application/badge-overview.js';
import { badgeEmblem } from './badges-screen.js';

const MAX_VISIBLE = 3;
const MAX_VISIBLE_LOW_SCREEN = 2;
const LOW_SCREEN_HEIGHT = 520;

/**
 * Stack positions of the toasts that are waiting or showing (oldest first).
 * @returns {{ index: number, visible: boolean }[]}
 */
export function toastSlots(count, maxVisible) {
  return Array.from({ length: count }, (_, index) => ({ index, visible: index < maxVisible }));
}

// A toast that is waiting for a free slot starts its timer only when it becomes visible
const starters = new WeakMap();

function layout(layer) {
  const max = window.innerHeight <= LOW_SCREEN_HEIGHT ? MAX_VISIBLE_LOW_SCREEN : MAX_VISIBLE;
  const active = [...layer.querySelectorAll('.badge-toast:not(.is-leaving)')];
  toastSlots(active.length, max).forEach((slot, i) => {
    const el = active[i];
    el.style.setProperty('--toast-index', String(slot.index));
    el.hidden = !slot.visible;
    if (slot.visible) starters.get(el)?.();
  });
}

export function showBadgeToast(layer, badgeId, durationMs = 3200) {
  const badge = describeBadge(badgeId);
  if (!badge) return null;
  const el = h(
    'div',
    { class: 'badge-toast', role: 'status', dataset: { toast: badgeId } },
    badgeEmblem(h, badgeId, true),
    h('span', {}, t('badge.toast', { name: t(badge.nameKey) })),
  );
  let started = false;
  starters.set(el, () => {
    if (started) return;
    started = true;
    setTimeout(() => {
      el.classList.add('is-leaving');
      layout(layer); // the others move down right away
    }, durationMs - 400);
    setTimeout(() => {
      el.remove();
      layout(layer);
    }, durationMs);
  });
  layer.append(el);
  layout(layer);
  return el;
}
