// „Fortschritt löschen" in den Einstellungen, nur aus dem Hauptmenü (Regel 48).
import { resetProgress } from '../../../../domain/progress/progress.js';

export function renderResetSection(ctx, { fromPause }) {
  if (fromPause) return null;
  const { t, h, store } = ctx;
  const status = h('p', { class: 'field-hint', role: 'status' });
  const confirmBox = h(
    'div',
    { class: 'confirm-box', hidden: true, role: 'alertdialog', dataset: { dialog: 'reset' } },
    h('p', {}, t('reset.question')),
    h(
      'div',
      { class: 'panel-actions' },
      h(
        'button',
        {
          class: 'btn',
          type: 'button',
          dataset: { action: 'reset-confirm' },
          onclick: () => {
            store.update('progress', (p) => resetProgress(p));
            confirmBox.hidden = true;
            trigger.hidden = false;
            status.textContent = t('reset.done');
          },
        },
        t('reset.confirm'),
      ),
      h(
        'button',
        {
          class: 'btn btn-secondary',
          type: 'button',
          dataset: { action: 'reset-cancel' },
          onclick: () => {
            confirmBox.hidden = true;
            trigger.hidden = false;
          },
        },
        t('reset.cancel'),
      ),
    ),
  );
  const trigger = h(
    'button',
    {
      class: 'btn btn-danger',
      type: 'button',
      dataset: { action: 'reset' },
      onclick: () => {
        confirmBox.hidden = false;
        trigger.hidden = true;
        status.textContent = '';
      },
    },
    t('reset.button'),
  );
  return h('div', { class: 'setting-row setting-reset' }, trigger, confirmBox, status);
}
