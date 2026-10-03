// Spielstand-Felder und Einstellungs-Abschnitte für Reiten und Springen (SRT-002, SRT-003).
import { addSettingsFields, field, objectSection, registerSection } from '../core/save-schema.js';
import { choiceGroup, registerSettingsSection, toggleRow } from '../app/settings-screen.js';
import { pickInitialLevel, QUALITY_LEVELS } from './view/quality.js';
import { deviceInfo } from './engine.js';
import { PROGRESS_DEFAULTS, sanitizeProgress } from '../progress/progress.js';
import { cleanName } from '../profile/horse-name.js';

export const COATS = ['chestnut', 'bay', 'black', 'grey', 'pinto'];
export const MARKINGS = ['none', 'star', 'blaze', 'snip'];

addSettingsFields({
  graphicsAuto: field.bool(true),
  // null = noch nicht gewählt; die Engine wählt beim ersten Start passend zum Gerät (Regel 4)
  graphicsLevel: field.enum([...QUALITY_LEVELS, null], null),
  camera: field.enum(['follow', 'rider'], 'follow'),
  aidFree: field.bool(true),
  aidCourse: field.bool(false),
});

registerSection(
  'horse',
  objectSection({
    coat: field.enum(COATS, 'bay'),
    marking: field.enum(MARKINGS, 'star'),
    // null = kein eigener Name (Vorgabe-Name der Sprache, Regel 43)
    name: { fallback: null, check: (v) => v === null || cleanName(v) === v },
    nameAnswered: field.bool(false),
  }),
);

registerSection('progress', {
  defaults: () => structuredClone(PROGRESS_DEFAULTS),
  sanitize: (raw) => sanitizeProgress(raw),
});

registerSettingsSection({
  id: 'graphics',
  order: 20,
  render(ctx) {
    const { t, store, services, inputMode } = ctx;
    const s = store.get('settings');
    return choiceGroup({
      name: 'graphics',
      label: t('settings.graphics'),
      value: s.graphicsAuto ? 'auto' : s.graphicsLevel,
      options: ['auto', ...QUALITY_LEVELS].map((v) => ({ value: v, label: t(`graphics.${v}`) })),
      onChange: (value) => {
        if (value === 'auto') {
          // Neue Wahl passend zum Gerät (Regel 4)
          const engine = services.engine;
          const level = engine ? pickInitialLevel(deviceInfo(engine.renderer, inputMode)) : null;
          store.update('settings', (x) => ({ ...x, graphicsAuto: true, graphicsLevel: level }));
        } else {
          store.update('settings', (x) => ({ ...x, graphicsAuto: false, graphicsLevel: value }));
        }
      },
    });
  },
});

registerSettingsSection({
  id: 'aid',
  order: 30,
  render(ctx) {
    const { t, store, h } = ctx;
    const s = store.get('settings');
    const row = (key) =>
      toggleRow({
        name: key,
        label: t(`settings.${key}`),
        value: s[key],
        onChange: (v) => store.update('settings', (x) => ({ ...x, [key]: v })),
      });
    return h('div', { class: 'settings-group' }, row('aidFree'), row('aidCourse'));
  },
});
