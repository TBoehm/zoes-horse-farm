// "My horse": name, coat color and head marking with a 3D preview (rule 43).
import { choiceGroup } from '../../settings-screen.js';
import { getEngine } from '../../../view3d/engine.js';
import {
  appearanceOptions,
  displayName,
  isValidName,
  NAME_MAX_LENGTH,
  rename,
  setAppearance,
} from '../../../../application/horse-service.js';

const IDLE = { speed: 0, gait: 'halt', turnRate: 0, y: 0, jump: null, hop: null, refusal: null };

export function createMyHorseScreen(ctx) {
  const { t, h, store, app } = ctx;
  const horseData = store.get('horse');
  const engine = getEngine(ctx);
  const { horse, camera, world } = engine;

  const nameInput = h('input', {
    class: 'text-input',
    type: 'text',
    maxlength: String(NAME_MAX_LENGTH * 2),
    value: displayName(horseData, t('horse.defaultName')),
    autocomplete: 'off',
    spellcheck: 'false',
    'aria-label': t('myHorse.name'),
    dataset: { field: 'horseName' },
  });
  const nameHint = h(
    'p',
    { class: 'field-hint is-invalid', role: 'alert', hidden: true },
    t('namePrompt.hint'),
  );
  const showValidity = (valid) => {
    nameInput.classList.toggle('is-invalid', !valid);
    nameInput.setAttribute('aria-invalid', String(!valid));
    nameHint.hidden = valid;
  };
  // An invalid input is not saved; the last valid name stays (rule 43)
  nameInput.addEventListener('input', () => {
    rename(store, nameInput.value, { defaultName: t('horse.defaultName') });
    showValidity(isValidName(nameInput.value));
  });
  nameInput.addEventListener('blur', () => {
    nameInput.value = displayName(store.get('horse'), t('horse.defaultName'));
    showValidity(true);
  });

  const choose = (key) => (value) => horse.setAppearance(setAppearance(store, { [key]: value }));
  const { coats, markings } = appearanceOptions();

  const panel = h(
    'section',
    { class: 'panel panel-horse' },
    h('h2', {}, t('myHorse.title')),
    h(
      'div',
      { class: 'settings-body' },
      // Full-width row: the input must not shrink to a few pixels next to the label on phones
      h(
        'label',
        { class: 'setting-row setting-row-wide setting-name' },
        h('span', { class: 'setting-label' }, t('myHorse.name')),
        nameInput,
        nameHint,
      ),
      choiceGroup({
        name: 'coat',
        label: t('myHorse.coat'),
        value: horseData.coat,
        options: coats.map((c) => ({ value: c, label: t(`coat.${c}`) })),
        onChange: choose('coat'),
      }),
      choiceGroup({
        name: 'marking',
        label: t('myHorse.marking'),
        value: horseData.marking,
        options: markings.map((m) => ({ value: m, label: t(`marking.${m}`) })),
        onChange: choose('marking'),
      }),
    ),
    h(
      'div',
      { class: 'panel-actions' },
      h(
        'button',
        {
          class: 'btn btn-secondary',
          type: 'button',
          dataset: { action: 'back' },
          onclick: () => app.go('menu'),
        },
        t('common.back'),
      ),
    ),
  );
  const el = h('div', { class: 'horse-layout' }, panel, h('div', { class: 'horse-preview-space' }));

  // 3D preview: the horse stands in the arena, the camera circles slowly
  world.setObstacles([], { flags: false });
  world.setAid(null);
  world.highlight(null);
  world.setLines?.(null);
  horse.setAppearance({ coat: horseData.coat, marking: horseData.marking });
  horse.object.position.set(0, 0, 0);
  horse.object.rotation.y = 0;
  let angle = 0.9;
  engine.run((dt) => {
    angle += dt * 0.25;
    const w = window.innerWidth;
    const hgt = window.innerHeight;
    // In landscape, shift the picture so that the horse stays in the free area right of the panel
    const shift = w > hgt ? (panel.getBoundingClientRect().width + 16) / 2 : 0;
    camera.setViewOffset(w, hgt, -shift, 0, w, hgt);
    const r = 5.2;
    camera.position.set(Math.sin(angle) * r, 1.9, Math.cos(angle) * r);
    camera.lookAt(0, 1.15, 0);
    horse.update(dt, IDLE);
    world.setShadowFocus?.(0, 0);
    world.update?.(dt, camera);
  });

  return {
    el,
    music: true,
    screenClass: 'screen-horse',
    destroy() {
      engine.run(null);
      camera.clearViewOffset();
    },
  };
}
