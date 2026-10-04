// Ride screen (thin adapter): builds the DOM, reads the input, pauses automatically, and runs the
// frame loop. All ride rules live in application/ride-session.js: this screen calls
// `session.step`, shows `session.view` in the 3D world and executes the returned commands.
import { onLangChange, t } from '../i18n.js';
import { h } from '../dom.js';
import { getEngine } from '../../view3d/engine.js';
import { createLowFpsHint } from '../../view3d/quality.js';
import { createFpsMeter, formatFpsText } from '../fps-display.js';
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
  const restartBtn = btn('restart', 'btn-secondary');
  const quitBtn = btn('quit', 'btn-secondary');
  const settingsBtn = btn('settings', 'btn-secondary');
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
      h('div', { class: 'menu-list' }, resumeBtn, restartBtn, quitBtn, settingsBtn),
    ),
  );
  const el = h('section', { class: 'ride-screen' }, hud, feedbackEl, hint, controls, pauseMenu);
  hud.append(fpsEl);
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
    if (s.showFps !== showFps) fpsMeter.reset();
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

  const renderTexts = () => {
    pauseTitle.textContent = t('pause.title');
    resumeBtn.textContent = t('pause.resume');
    restartBtn.textContent = t('pause.restart');
    quitBtn.textContent = t(session.quitLabelKey);
    settingsBtn.textContent = t('pause.settings');
    hint.textContent = t('ride.pauseHint');
    lostNote.textContent = t('pause.graphicsLost');
    renderFps();
    hudView?.renderTexts();
    renderHud();
    applyLines(session.view.lines); // the line labels are translated texts too
  };
  const offLang = onLangChange(renderTexts);

  let paused = false;

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

  // Manual level that is too high for the device: one hint per ride, the level stays (rule 4)
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
    lowFpsHint.interrupt();
  }

  // WebGL context lost (rule 4): the ride pauses and cannot go on before the picture is back
  let contextLost = false;
  function onContextLost() {
    contextLost = true;
    lostNote.hidden = false;
    resumeBtn.disabled = true;
    setPaused(true);
  }
  function onContextRestored() {
    contextLost = false;
    lostNote.hidden = true;
    resumeBtn.disabled = false;
    governor.interrupt();
    lowFpsHint.interrupt();
    if (paused && app.current === 'ride') resumeBtn.focus({ preventScroll: true });
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
    if (paused) resumeBtn.focus({ preventScroll: true });
    // a focused menu button must not keep Space/Enter once the ride goes on
    else if (el.contains(document.activeElement)) document.activeElement.blur();
  }

  resumeBtn.addEventListener('click', () => setPaused(false));
  restartBtn.addEventListener('click', () => {
    restart();
    setPaused(false);
  });
  quitBtn.addEventListener('click', () => app.go(session.quitScreen));
  settingsBtn.addEventListener('click', () => app.push('settings', { fromPause: true }));

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
    if (showFps) {
      // a long frame is a suspended tab, not a slow game
      const value = rawDt > 1 ? (fpsMeter.reset(), null) : fpsMeter.frame(rawDt);
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
      feedbackTimer -= dt;
      if (feedbackTimer <= 0) feedbackEl.classList.remove('is-visible');
    }

    const view = session.view;
    renderHud(view.hud);
    horse.update(dt, view.horse);
    placeHorse(view.horse);
    world.syncRails(view.rails, dt, view.fallDirs);
    world.setShadowFocus(view.horse.x, view.horse.z);
    world.highlight(view.highlight?.elementId ?? null, view.highlight?.number);
    world.setFinishMarked(view.finishMarked);
    world.setAid(view.aid);
    cameraRig.update(dt, view.horse, horse.earAnchor);
    world.update(dt, engine.camera);
    governor.frame(rawDt, !document.hidden);
    if (lowFpsHint.frame(rawDt, !document.hidden && !autoGraphics)) {
      showFeedback('ride.graphicsTooHigh', { long: true });
    }
  }

  cameraRig.setMode(settings.get().camera);
  restart();
  // The context may have been lost while no ride was running: start paused then
  if (engine.contextLost) onContextLost();
  engine.run(frame);

  const instance = {
    el,
    music: false,
    rerenderOnLang: false,
    screenClass: 'screen-ride',
    onShow() {
      // Back from the settings: still paused
      if (paused) resumeBtn.focus({ preventScroll: true });
    },
    destroy() {
      engine.run(null);
      offLang();
      offMode();
      offRotate();
      offSettings();
      offContextLost();
      offContextRestored();
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
