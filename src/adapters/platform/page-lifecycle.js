// Page lifecycle for the crash guard (rule 4): whatever happens to a page that is hidden or being
// closed is not a crash. `visibilitychange` (hidden) and `pagehide` (also fired on a reload and on
// a normal close) mark the guard clean; coming back (visible, `pageshow`) marks it again.

/**
 * @param {{ markBackground(): void, resume(): void }} guard
 * @param {{ doc?: EventTarget & { visibilityState?: string, hidden?: boolean },
 *   win?: EventTarget }} [targets]
 * @returns {() => void} removes the listeners
 */
export function installPageLifecycle(guard, { doc = document, win = window } = {}) {
  const isHidden = () => doc.visibilityState === 'hidden' || doc.hidden === true;
  const onVisibility = () => (isHidden() ? guard.markBackground() : guard.resume());
  const onPageHide = () => guard.markBackground();
  const onPageShow = () => {
    if (!isHidden()) guard.resume();
  };
  doc.addEventListener('visibilitychange', onVisibility);
  win.addEventListener('pagehide', onPageHide);
  win.addEventListener('pageshow', onPageShow);
  return () => {
    doc.removeEventListener('visibilitychange', onVisibility);
    win.removeEventListener('pagehide', onPageHide);
    win.removeEventListener('pageshow', onPageShow);
  };
}
