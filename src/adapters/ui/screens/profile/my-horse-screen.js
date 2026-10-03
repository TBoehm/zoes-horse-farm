// „Mein Pferd": Name, Fellfarbe, Kopfabzeichen mit 3D-Vorschau (Regel 43).
import { choiceGroup } from '../app/settings-screen.js';
import { getEngine } from '../game/engine.js';
import { COATS, MARKINGS } from '../game/save-sections.js';
import { cleanName, NAME_MAX } from './horse-name.js';

const IDLE = { speed: 0, gait: 'halt', turnRate: 0, y: 0, jump: null, hop: null, refusal: null };

export function createMyHorseScreen(ctx) {
  const { t, h, store, app } = ctx;
  const horseData = store.get('horse');
  const engine = getEngine(ctx);
  const { horse, camera, world } = engine;

  const nameInput = h('input', {
    class: 'text-input',
    type: 'text',
    maxlength: String(NAME_MAX * 2),
    value: horseData.name ?? t('horse.defaultName'),
    autocomplete: 'off',
    spellcheck: 'false',
    'aria-label': t('myHorse.name'),
    dataset: { field: 'horseName' },
  });
  // Ungültige Eingabe wird nicht gespeichert; es gilt der letzte gültige Name (Regel 43)
  const saveName = () => {
    const name = cleanName(nameInput.value);
    if (name === null) return;
    const current = store.get('horse');
    const isDefault = current.name === null && name === t('horse.defaultName');
    if (isDefault || name === current.name) return;
    store.update('horse', (x) => ({ ...x, name, nameAnswered: true }));
  };
  nameInput.addEventListener('input', saveName);
  nameInput.addEventListener('blur', () => {
    const current = store.get('horse');
    nameInput.value = current.name ?? t('horse.defaultName');
  });

  const update = (key) => (value) => {
    const next = store.update('horse', (x) => ({ ...x, [key]: value }));
    horse.setAppearance({ coat: next.coat, marking: next.marking });
  };

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
        options: COATS.map((c) => ({ value: c, label: t(`coat.${c}`) })),
        onChange: update('coat'),
      }),
      choiceGroup({
        name: 'marking',
        label: t('myHorse.marking'),
        value: horseData.marking,
        options: MARKINGS.map((m) => ({ value: m, label: t(`marking.${m}`) })),
        onChange: update('marking'),
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

  // 3D-Vorschau: Pferd steht auf dem Platz, Kamera kreist langsam
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
    // Pferd im Querformat rechts neben dem Bedienfeld zeigen
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
