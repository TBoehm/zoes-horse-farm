// Ride screen (thin adapter): builds the DOM, reads the input, pauses automatically, and runs the
// frame loop. All ride rules live in application/ride-session.js: this screen calls
// `session.step`, shows `session.view` in the 3D world and executes the returned commands.
import { onLangChange, t } from '../i18n.js';
import { h } from '../dom.js';
import { getEngine } from '../../view3d/engine.js';
import { canHintLowerLevel, createLowFpsHint } from '../../view3d/quality.js';
import { createRestoreWatchdog } from '../../view3d/resilience.js';
import { createFpsMeter, formatFpsText } from '../fps-display.js';
import { createDebugBox } from '../debug-display.js';
import { createInput } from '../../input/input.js';
import { trapTab } from '../../input/focus-trap.js';
import { createRideMode } from '../../../application/modes/index.js';
import { createRideSession } from '../../../application/ride-session.js';
import { canStart } from '../../../application/course-catalog.js';
import { showBadgeToast } from './profile/badge-toast.js';

const HUDS = {};
const FEEDBACK_VISIBLE_S = 2;
const HINT_VISIBLE_S = 5; // the "graphics too high" hint is longer than a jump message

/**
 * A mode with a HUD registers a view per mode id:
 * factory() → { el, renderTexts(), render(model) }.
 */
export function registerRideHud(id, factory) {
  HUDS[id] = factory;
}

/**
 * @param {object} ctx app context
 * @param {{ mode?: 'free'|'course', courseId?: number }} params
 * @param {{ rng: () => number }} deps random source, injected by the composition root
 */
export function createRideScreen(ctx, params = {}, { rng }) {
  const { app, store, settings, inputMode, services, clock } = ctx;
  // A locked course cannot be ridden (the course card is only disabled): back to the selection
  if (params.mode === 'course' && !canStart(store, params.courseId)) {
    return { el: h('div'), redirect: { name: 'courseSelect' } };
  }
  const engine = getEngine(ctx);
  const { world, horse, cameraRig, governor } = engine;
  const session = createRideSession({ mode: createRideMode(params), store, clock, rng });
  const hudView = session.view.hud ? HUDS[session.modeId]?.() : null;

  // --- DOM ---
  const hud = h('div', { class: 'ride-hud' });
  const feedbackEl = h('div', { class: 'ride-feedback', role: 'status', 'aria-live': 'polite' });
  const hint = h('div', { class: 'ride-hint' });
  // Frame rate and graphics level (rule 4), top left above the course HUD; shown on demand
  const fpsEl = h('div', {
    class: 'ride-fps',
    hidden: true,
    dataset: { hud: 'fps' },
  });
  const controls = h('div', { class: 'ride-controls' });
  const pauseTitle = h('h2', { id: 'ride-pause-title' });
  const lostNote = h('p', {
    class: 'pause-note',
    hidden: true,
    role: 'status',
    dataset: { note: 'graphics-lost' },
  });
  const btn = (action, cls = '') =>
    h('button', { class: `btn ${cls}`, type: 'button', dataset: { action } });
  const resumeBtn = btn('resume', 'btn-menu');
  // only shown when the lost WebGL context does not come back (see the watchdog below)
  const reloadBtn = btn('reload', 'btn-menu');
  reloadBtn.hidden = true;
  const restartBtn = btn('restart', 'btn-secondary');
  const quitBtn = btn('quit', 'btn-secondary');
  const settingsBtn = btn('settings', 'btn-secondary');
  const helpBtn = btn('help', 'btn-secondary');
  const pauseMenu = h(
    'div',
    {
      class: 'pause-overlay',
      hidden: true,
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'ride-pause-title',
      dataset: { overlay: 'pause' },
    },
    h(
      'section',
      { class: 'panel panel-pause' },
      pauseTitle,
      lostNote,
      h(
        'div',
        { class: 'menu-list' },
        reloadBtn,
        resumeBtn,
        restartBtn,
        quitBtn,
        settingsBtn,
        helpBtn,
      ),
    ),
  );
  const el = h('section', { class: 'ride-screen' }, hud, feedbackEl, hint, controls, pauseMenu);
  hud.append(fpsEl);
  // Diagnostics for real-device tests: only with `?debug` (the composition root provides the
  // service then), under the fps line
  const debugBox = services.debug
    ? createDebugBox({
        diagnostics: engine.diagnostics,
        errorLog: services.debug.errorLog,
        t,
      })
    : null;
  if (debugBox) hud.append(debugBox.el);
  if (hudView) hud.append(hudView.el);
  const renderHud = (model = session.view.hud) => hudView?.render(model);

  // --- fps display (rule 4): setting, averaged value, level text ---
  const fpsMeter = createFpsMeter();
  let showFps = settings.get().showFps;
  let autoGraphics = settings.get().graphicsAuto;
  let fps = null;
  function renderFps() {
    fpsEl.hidden = !showFps;
    if (!showFps) return;
    fpsEl.textContent = formatFpsText({ fps, level: engine.level, auto: autoGraphics }, t);
  }
  // live: the level can change by the governor or in the settings, the toggle in the settings
  const offSettings = settings.onChange((s) => {
    if (s.showFps !== showFps) {
      // switched on or off: do not show the value of the last time
      fpsMeter.reset();
      fps = null;
    }
    showFps = s.showFps;
    autoGraphics = s.graphicsAuto;
    renderFps();
  });

  // Session lines carry label keys; translate them here (the language may have changed)
  function applyLines(lines) {
    world.setLines(
      lines
        ? {
            ...lines,
            labels: { start: t(lines.labelKeys.start), finish: t(lines.labelKeys.finish) },
          }
        : null,
    );
  }

  function renderLostNote() {
    lostNote.textContent = t(restoreOverdue ? 'pause.graphicsReload' : 'pause.graphicsLost');
  }

  const renderTexts = () => {
    pauseTitle.textContent = t('pause.title');
    resumeBtn.textContent = t('pause.resume');
    reloadBtn.textContent = t('pause.reload');
    restartBtn.textContent = t('pause.restart');
    quitBtn.textContent = t(session.quitLabelKey);
    settingsBtn.textContent = t('pause.settings');
    helpBtn.textContent = t('pause.help');
    hint.textContent = t('ride.pauseHint');
    renderLostNote();
    renderFps();
    debugBox?.renderTexts();
    hudView?.renderTexts();
    renderHud();
    applyLines(session.view.lines); // the line labels are translated texts too
  };
  const offLang = onLangChange(renderTexts);

  let paused = false;
  // WebGL context state (rule 4): see the loss handlers below
  let contextLost = false;
  let restoreOverdue = false; // the lost context did not come back in time: ask for a reload

  // --- Input and world ---
  // The keyboard only listens while this ride is the top screen and not paused
  const input = createInput({
    container: controls,
    inputMode,
    isActive: () => !paused && app.current === 'ride',
  });
  const updateHint = () => (hint.hidden = inputMode.touch);
  updateHint();
  const offMode = inputMode.onChange(updateHint);

  world.setObstacles(session.obstacles, { flags: session.flags });
  renderTexts();
  world.highlight(null);
  world.setAid(null);
  world.setFinishMarked(false);

  let feedbackTimer = 0;

  function showFeedback(key, { long = false } = {}) {
    feedbackEl.textContent = t(key);
    feedbackEl.classList.toggle('is-hint', long);
    feedbackEl.classList.add('is-visible');
    feedbackTimer = long ? HINT_VISIBLE_S : FEEDBACK_VISIBLE_S;
  }

  /** Focus for the pause menu: the first button that can be used (Continue is off while lost). */
  function focusPauseMenu() {
    const first = [...pauseMenu.querySelectorAll('button')].find((b) => !b.disabled && !b.hidden);
    first?.focus({ preventScroll: true });
  }

  // Manual level that is too high for the device: one hint per ride, the level stays (rule 4).
  // Not at the lowest level: there is nothing lower to pick (canHintLowerLevel).
  const lowFpsHint = createLowFpsHint();

  /** Executes the commands of the session; returns true if the screen is being left. */
  function execute(commands) {
    for (const cmd of commands) {
      if (cmd.type === 'endGallop') input.endGallop();
      else if (cmd.type === 'resetTouchGallop') input.resetTouchGallop();
      else if (cmd.type === 'feedback') showFeedback(cmd.key);
      else if (cmd.type === 'badges')
        cmd.ids.forEach((id) => showBadgeToast(app.layers.overlay, id));
      else if (cmd.type === 'sound') services.audio?.sfx[cmd.name]?.();
      else if (cmd.type === 'finished') {
        input.resetTouchGallop();
        app.go(cmd.screen, cmd.params);
        return true;
      }
    }
    return false;
  }

  function placeHorse(state) {
    horse.object.position.set(state.x, state.y ?? 0, state.z);
    horse.object.rotation.y = state.heading;
  }

  function restart() {
    execute(session.restart().commands);
    input.clearEdges();
    const view = session.view;
    applyLines(view.lines);
    renderHud(view.hud);
    horse.setAppearance(store.get('horse'));
    placeHorse(view.horse);
    cameraRig.snap();
    governor.interrupt();
    lowFpsHint.reset(); // "Start again" is a new ride (concept rule 39)
  }

  // WebGL context lost (rule 4): the ride pauses and cannot go on before the picture is back. If
  // the browser never restores it (e.g. after repeated losses), the watchdog turns the note into a
  // reload request.
  const restoreWatchdog = createRestoreWatchdog({
    onTimeout() {
      restoreOverdue = true;
      renderLostNote();
      reloadBtn.hidden = false;
      if (paused && app.current === 'ride') focusPauseMenu();
    },
  });
  function onContextLost() {
    contextLost = true;
    restoreOverdue = false;
    renderLostNote();
    lostNote.hidden = false;
    resumeBtn.disabled = true;
    setPaused(true);
    restoreWatchdog.start();
  }
  function onContextRestored() {
    contextLost = false;
    restoreWatchdog.cancel();
    restoreOverdue = false;
    reloadBtn.hidden = true;
    lostNote.hidden = true;
    resumeBtn.disabled = false;
    governor.interrupt();
    lowFpsHint.interrupt();
    if (paused && app.current === 'ride') focusPauseMenu();
  }
  const offContextLost = engine.on('contextLost', onContextLost);
  const offContextRestored = engine.on('contextRestored', onContextRestored);

  function setPaused(next) {
    if (paused === next) return;
    if (!next && contextLost) return;
    paused = next;
    pauseMenu.hidden = !paused;
    el.classList.toggle('is-paused', paused);
    input.clearEdges();
    governor.interrupt();
    lowFpsHint.interrupt();
    services.audio?.setPaused(paused);
    if (paused) focusPauseMenu();
    // a focused menu button must not keep Space/Enter once the ride goes on
    else {
      if (el.contains(document.activeElement)) document.activeElement.blur();
      showContextLossHint();
    }
  }

  /**
   * After a lost graphics context with a manual level above low (rule 4): the toast tells the
   * player to pick a lower level. Shown once the ride goes on (the pause menu covers the screen
   * before), it is consumed from the engine so that it appears only once per loss.
   */
  function showContextLossHint() {
    if (engine.takeGraphicsHint()) showFeedback('ride.graphicsContextLost', { long: true });
  }

  resumeBtn.addEventListener('click', () => setPaused(false));
  restartBtn.addEventListener('click', () => {
    restart();
    setPaused(false);
  });
  quitBtn.addEventListener('click', () => app.go(session.quitScreen));
  settingsBtn.addEventListener('click', () => app.push('settings', { fromPause: true }));
  // Controls help on top of the paused ride: "Got it" pops back to the open pause menu (rule 56)
  helpBtn.addEventListener('click', () => app.push('controlsHelp', { fromPause: true }));
  reloadBtn.addEventListener('click', () => location.reload());

  // Auto-pause on lost focus and portrait orientation (rules 12, 38)
  const onHidden = () => document.hidden && setPaused(true);
  const onBlur = () => setPaused(true);
  document.addEventListener('visibilitychange', onHidden);
  window.addEventListener('blur', onBlur);
  // Pause menu: Esc continues, Tab stays inside the dialog (keyboard handler is inactive then)
  const onPauseKey = (e) => {
    if (!paused || app.current !== 'ride') return;
    if (e.code === 'Escape') {
      e.preventDefault();
      if (!e.repeat) setPaused(false);
    } else {
      trapTab(e, pauseMenu);
    }
  };
  window.addEventListener('keydown', onPauseKey);
  const offRotate = app.on('rotateBlocked', (blocked) => blocked && setPaused(true));

  horse.onFootfall = (gait) => !paused && services.audio?.sfx.hoof(gait);

  function frame(dt, rawDt) {
    debugBox?.frame(rawDt);
    if (showFps) {
      const value = fpsMeter.frame(rawDt); // restarts itself after a suspended tab
      if (value !== null) {
        fps = value;
        renderFps();
      }
    }
    if (paused || app.current !== 'ride') {
      governor.frame(rawDt, false);
      return;
    }
    const inp = input.poll();
    if (inp.pause) {
      setPaused(true);
      return;
    }
    if (inp.camera) {
      const next = cameraRig.toggle();
      settings.setCamera(next);
    }
    if (execute(session.step(dt, inp).commands)) return;

    if (feedbackTimer > 0) {
      feedbackTimer -= rawDt; // real time: the toast must not stay longer at a low frame rate
      if (feedbackTimer <= 0) feedbackEl.classList.remove('is-visible');
    }

    const view = session.view;
    renderHud(view.hud);
    horse.update(dt, view.horse);
    placeHorse(view.horse);
    engine.emitHoofDust(); // after placeHorse: the footfalls are placed with the object
    world.syncRails(view.rails, dt, view.fallDirs);
    world.setShadowFocus(view.horse.x, view.horse.z);
    world.highlight(view.highlight?.elementId ?? null, view.highlight?.number);
    world.setFinishMarked(view.finishMarked);
    world.setAid(view.aid);
    cameraRig.update(dt, view.horse, horse.earAnchor);
    world.update(dt, engine.camera);
    governor.frame(rawDt, !document.hidden);
    if (
      lowFpsHint.frame(
        rawDt,
        !document.hidden && canHintLowerLevel({ auto: autoGraphics, level: engine.level }),
      )
    ) {
      showFeedback('ride.graphicsTooHigh', { long: true });
    }
  }

  cameraRig.setMode(settings.get().camera);
  restart();
  // The context may have been lost while no ride was running: start paused then
  if (engine.contextLost) onContextLost();
  else showContextLossHint(); // a loss between two rides may have left a hint
  engine.run(frame);

  const instance = {
    el,
    music: false,
    rerenderOnLang: false,
    screenClass: 'screen-ride',
    onShow() {
      // Back from the settings: still paused
      if (paused) focusPauseMenu();
    },
    destroy() {
      engine.run(null);
      offLang();
      offMode();
      offRotate();
      offSettings();
      offContextLost();
      offContextRestored();
      restoreWatchdog.cancel();
      horse.onFootfall = null;
      document.removeEventListener('visibilitychange', onHidden);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('keydown', onPauseKey);
      input.dispose();
      session.dispose();
      services.audio?.setPaused(false);
      if (services.ride?.session === session) delete services.ride;
    },
    get paused() {
      return paused;
    },
  };
  // Lets the test hook (adapters/platform/test-hooks.js) look at the running ride
  services.ride = { session, engine, screen: instance };
  return instance;
}
