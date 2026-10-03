// Namensfrage beim ersten Start (Regel 43). Erscheint bei jedem Start, bis beantwortet/übersprungen.
import { cleanName, NAME_MAX } from '../../../../domain/horse/horse-name.js';

export function createNamePromptScreen(ctx, params = {}) {
  const { t, h, store, app } = ctx;
  const input = h('input', {
    class: 'text-input',
    type: 'text',
    maxlength: String(NAME_MAX * 2),
    autocomplete: 'off',
    autocapitalize: 'words',
    spellcheck: 'false',
    placeholder: t('namePrompt.placeholder'),
    'aria-label': t('namePrompt.title'),
    dataset: { field: 'horseName' },
  });
  const ok = h(
    'button',
    { class: 'btn btn-menu', type: 'submit', disabled: true, dataset: { action: 'ok' } },
    t('namePrompt.ok'),
  );
  const skip = h(
    'button',
    { class: 'btn btn-secondary', type: 'button', dataset: { action: 'skip' } },
    t('namePrompt.skip'),
  );
  const done = () => app.go(params.next ?? 'menu');
  input.addEventListener('input', () => {
    ok.disabled = cleanName(input.value) === null;
  });
  skip.addEventListener('click', () => {
    store.update('horse', (x) => ({ ...x, name: null, nameAnswered: true }));
    done();
  });
  const form = h(
    'form',
    { class: 'name-form' },
    input,
    h('p', { class: 'field-hint' }, t('namePrompt.hint')),
    h('div', { class: 'panel-actions' }, ok, skip),
  );
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const name = cleanName(input.value);
    if (!name) return;
    store.update('horse', (x) => ({ ...x, name, nameAnswered: true }));
    done();
  });
  const el = h(
    'section',
    { class: 'panel panel-name' },
    h('div', { class: 'notice-emoji', 'aria-hidden': 'true' }, '🐴'),
    h('h2', {}, t('namePrompt.title')),
    form,
  );
  queueMicrotask(() => input.focus({ preventScroll: true }));
  return { el, music: true };
}
