// Entry point and composition root: checks, save game, language, app frame, wiring of all parts.
// No rules here: ports (store, clock, rng) are created and handed to the layers.
import './adapters/ui/styles/main.css';
import './adapters/ui/styles/ride.css';
import './adapters/ui/styles/profile.css';
import './adapters/ui/styles/courses.css';
import { detectLang, getLang, setLang } from './adapters/ui/i18n.js';
import { createStore, requestPersistentStorage } from './adapters/storage/local-store.js';
import { hasWebGL } from './adapters/platform/webgl.js';
import { createInputMode, detectDevice } from './adapters/platform/input-mode.js';
import {
  gpuBudgetOverride,
  installTestHooks,
  testHooksRequested,
} from './adapters/platform/test-hooks.js';
import { systemClock } from './adapters/platform/clock.js';
import {
  createErrorLog,
  debugRequested,
  installErrorCapture,
} from './adapters/platform/debug-info.js';
import { registerAllStrings } from './adapters/ui/i18n/index.js';
import { createApp } from './adapters/ui/app.js';
import { createMainMenuScreen, registerMenuEntry } from './adapters/ui/menu.js';
import { createSettingsScreen } from './adapters/ui/settings-screen.js';
import {
  installRotateNotice,
  renderErrorNotice,
  renderNo3dNotice,
  showSaveNotice,
} from './adapters/ui/notices.js';
import './application/settings-schema.js';
import { createSettingsService } from './application/settings-service.js';
import './adapters/ui/settings-sections.js';
import { createRideScreen } from './adapters/ui/screens/ride-screen.js';
import { firstScreen, registerProfile } from './adapters/ui/screens/profile/register.js';
import { registerCourses } from './adapters/ui/screens/courses/register.js';
import { registerAudio } from './adapters/ui/audio-wiring.js';

function boot() {
  // `?debug`: collect errors from the very start for the debug box of the ride
  let debugService = null;
  if (debugRequested()) {
    debugService = { errorLog: createErrorLog() };
    installErrorCapture(debugService.errorLog);
  }
  const root = document.getElementById('app');
  registerAllStrings();

  const store = createStore({ env: { defaultLang: detectLang(navigator.languages) } });
  setLang(store.get('settings').lang);
  document.documentElement.lang = getLang();

  // Rule 7: without 3D only a notice is shown instead of the whole app
  if (!hasWebGL()) {
    renderNo3dNotice(root);
    return;
  }

  store.flush();
  requestPersistentStorage();

  const inputMode = createInputMode({ device: detectDevice(window), target: window });
  document.documentElement.classList.toggle('touch-mode', inputMode.touch);
  inputMode.onChange((touch) => document.documentElement.classList.toggle('touch-mode', touch));

  // The only writer of the settings section: screens and the engine call this service
  const settings = createSettingsService(store);

  const app = createApp({ root, store, settings, inputMode, clock: systemClock });
  if (debugService) app.services.debug = debugService;
  app.register('menu', createMainMenuScreen);
  app.register('settings', createSettingsScreen);
  // The ride use case gets its random source here (the application layer never calls Math.random)
  app.register('ride', (ctx, params) => createRideScreen(ctx, params, { rng: Math.random }));
  registerProfile(app);
  registerCourses(app);
  registerAudio(app);
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

  // Read-only helpers for browser tests; only with `?testhooks` in the URL. Installed before the
  // first screen so that everything they configure is there when the screens start.
  if (testHooksRequested()) {
    installTestHooks({ app, store, inputMode });
    app.services.gpuBudgetOverrideMB = gpuBudgetOverride(); // forces a small budget in tests
  }
  app.go(firstScreen(store));
}

try {
  boot();
} catch (err) {
  // Unexpected start error: child-friendly notice instead of an empty page (purpose of rule 7)
  console.error(err);
  registerAllStrings(); // idempotent; the error may have happened before the texts were known
  renderErrorNotice(document.getElementById('app'));
}
