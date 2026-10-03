// Keyboard focus trap for modal dialogs (the pause menu): Tab/Shift+Tab stay inside.

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/** Index reached by Tab (or Shift+Tab) from `current` (-1 = focus is outside); wraps around. */
export function nextFocusIndex(count, current, backwards) {
  if (count <= 0) return -1;
  if (current < 0) return backwards ? count - 1 : 0;
  return (current + (backwards ? -1 : 1) + count) % count;
}

/** Enabled controls inside `root` that are not hidden, in DOM order. */
export function focusableIn(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter(
    (el) => !el.disabled && !el.closest('[hidden]'),
  );
}

/** Handles a Tab keydown for a trap around `root`. Returns true if the event was handled. */
export function trapTab(event, root) {
  if (event.key !== 'Tab') return false;
  const items = focusableIn(root);
  if (!items.length) return false;
  event.preventDefault();
  const next = nextFocusIndex(items.length, items.indexOf(document.activeElement), event.shiftKey);
  items[next].focus({ preventScroll: true });
  return true;
}
