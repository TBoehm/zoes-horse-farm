// Settings sections for graphics level, fps display, jump aid (SRT-002, SRT-003, SRT-007) and the
// build version at the bottom (SRT-015).
import { choiceGroup, registerSettingsSection, toggleRow } from './settings-screen.js';
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';
import { APP_VERSION } from '../platform/app-version.js';

registerSettingsSection({
  id: 'graphics',
  order: 20,
  render(ctx) {
    const { t, settings, h } = ctx;
    const s = settings.get();
    const level = choiceGroup({
      name: 'graphics',
      label: t('settings.graphics'),
      value: s.graphicsAuto ? 'auto' : s.graphicsLevel,
      options: ['auto', ...GRAPHICS_LEVELS].map((v) => ({ value: v, label: t(`graphics.${v}`) })),
      onChange: (value) => {
        // "Automatic" starts at low again and climbs from there (rule 4)
        if (value === 'auto') settings.setGraphicsAuto();
        else settings.setGraphicsLevel(value);
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

// Subtle build version line below everything else, in the main menu and in the pause menu
// (rule 58): tells which version runs after a background update.
registerSettingsSection({
  id: 'version',
  order: 99,
  render({ t, h }) {
    return h(
      'p',
      { class: 'settings-version', dataset: { field: 'version' } },
      t('settings.version', { version: APP_VERSION }),
    );
  },
});
