// Anmeldung von „Mein Pferd", „Auszeichnungen", Namensfrage und „Fortschritt löschen" (SRT-005).
import { registerMenuEntry, registerMenuHeader } from '../app/menu.js';
import { registerSettingsSection } from '../app/settings-screen.js';
import { createBadgesScreen } from './badges-screen.js';
import { createMyHorseScreen } from './my-horse-screen.js';
import { createNamePromptScreen } from './name-prompt.js';
import { renderResetSection } from './reset-section.js';
import { displayName } from './horse-name.js';

export function registerProfile(app) {
  app.register('namePrompt', createNamePromptScreen);
  app.register('myHorse', createMyHorseScreen);
  app.register('badges', createBadgesScreen);
  registerMenuEntry({
    id: 'horse',
    order: 30,
    labelKey: 'menu.horse',
    onSelect: ({ app }) => app.go('myHorse'),
  });
  registerMenuEntry({
    id: 'badges',
    order: 40,
    labelKey: 'menu.badges',
    onSelect: ({ app }) => app.go('badges'),
  });
  registerMenuHeader(({ h, t, store }) =>
    h(
      'p',
      { class: 'menu-greeting', dataset: { field: 'greeting' } },
      t('menu.greeting', { name: displayName(store.get('horse'), t) }),
    ),
  );
  registerSettingsSection({ id: 'reset', order: 90, render: renderResetSection });
}

/** Erster Bildschirm nach dem Start: Namensfrage, bis sie beantwortet wurde (Regel 43). */
export function firstScreen(store) {
  return store.get('horse').nameAnswered ? 'menu' : 'namePrompt';
}
