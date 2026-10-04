// Settings sections for graphics level, fps display and jump aid (SRT-002, SRT-003, SRT-007).
import { choiceGroup, registerSettingsSection, toggleRow } from './settings-screen.js';
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';
import { pickInitialLevel } from '../view3d/quality.js';
import { deviceInfo } from '../view3d/engine.js';

registerSettingsSection({
  id: 'graphics',
  order: 20,
  render(ctx) {
    const { t, settings, services, inputMode, h } = ctx;
    const s = settings.get();
    const level = choiceGroup({
      name: 'graphics',
      label: t('settings.graphics'),
      value: s.graphicsAuto ? 'auto' : s.graphicsLevel,
      options: ['auto', ...GRAPHICS_LEVELS].map((v) => ({ value: v, label: t(`graphics.${v}`) })),
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
    // frame-rate display in the ride (rules 4, 44)
    const fps = toggleRow({
      name: 'showFps',
      label: t('settings.showFps'),
      value: s.showFps,
      onChange: (on) => settings.setShowFps(on),
    });
    // own row in the two-column landscape layout: the two jump-aid switches below stay a pair
    fps.classList.add('setting-row-wide');
    return h('div', { class: 'settings-group' }, level, fps);
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
