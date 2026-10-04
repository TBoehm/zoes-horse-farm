// Short toast for instantly awarded badges, without interrupting the ride (rule 49).
// Small and at the bottom centre, so that it does not cover the course HUD (top left). In touch
// mode it sits in the free gap between the joystick and the Gallop/Jump buttons instead.
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

const STACK_GAP = 6;
const GAP_MARGIN = 8;
const GAP_MIN_WIDTH = 120;

/** Bottom offsets (px) of stacked toasts: each one sits above the previous ones. */
export function stackOffsets(heights, gap = STACK_GAP) {
  const offsets = [];
  let offset = 0;
  for (const height of heights) {
    offsets.push(offset);
    offset += height + gap;
  }
  return offsets;
}

/**
 * Horizontal placement inside the free gap between two touch-control groups (viewport px).
 * @param {number} leftEdge right edge of the left group
 * @param {number} rightEdge left edge of the right group
 * @returns {{ x: number, maxWidth: number }} centre of the gap and the widest allowed toast
 */
export function toastGap(
  leftEdge,
  rightEdge,
  { margin = GAP_MARGIN, minWidth = GAP_MIN_WIDTH } = {},
) {
  return {
    x: (leftEdge + rightEdge) / 2,
    maxWidth: Math.max(minWidth, rightEdge - leftEdge - margin * 2),
  };
}

// The touch controls only exist while a ride with touch input is running
function touchGap() {
  const left = document.querySelector('.touch-left')?.getBoundingClientRect();
  const right = document.querySelector('.touch-right')?.getBoundingClientRect();
  if (!left || !right || !left.width || !right.width) return null;
  return toastGap(left.right, right.left);
}

// A toast that is waiting for a free slot starts its timer only when it becomes visible
const starters = new WeakMap();

function layout(layer) {
  const max = window.innerHeight <= LOW_SCREEN_HEIGHT ? MAX_VISIBLE_LOW_SCREEN : MAX_VISIBLE;
  const active = [...layer.querySelectorAll('.badge-toast:not(.is-leaving)')];
  const gap = touchGap();
  const slots = toastSlots(active.length, max);
  slots.forEach((slot, i) => {
    const el = active[i];
    el.hidden = !slot.visible;
    if (gap) {
      el.style.setProperty('--toast-x', `${gap.x}px`);
      el.style.setProperty('--toast-max-w', `${gap.maxWidth}px`);
    } else {
      el.style.removeProperty('--toast-x');
      el.style.removeProperty('--toast-max-w');
    }
  });
  // Heights are measured after the width limit is set: a narrow toast may wrap to two lines
  const shown = active.filter((_, i) => slots[i].visible);
  stackOffsets(shown.map((el) => el.offsetHeight)).forEach((offset, i) => {
    shown[i].style.setProperty('--toast-bottom', `${offset}px`);
    starters.get(shown[i])?.();
  });
}

// Turning the device or resizing the window moves the touch controls: place the toasts again
const layers = new Set();
let watchingResize = false;
function watchResize() {
  if (watchingResize) return;
  watchingResize = true;
  window.addEventListener('resize', () => layers.forEach(layout));
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
  layers.add(layer);
  watchResize();
  layout(layer);
  return el;
}
