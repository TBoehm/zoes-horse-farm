// "My horse": name, coat color and head marking with a 3D preview (rule 43).
import { choiceGroup } from '../../settings-screen.js';
import { getEngine } from '../../../view3d/engine.js';
import {
  appearanceOptions,
  displayName,
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
  // An invalid input is not saved; the last valid name stays (rule 43)
  const saveName = () => rename(store, nameInput.value, { defaultName: t('horse.defaultName') });
  nameInput.addEventListener('input', saveName);
  nameInput.addEventListener('blur', () => {
    nameInput.value = displayName(store.get('horse'), t('horse.defaultName'));
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
      h(
        'label',
        { class: 'setting-row' },
        h('span', { class: 'setting-label' }, t('myHorse.name')),
        nameInput,
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
    const wide = window.innerWidth > window.innerHeight;
    const r = 5.2;
    // In landscape, show the horse to the right of the panel
    const side = wide ? -1.6 : 0;
    camera.position.set(Math.sin(angle) * r + side, 1.9, Math.cos(angle) * r);
    camera.lookAt(side * 0.55, 1.15, 0);
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
    },
  };
}
