// Name question on the first start (rule 43). Shown on every start until answered or skipped.
import {
  answerName,
  isValidName,
  NAME_MAX_LENGTH,
  skipName,
} from '../../../../application/horse-service.js';
import { nextStartScreen } from '../../../../application/start-flow.js';

export function createNamePromptScreen(ctx) {
  const { t, h, store, app } = ctx;
  const input = h('input', {
    class: 'text-input',
    type: 'text',
    // maxlength counts UTF-16 units, the name limit counts characters: headroom for surrogate
    // pairs (emoji); cleanName enforces the real limit
    maxlength: String(NAME_MAX_LENGTH * 2),
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
  // after the name question: the controls help on the first start, then the menu (rule 56)
  const done = () => app.go(nextStartScreen(store));
  input.addEventListener('input', () => {
    ok.disabled = !isValidName(input.value);
  });
  skip.addEventListener('click', () => {
    skipName(store);
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
    if (answerName(store, input.value, { defaultName: t('horse.defaultName') })) done();
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
