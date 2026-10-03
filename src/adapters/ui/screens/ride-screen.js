// Reit-Bildschirm: Spielschleife, Eingabe, Pause, HUD und Rückmeldungen (SRT-002 bis SRT-004).
import { onLangChange, t } from '../i18n.js';
import { h } from '../dom.js';
import { getEngine } from '../../view3d/engine.js';
import { createInput } from '../../input/input.js';
import { createRidingSim } from '../../../domain/sim/riding-sim.js';
import { createFreeMode } from '../../../application/modes/free-mode.js';
import { addJump } from '../../../domain/progress/progress.js';
import { checkInstantBadges } from '../../../domain/progress/badges.js';
import { showBadgeToast } from './profile/badge-toast.js';

const MODES = { free: createFreeMode };
const HUDS = {};

/** Further modes (e.g. course, SRT-004) register here. factory(ctx, params) → mode. */
export function registerRideMode(id, factory) {
  MODES[id] = factory;
}

/** A mode with a HUD registers a view per mode id: factory() → { el, renderTexts(), render(model) }. */
export function registerRideHud(id, factory) {
  HUDS[id] = factory;
}

export function createRideScreen(ctx, params = {}) {
  const { app, store, inputMode, services, clock } = ctx;
  const engine = getEngine(ctx);
  const { world, horse, cameraRig, governor } = engine;
  // Modes get only the ports they need (store, clock); no DOM, no app
  const mode = MODES[params.mode ?? 'free']({ store, clock }, params);
  const hudView = mode.hudModel ? HUDS[mode.id]?.() : null;

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
  const renderHud = () => hudView?.render(mode.hudModel());

  const renderTexts = () => {
    pauseTitle.textContent = t('pause.title');
    resumeBtn.textContent = t('pause.resume');
    restartBtn.textContent = t('pause.restart');
    quitBtn.textContent = t(mode.quitLabelKey);
    settingsBtn.textContent = t('pause.settings');
    hint.textContent = t('ride.pauseHint');
    hudView?.renderTexts();
    renderHud();
  };
  renderTexts();
  const offLang = onLangChange(renderTexts);

  // --- Spielzustand ---
  const input = createInput({ container: controls, inputMode });
  const updateHint = () => (hint.hidden = inputMode.touch);
  updateHint();
  const offMode = inputMode.onChange(updateHint);

  const sim = createRidingSim({ obstacles: mode.obstacles, rules: mode.rules, rng: Math.random });
  world.setObstacles(mode.obstacles, { flags: mode.flags });
  // Mode lines carry label keys; translate them here (language may have changed since last restart)
  const applyLines = () => {
    const lines = mode.lines;
    world.setLines?.(
      lines
        ? {
            ...lines,
            labels: { start: t(lines.labelKeys.start), finish: t(lines.labelKeys.finish) },
          }
        : null,
    );
  };
  applyLines();
  world.highlight(null);
  world.setAid(null);
  world.setFinishMarked?.(false);

  let paused = false;
  let rebuilds = [];
  let feedbackTimer = 0;

  const api = {
    sim,
    world,
    rebuildIn(elementId, seconds) {
      rebuilds = rebuilds.filter((r) => r.elementId !== elementId);
      rebuilds.push({ elementId, left: seconds });
    },
    rebuildNow(elementId) {
      rebuilds = rebuilds.filter((r) => r.elementId !== elementId);
      sim.rebuild(elementId);
    },
    feedback(key, params) {
      feedbackEl.textContent = t(key, params);
      feedbackEl.classList.add('is-visible');
      feedbackTimer = 2;
    },
    /** End of ride: reset the touch gallop, finish signal, then open the next screen. */
    finish({ screen, params: screenParams }) {
      input.resetTouchGallop();
      services.audio?.sfx.finishSignal();
      app.go(screen, screenParams);
    },
    pause: () => setPaused(true),
    restart: () => restart(),
  };

  function placeHorse() {
    const s = sim.horse;
    horse.object.position.set(s.x, s.y ?? 0, s.z);
    horse.object.rotation.y = s.heading;
  }

  function restart() {
    rebuilds = [];
    sim.reset(mode.startPose());
    sim.rebuildAll();
    input.resetTouchGallop();
    input.clearEdges();
    mode.onRestart?.(api);
    if (mode.lines) applyLines();
    renderHud();
    horse.setAppearance?.(store.get('horse'));
    placeHorse();
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
  quitBtn.addEventListener('click', () => app.go(mode.quitScreen));
  settingsBtn.addEventListener('click', () => app.push('settings', { fromPause: true }));

  // Auto-Pause bei Fokusverlust und Hochformat (Regeln 12, 38)
  const onHidden = () => document.hidden && setPaused(true);
  const onBlur = () => setPaused(true);
  document.addEventListener('visibilitychange', onHidden);
  window.addEventListener('blur', onBlur);
  const offRotate = app.on('rotateBlocked', (blocked) => blocked && setPaused(true));

  const offFootfall = (() => {
    horse.onFootfall = (gait) => !paused && services.audio?.sfx.hoof(gait);
    return () => (horse.onFootfall = null);
  })();

  function handleEvents(events) {
    for (const e of events) {
      if (e.type === 'gallopEnded') input.endGallop();
      if (e.type === 'landed') {
        // Sprungzähler: jeder Sprung über ein Hindernis, sofort gespeichert (Regeln 40, 45)
        // Sofort-Auszeichnungen (Regel 49) werden kurz eingeblendet
        let awarded = [];
        store.update('progress', (p) => {
          const result = checkInstantBadges(addJump(p), clock.nowIso());
          awarded = result.awarded;
          return result.progress;
        });
        for (const id of awarded) showBadgeToast(app.layers.overlay, id);
        services.audio?.sfx.landing();
      }
      if (e.type === 'takeoff') services.audio?.sfx.takeoff();
      if (e.type === 'railDown') services.audio?.sfx.railDown();
    }
    mode.onEvents(events, api);
  }

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
    const prev = { x: sim.horse.x, z: sim.horse.z };
    const events = sim.step(dt, inp);
    handleEvents(events);
    mode.update(dt, api, prev);
    renderHud();

    for (const r of rebuilds) r.left -= dt;
    for (const r of rebuilds.filter((x) => x.left <= 0)) sim.rebuild(r.elementId);
    rebuilds = rebuilds.filter((r) => r.left > 0);

    if (feedbackTimer > 0) {
      feedbackTimer -= dt;
      if (feedbackTimer <= 0) feedbackEl.classList.remove('is-visible');
    }

    horse.update(dt, sim.horse);
    placeHorse();
    world.syncRails(sim.rails, dt);
    world.setShadowFocus?.(sim.horse.x, sim.horse.z);
    const aim = mode.aidTarget(api);
    world.setAid(
      aim
        ? { ...aim, zone: sim.zoneFor(aim.elementId, aim.dir, Math.max(sim.horse.speed, 1)) }
        : null,
    );
    cameraRig.update(dt, sim.horse, horse.earAnchor);
    world.update?.(dt, engine.camera);
    governor.frame(rawDt, !document.hidden);
  }

  cameraRig.setMode(store.get('settings').camera);
  restart();
  engine.run(frame);

  return {
    el,
    music: false,
    rerenderOnLang: false,
    screenClass: 'screen-ride',
    onShow() {
      // Zurück aus den Einstellungen: weiterhin pausiert
      if (paused) resumeBtn.focus({ preventScroll: true });
    },
    destroy() {
      engine.run(null);
      offLang();
      offMode();
      offRotate();
      offFootfall();
      document.removeEventListener('visibilitychange', onHidden);
      window.removeEventListener('blur', onBlur);
      input.dispose();
      mode.dispose?.();
      services.audio?.setPaused(false);
    },
    get paused() {
      return paused;
    },
  };
}
