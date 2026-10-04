// Controls help (rule 56): key caps or touch symbols with a short text. Shown once after the name
// question, again on demand from the main menu and from the pause menu.
import { nextStartScreen } from '../../../application/start-flow.js';
import { clear } from '../dom.js';
import { registerMenuEntry } from '../menu.js';
import { choiceGroup } from '../settings-screen.js';
import { defaultHelpMode, HELP_MODES, rowsFor } from './help-content.js';

/**
 * @param {object} ctx app context
 * @param {{ fromPause?: boolean }} params fromPause: opened on top of the paused ride (rule 56).
 *   The shown input type is local: nothing in the app changes the language while this screen is
 *   open (the settings cannot be reached from here), so there is nothing to keep across a rebuild.
 */
export function createControlsHelpScreen(ctx, params = {}) {
  const { t, h, app, store, settings, inputMode } = ctx;
  let mode = defaultHelpMode(inputMode.touch);

  const keyCap = (key) => h('kbd', { class: 'keycap' }, key.labelKey ? t(key.labelKey) : key.label);

  function visual(row) {
    if (row.keys) {
      const alternatives = row.keys.map((group) =>
        h('span', { class: 'keycap-group' }, group.map(keyCap)),
      );
      return alternatives.flatMap((group, i) =>
        i === 0 ? [group] : [h('span', { class: 'help-or', 'aria-hidden': 'true' }, '/'), group],
      );
    }
    return row.glyphs.map((glyph) =>
      h(
        'span',
        {
          class: `help-glyph help-glyph-${glyph.kind}`,
          // a symbol without a label is named for screen readers; labeled ones are read as text
          ...(glyph.nameKey
            ? { role: 'img', 'aria-label': t(glyph.nameKey) }
            : { 'aria-hidden': 'true' }),
        },
        glyph.labelKey ? t(glyph.labelKey) : glyph.symbol,
      ),
    );
  }

  const list = h('ul', { class: 'help-list' });
  function renderList() {
    clear(list);
    list.append(
      ...rowsFor(mode).map((row) =>
        h(
          'li',
          { class: 'help-row', dataset: { help: row.id } },
          h('span', { class: 'help-visual' }, visual(row)),
          h('span', { class: 'help-text' }, t(row.textKey)),
        ),
      ),
    );
  }
  renderList();

  const modeSwitch = choiceGroup({
    name: 'helpMode',
    label: t('help.mode'),
    value: mode,
    options: HELP_MODES.map((value) => ({ value, label: t(`help.mode.${value}`) })),
    onChange: (next) => {
      mode = next;
      renderList();
    },
  });
  modeSwitch.classList.add('help-switch');

  const done = h(
    'button',
    { class: 'btn btn-menu', type: 'button', dataset: { action: 'help-done' } },
    t('help.done'),
  );
  done.addEventListener('click', () => {
    settings.markControlsHelpSeen();
    // from the pause menu: back to the paused ride; otherwise the next step of the start sequence
    // (the main menu once the name question and this help are done)
    if (params.fromPause) app.pop();
    else app.go(nextStartScreen(store));
  });

  const el = h(
    'section',
    { class: 'panel panel-help', 'aria-labelledby': 'help-title' },
    h('div', { class: 'help-head' }, h('h2', { id: 'help-title' }, t('help.title')), modeSwitch),
    h('div', { class: 'help-body' }, list),
    h('div', { class: 'panel-actions' }, done),
  );
  // keyboard users start on the switch (Enter / Tab from there); the selected option has the focus
  queueMicrotask(() => el.querySelector('[aria-checked="true"]')?.focus({ preventScroll: true }));
  return { el, music: !params.fromPause };
}

export function registerControlsHelp(app) {
  app.register('controlsHelp', createControlsHelpScreen);
  registerMenuEntry({
    id: 'help',
    order: 45,
    labelKey: 'menu.help',
    onSelect: ({ app }) => app.go('controlsHelp', {}),
  });
}
