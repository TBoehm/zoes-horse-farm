// Einstieg: Prüfungen, Spielstand, Sprache, App-Rahmen.
import './adapters/ui/styles/main.css';
import './adapters/ui/styles/ride.css';
import './adapters/ui/styles/profile.css';
import './adapters/ui/styles/courses.css';
import { detectLang, getLang, setLang } from './adapters/ui/i18n.js';
import { createStore, requestPersistentStorage } from './adapters/storage/local-store.js';
import { hasWebGL } from './adapters/platform/webgl.js';
import { createInputMode, detectDevice } from './adapters/platform/input-mode.js';
import { registerAllStrings } from './adapters/ui/i18n/index.js';
import { createApp } from './adapters/ui/app.js';
import { createMainMenuScreen, registerMenuEntry } from './adapters/ui/menu.js';
import { createSettingsScreen } from './adapters/ui/settings-screen.js';
import { installRotateNotice, renderNo3dNotice, showSaveNotice } from './adapters/ui/notices.js';
import './application/settings-schema.js';
import './adapters/ui/settings-sections.js';
import { createRideScreen } from './adapters/ui/screens/ride-screen.js';
import { firstScreen, registerProfile } from './adapters/ui/screens/profile/register.js';
import { registerCourses } from './adapters/ui/screens/courses/register.js';
import { registerAudio } from './adapters/ui/audio-wiring.js';

function boot() {
  const root = document.getElementById('app');
  registerAllStrings();

  const store = createStore({ env: { defaultLang: detectLang(navigator.languages) } });
  setLang(store.get('settings').lang);
  document.documentElement.lang = getLang();

  // Regel 7: ohne 3D statt der ganzen App nur der Hinweis
  if (!hasWebGL()) {
    renderNo3dNotice(root);
    return;
  }

  store.flush();
  requestPersistentStorage();

  const inputMode = createInputMode({ device: detectDevice(window), target: window });
  document.documentElement.classList.toggle('touch-mode', inputMode.touch);
  inputMode.onChange((touch) => document.documentElement.classList.toggle('touch-mode', touch));

  const app = createApp({ root, store, inputMode });
  app.register('menu', createMainMenuScreen);
  app.register('settings', createSettingsScreen);
  app.register('ride', createRideScreen);
  registerProfile(app);
  registerCourses(app);
  registerAudio(app, store);
  registerMenuEntry({
    id: 'free',
    order: 20,
    labelKey: 'menu.free',
    onSelect: ({ app }) => app.go('ride', { mode: 'free' }),
  });
  registerMenuEntry({
    id: 'settings',
    order: 50,
    labelKey: 'menu.settings',
    onSelect: ({ app }) => app.go('settings'),
  });

  installRotateNotice({
    layer: app.layers.overlay,
    inputMode,
    onBlockedChange: (blocked) => app.emit('rotateBlocked', blocked),
  });

  if (store.shouldShowSaveNotice()) showSaveNotice(app.layers.overlay);
  store.onSaveFailed(() => {
    if (store.shouldShowSaveNotice()) showSaveNotice(app.layers.overlay);
  });

  app.go(firstScreen(store));
  window.__zhf = { app, store, inputMode };
}

try {
  boot();
} catch (err) {
  // Unerwarteter Startfehler: kindgerechter Hinweis statt leerer Seite (Sinn von Regel 7)
  console.error(err);
  renderNo3dNotice(document.getElementById('app'));
}
