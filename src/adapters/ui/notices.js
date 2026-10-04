// Notices: "rotate device" (rule 12), "no 3D" (rule 7), "saving not possible" (rule 46).
import { onLangChange, t } from './i18n.js';
import { isPortrait } from '../platform/input-mode.js';
import { h, clear } from './dom.js';

// Full-page notice with a horse, title, text and hint; `kind` is the key prefix and data attribute.
function renderFullNotice(root, kind) {
  clear(root);
  root.append(
    h(
      'section',
      { class: `notice notice-${kind}`, role: 'alert', dataset: { notice: kind } },
      h('div', { class: 'notice-emoji', 'aria-hidden': 'true' }, '🐴'),
      h('h1', {}, t(`notice.${kind}.title`)),
      h('p', {}, t(`notice.${kind}.text`)),
      h('p', { class: 'notice-hint' }, t(`notice.${kind}.hint`)),
    ),
  );
}

export const renderNo3dNotice = (root) => renderFullNotice(root, 'no3d');

/** Unexpected start error: a child-friendly notice instead of an empty page. */
export const renderErrorNotice = (root) => renderFullNotice(root, 'error');

/**
 * In portrait orientation with touch mode active, shows the rotate notice above everything.
 * onBlockedChange(true) can be used e.g. to pause the game.
 */
export function installRotateNotice({ layer, inputMode, win = window, onBlockedChange }) {
  const title = h('h2', {});
  const text = h('p', {});
  const el = h(
    'div',
    { class: 'notice notice-rotate', role: 'alert', hidden: true, dataset: { notice: 'rotate' } },
    h('div', { class: 'rotate-icon', 'aria-hidden': 'true' }),
    title,
    text,
  );
  layer.append(el);
  let blocked = false;
  const renderText = () => {
    title.textContent = t('notice.rotate.title');
    text.textContent = t('notice.rotate.text');
  };
  const update = () => {
    const next = inputMode.touch && isPortrait(win);
    el.hidden = !next;
    document.documentElement.classList.toggle('rotate-blocked', next);
    if (next !== blocked) {
      blocked = next;
      onBlockedChange?.(blocked);
    }
  };
  renderText();
  update();
  onLangChange(renderText);
  inputMode.onChange(update);
  win.addEventListener('resize', update);
  win.addEventListener('orientationchange', update);
  return {
    get blocked() {
      return blocked;
    },
    update,
  };
}

export function showSaveNotice(layer) {
  const text = h('p', {}, t('notice.save.text'));
  const ok = h('button', { class: 'btn btn-small', type: 'button' }, t('common.ok'));
  const el = h(
    'div',
    { class: 'toast toast-save', role: 'status', dataset: { notice: 'save' } },
    text,
    ok,
  );
  const off = onLangChange(() => {
    text.textContent = t('notice.save.text');
    ok.textContent = t('common.ok');
  });
  ok.addEventListener('click', () => {
    off();
    el.remove();
  });
  layer.append(el);
  return el;
}
