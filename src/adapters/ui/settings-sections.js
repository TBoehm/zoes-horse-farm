// Settings sections for graphics level and jump aid (SRT-002, SRT-003).
import { choiceGroup, registerSettingsSection, toggleRow } from './settings-screen.js';
import { pickInitialLevel, QUALITY_LEVELS } from '../view3d/quality.js';
import { deviceInfo } from '../view3d/engine.js';

registerSettingsSection({
  id: 'graphics',
  order: 20,
  render(ctx) {
    const { t, settings, services, inputMode } = ctx;
    const s = settings.get();
    return choiceGroup({
      name: 'graphics',
      label: t('settings.graphics'),
      value: s.graphicsAuto ? 'auto' : s.graphicsLevel,
      options: ['auto', ...QUALITY_LEVELS].map((v) => ({ value: v, label: t(`graphics.${v}`) })),
      onChange: (value) => {
        if (value === 'auto') {
          // New pick that fits the device (rule 4)
          const engine = services.engine;
          const level = engine ? pickInitialLevel(deviceInfo(engine.renderer, inputMode)) : null;
          settings.setGraphicsAuto(level);
        } else {
          settings.setGraphicsLevel(value);
        }
      },
    });
  },
});

registerSettingsSection({
  id: 'aid',
  order: 30,
  render(ctx) {
    const { t, settings, h } = ctx;
    const s = settings.get();
    const row = (key, mode) =>
      toggleRow({
        name: key,
        label: t(`settings.${key}`),
        value: s[key],
        onChange: (v) => settings.setAid(mode, v),
      });
    return h(
      'div',
      { class: 'settings-group' },
      row('aidFree', 'free'),
      row('aidCourse', 'course'),
    );
  },
});
