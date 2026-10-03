// Course HUD (SRT-004): renders the plain-data HUD model of the course mode as DOM chips.
// Registered for the mode id 'course' (see register.js); the ride screen owns the instance.
import { getLang, t } from '../../i18n.js';
import { h } from '../../dom.js';
import { formatSeconds } from './format.js';

/**
 * @returns {{ el: HTMLElement, renderTexts(): void, render(model: object): void }}
 * model = { phase, timeMs, allowedS, faults, overTime, nextLabel, missingHint }
 */
export function createCourseHud() {
  const chip = (field) => {
    const label = h('small', {});
    const value = h('span', { dataset: { hud: field } });
    return {
      el: h('div', { class: 'hud-chip', dataset: { chip: field } }, label, value),
      label,
      value,
    };
  };
  const time = chip('time');
  const allowed = chip('allowed');
  const faults = chip('faults');
  const next = chip('next');
  const notice = h('div', { class: 'hud-chip hud-notice', dataset: { hud: 'notice' } });
  const el = h('div', { class: 'hud-row' }, time.el, allowed.el, faults.el, next.el, notice);

  return {
    el,
    renderTexts() {
      time.label.textContent = t('hud.time');
      allowed.label.textContent = t('hud.allowed');
      faults.label.textContent = t('hud.faults');
      next.label.textContent = t('hud.next');
    },
    render(model) {
      const lang = getLang();
      const riding = model.phase !== 'prestart';
      time.value.textContent = `${formatSeconds(Math.floor(model.timeMs / 10), lang)} s`;
      allowed.value.textContent = `${model.allowedS} s`;
      faults.value.textContent = String(model.faults);
      next.value.textContent =
        model.nextLabel === 'finish' ? t('hud.finish') : String(model.nextLabel);
      time.el.hidden = !riding;
      faults.el.hidden = !riding;
      time.el.classList.toggle('is-warning', model.overTime);
      if (model.phase === 'prestart') {
        notice.textContent = t('ride.prestartHint');
        notice.hidden = false;
      } else if (model.missingHint !== null && model.missingHint !== undefined) {
        notice.textContent = t('hud.missing', { n: model.missingHint });
        notice.hidden = false;
      } else {
        notice.hidden = true;
      }
    },
  };
}
