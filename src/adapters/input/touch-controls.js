// Touch controls (rule 10): joystick on the left, "Gallop" (toggle) and "Jump" on the right,
// "Pause" and "Camera" at the top right. All elements at least 44×44 px.
import nipplejs from 'nipplejs';
import { onLangChange, t } from '../ui/i18n.js';
import { h } from '../ui/dom.js';
import { readStickEvent } from './joystick-mapping.js';

export function createTouchControls(container) {
  let steer = 0;
  let throttle = 0;
  let gallop = false;
  let jump = false;
  let pause = false;
  let camera = false;

  const base = h('div', { class: 'touch-joystick', dataset: { control: 'joystick' } });
  const gallopBtn = h(
    'button',
    { class: 'touch-btn touch-gallop', type: 'button', 'aria-pressed': 'false' },
    h('span', { class: 'touch-gallop-light', 'aria-hidden': 'true' }),
    h('span', { class: 'touch-label' }),
  );
  const jumpBtn = h(
    'button',
    { class: 'touch-btn touch-jump', type: 'button' },
    h('span', { class: 'touch-label' }),
  );
  const pauseBtn = h('button', { class: 'touch-btn touch-small touch-pause', type: 'button' });
  const cameraBtn = h('button', { class: 'touch-btn touch-small touch-camera', type: 'button' });

  const root = h(
    'div',
    { class: 'touch-controls', dataset: { control: 'touch' } },
    h('div', { class: 'touch-left' }, base),
    h('div', { class: 'touch-right' }, gallopBtn, jumpBtn),
    h('div', { class: 'touch-top' }, cameraBtn, pauseBtn),
  );
  container.append(root);

  const renderTexts = () => {
    gallopBtn.querySelector('.touch-label').textContent = t('ride.touch.gallop');
    jumpBtn.querySelector('.touch-label').textContent = t('ride.touch.jump');
    pauseBtn.textContent = '❚❚';
    pauseBtn.setAttribute('aria-label', t('ride.touch.pause'));
    cameraBtn.textContent = '🎥';
    cameraBtn.setAttribute('aria-label', t('ride.touch.camera'));
  };
  renderTexts();
  const offLang = onLangChange(renderTexts);

  const setGallop = (on) => {
    gallop = on;
    gallopBtn.setAttribute('aria-pressed', String(on));
    gallopBtn.classList.toggle('is-on', on);
  };

  // Joystick via nipplejs (static in the bottom-left zone)
  const releaseStick = () => {
    steer = 0;
    throttle = 0;
  };
  let manager = null;
  const ensureStick = () => {
    if (manager || root.hidden) return;
    manager = nipplejs.create({
      zone: base,
      mode: 'static',
      position: { left: '50%', top: '50%' },
      size: 130,
      color: 'rgba(255,255,255,0.85)',
      restOpacity: 0.6,
      multitouch: false,
    });
    // nipplejs 1.x: handlers get ONE argument { type, target, data }
    manager.on('move', (_e, evt) => {
      ({ steer, throttle } = readStickEvent(evt));
    });
    manager.on('end', releaseStick);
  };

  const press = (btn, fn) =>
    btn.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      fn();
    });
  press(gallopBtn, () => setGallop(!gallop));
  press(jumpBtn, () => (jump = true));
  press(pauseBtn, () => (pause = true));
  press(cameraBtn, () => (camera = true));
  // Keyboard/click operation without a pointer (e.g. screen readers)
  for (const [btn, fn] of [
    [gallopBtn, () => setGallop(!gallop)],
    [jumpBtn, () => (jump = true)],
    [pauseBtn, () => (pause = true)],
    [cameraBtn, () => (camera = true)],
  ]) {
    btn.addEventListener('click', (e) => {
      if (e.detail === 0) fn();
    });
  }

  return {
    root,
    poll() {
      const state = { steer, throttle, gallop, jump, pause, camera };
      jump = pause = camera = false;
      return state;
    },
    setGallop,
    get gallop() {
      return gallop;
    },
    setVisible(visible) {
      root.hidden = !visible;
      if (visible) ensureStick();
      else releaseStick();
    },
    clearEdges() {
      jump = pause = camera = false;
    },
    dispose() {
      offLang();
      manager?.destroy();
      root.remove();
    },
  };
}
