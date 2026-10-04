// "Delete progress" in the settings, only from the main menu (rule 48).
import { resetProgress } from '../../../../application/progress-service.js';

export function renderResetSection(ctx, { fromPause }) {
  if (fromPause) return null;
  const { t, h, store } = ctx;
  const status = h('p', { class: 'field-hint', role: 'status' });
  const cancel = h(
    'button',
    {
      class: 'btn btn-secondary',
      type: 'button',
      dataset: { action: 'reset-cancel' },
      onclick: () => {
        confirmBox.hidden = true;
        trigger.hidden = false;
        trigger.focus({ preventScroll: true });
      },
    },
    t('reset.cancel'),
  );
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
          class: 'btn btn-danger',
          type: 'button',
          dataset: { action: 'reset-confirm' },
          onclick: () => {
            resetProgress(store);
            confirmBox.hidden = true;
            trigger.hidden = false;
            trigger.focus({ preventScroll: true });
            status.textContent = t('reset.done');
          },
        },
        t('reset.confirm'),
      ),
      cancel,
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
        // the safe choice has the focus (Enter or Space does not delete by accident)
        cancel.focus({ preventScroll: true });
        // on a low phone screen the question and both buttons must be fully visible
        confirmBox.scrollIntoView({ block: 'nearest' });
      },
    },
    t('reset.button'),
  );
  return h('div', { class: 'setting-row setting-reset' }, trigger, confirmBox, status);
}
