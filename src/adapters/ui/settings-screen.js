// Settings with extensible sections. Feature areas register sections with
// registerSettingsSection. params.fromPause: opened from the pause menu (rules 38, 48, 51).
import { LANGS, setLang } from './i18n.js';
import { h } from './dom.js';

const sections = [];

/** @param {{ id: string, order: number, render: (ctx, opts) => Node|null }} section */
export function registerSettingsSection(section) {
  const i = sections.findIndex((s) => s.id === section.id);
  if (i >= 0) sections.splice(i, 1);
  sections.push(section);
  sections.sort((a, b) => a.order - b.order);
}

/** Choice group of large buttons (radio behaviour). */
export function choiceGroup({ label, options, value, onChange, name }) {
  const group = h('div', { class: 'choice-group', role: 'radiogroup', 'aria-label': label });
  const buttons = options.map((opt) =>
    h(
      'button',
      {
        class: 'btn btn-choice',
        type: 'button',
        role: 'radio',
        'aria-checked': String(opt.value === value),
        dataset: { name, value: String(opt.value) },
        onclick: () => {
          for (const b of buttons) b.setAttribute('aria-checked', String(b === buttons[idx(opt)]));
          onChange(opt.value);
        },
      },
      opt.label,
    ),
  );
  const idx = (opt) => options.indexOf(opt);
  group.append(...buttons);
  const wide = options.length > 3 ? ' setting-row-wide' : '';
  return h(
    'div',
    { class: `setting-row${wide}` },
    h('span', { class: 'setting-label' }, label),
    group,
  );
}

/**
 * On/off switch as a large button. Returns the row with its label, or only the switch button when
 * `label` is left out (for rows that bring their own label, e.g. volume rows).
 */
export function toggleRow({ label, value, onChange, name }) {
  const btn = h(
    'button',
    {
      class: 'btn btn-toggle',
      type: 'button',
      role: 'switch',
      'aria-checked': String(value),
      dataset: { name },
    },
    h('span', { class: 'toggle-knob', 'aria-hidden': 'true' }),
  );
  btn.addEventListener('click', () => {
    const next = btn.getAttribute('aria-checked') !== 'true';
    btn.setAttribute('aria-checked', String(next));
    onChange(next);
  });
  if (label === undefined) return btn;
  return h('div', { class: 'setting-row' }, h('span', { class: 'setting-label' }, label), btn);
}

registerSettingsSection({
  id: 'language',
  order: 10,
  render(ctx) {
    const { t, store } = ctx;
    return choiceGroup({
      name: 'lang',
      label: t('settings.language'),
      value: store.get('settings').lang,
      options: LANGS.map((l) => ({ value: l, label: t(`lang.${l}`) })),
      onChange: (lang) => {
        store.update('settings', (s) => ({ ...s, lang }));
        setLang(lang);
      },
    });
  },
});

export function createSettingsScreen(ctx, params = {}) {
  const { t, app } = ctx;
  const opts = { fromPause: Boolean(params.fromPause) };
  const body = h(
    'div',
    { class: 'settings-body' },
    sections.map((s) => s.render(ctx, opts)).filter(Boolean),
  );
  const back = h(
    'button',
    {
      class: 'btn btn-secondary',
      type: 'button',
      dataset: { action: 'back' },
      onclick: () => (opts.fromPause ? app.pop() : app.go('menu')),
    },
    t('common.back'),
  );
  const el = h(
    'section',
    { class: 'panel panel-settings' },
    h('h2', {}, t('settings.title')),
    body,
    h('div', { class: 'panel-actions' }, back),
  );
  // Music only when opened from the main menu (rule 51)
  return { el, music: !opts.fromPause };
}
