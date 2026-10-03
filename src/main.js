// Einstieg: Prüfungen, Spielstand, Sprache, App-Rahmen.
import './styles/main.css';
import './styles/ride.css';
import './styles/profile.css';
import './styles/courses.css';
import { detectLang, getLang, setLang } from './core/i18n.js';
import { createStore, requestPersistentStorage } from './core/storage.js';
import { hasWebGL } from './core/webgl.js';
import { createInputMode, detectDevice } from './core/input-mode.js';
import { registerAllStrings } from './i18n/index.js';
import { createApp } from './app/app.js';
import { createMainMenuScreen, registerMenuEntry } from './app/menu.js';
import { createSettingsScreen } from './app/settings-screen.js';
import { installRotateNotice, renderNo3dNotice, showSaveNotice } from './app/notices.js';
import './game/save-sections.js';
import { createRideScreen } from './game/ride-screen.js';
import { firstScreen, registerProfile } from './profile/profile.js';
import { registerCourses } from './course-ui/register.js';
import { registerAudio } from './audio/register.js';

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
