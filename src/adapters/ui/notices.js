// Notices: "rotate device" (rule 12), "no 3D" (rule 7), "saving not possible" (rule 46).
import { onLangChange, t } from './i18n.js';
import { isPortrait } from '../platform/input-mode.js';
import { h, clear } from './dom.js';

export function renderNo3dNotice(root) {
  clear(root);
  root.append(
    h(
      'section',
      { class: 'notice notice-no3d', role: 'alert', dataset: { notice: 'no3d' } },
      h('div', { class: 'notice-emoji', 'aria-hidden': 'true' }, '🐴'),
      h('h1', {}, t('notice.no3d.title')),
      h('p', {}, t('notice.no3d.text')),
      h('p', { class: 'notice-hint' }, t('notice.no3d.hint')),
    ),
  );
}

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
