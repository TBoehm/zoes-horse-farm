// Ride screen (thin adapter): builds the DOM, reads the input, pauses automatically, and runs the
// frame loop. All ride rules live in application/ride-session.js: this screen calls
// `session.step`, shows `session.view` in the 3D world and executes the returned commands.
import { onLangChange, t } from '../i18n.js';
import { h } from '../dom.js';
import { getEngine } from '../../view3d/engine.js';
import { createInput } from '../../input/input.js';
import { createRideMode } from '../../../application/modes/index.js';
import { createRideSession } from '../../../application/ride-session.js';
import { showBadgeToast } from './profile/badge-toast.js';

const HUDS = {};
const FEEDBACK_VISIBLE_S = 2;

/** A mode with a HUD registers a view per mode id: factory() → { el, renderTexts(), render(model) }. */
export function registerRideHud(id, factory) {
  HUDS[id] = factory;
}

/**
 * @param {object} ctx app context
 * @param {{ mode?: 'free'|'course', courseId?: number }} params
 * @param {{ rng: () => number }} deps random source, injected by the composition root
 */
export function createRideScreen(ctx, params = {}, { rng }) {
  const { app, store, inputMode, services, clock } = ctx;
  const engine = getEngine(ctx);
  const { world, horse, cameraRig, governor } = engine;
  const session = createRideSession({ mode: createRideMode(params), store, clock, rng });
  const hudView = session.view.hud ? HUDS[session.modeId]?.() : null;

  // --- DOM ---
  const hud = h('div', { class: 'ride-hud' });
  const feedbackEl = h('div', { class: 'ride-feedback', role: 'status', 'aria-live': 'polite' });
  const hint = h('div', { class: 'ride-hint' });
  const controls = h('div', { class: 'ride-controls' });
  const pauseTitle = h('h2', {});
  const btn = (action, cls = '') =>
    h('button', { class: `btn ${cls}`, type: 'button', dataset: { action } });
  const resumeBtn = btn('resume', 'btn-menu');
  const restartBtn = btn('restart', 'btn-secondary');
  const quitBtn = btn('quit', 'btn-secondary');
  const settingsBtn = btn('settings', 'btn-secondary');
  const pauseMenu = h(
    'div',
    { class: 'pause-overlay', hidden: true, dataset: { overlay: 'pause' } },
    h(
      'section',
      { class: 'panel panel-pause' },
      pauseTitle,
      h('div', { class: 'menu-list' }, resumeBtn, restartBtn, quitBtn, settingsBtn),
    ),
  );
  const el = h('section', { class: 'ride-screen' }, hud, feedbackEl, hint, controls, pauseMenu);
  if (hudView) hud.append(hudView.el);
  const renderHud = (model = session.view.hud) => hudView?.render(model);

  const renderTexts = () => {
    pauseTitle.textContent = t('pause.title');
    resumeBtn.textContent = t('pause.resume');
    restartBtn.textContent = t('pause.restart');
    quitBtn.textContent = t(session.quitLabelKey);
    settingsBtn.textContent = t('pause.settings');
    hint.textContent = t('ride.pauseHint');
    hudView?.renderTexts();
    renderHud();
  };
  renderTexts();
  const offLang = onLangChange(renderTexts);

  // --- Input and world ---
  const input = createInput({ container: controls, inputMode });
  const updateHint = () => (hint.hidden = inputMode.touch);
  updateHint();
  const offMode = inputMode.onChange(updateHint);

  world.setObstacles(session.obstacles, { flags: session.flags });
  // Session lines carry label keys; translate them here (the language may have changed)
  const applyLines = (lines) =>
    world.setLines?.(
      lines
        ? {
            ...lines,
            labels: { start: t(lines.labelKeys.start), finish: t(lines.labelKeys.finish) },
          }
        : null,
    );
  applyLines(session.view.lines);
  world.highlight(null);
  world.setAid(null);
  world.setFinishMarked?.(false);

  let paused = false;
  let feedbackTimer = 0;

  function showFeedback(key) {
    feedbackEl.textContent = t(key);
    feedbackEl.classList.add('is-visible');
    feedbackTimer = FEEDBACK_VISIBLE_S;
  }

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
        services.audio?.sfx.finishSignal();
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
    horse.setAppearance?.(store.get('horse'));
    placeHorse(view.horse);
    cameraRig.snap();
    governor.interrupt();
  }

  function setPaused(next) {
    if (paused === next) return;
    paused = next;
    pauseMenu.hidden = !paused;
    el.classList.toggle('is-paused', paused);
    input.clearEdges();
    governor.interrupt();
    services.audio?.setPaused(paused);
    if (paused) resumeBtn.focus({ preventScroll: true });
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
  const offRotate = app.on('rotateBlocked', (blocked) => blocked && setPaused(true));

  horse.onFootfall = (gait) => !paused && services.audio?.sfx.hoof(gait);

  function frame(dt, rawDt) {
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
      store.update('settings', (s) => ({ ...s, camera: next }));
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
    world.syncRails(view.rails, dt);
    world.setShadowFocus?.(view.horse.x, view.horse.z);
    world.highlight(view.highlight?.elementId ?? null, view.highlight?.number);
    world.setFinishMarked?.(view.finishMarked);
    world.setAid(view.aid);
    cameraRig.update(dt, view.horse, horse.earAnchor);
    world.update?.(dt, engine.camera);
    governor.frame(rawDt, !document.hidden);
  }

  cameraRig.setMode(store.get('settings').camera);
  restart();
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
      horse.onFootfall = null;
      document.removeEventListener('visibilitychange', onHidden);
      window.removeEventListener('blur', onBlur);
      input.dispose();
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
