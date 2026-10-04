// Test hook for browser tests (smoke tests, manual play-tests): `window.__zhfTest`.
// Only installed when the URL contains `?testhooks`; the game itself never uses it. The helpers
// return plain copies of the state; `go` (jumps to a screen, e.g. to look at the results screen
// without riding a whole course), `setAutoLevel` (a governor-style level change) and
// `loseContext` / `restoreContext` (simulated WebGL context loss) and `setFrameFeed` (replaces the
// frame times the graphics automatic measures) are the only actions.

const round = (n, digits = 3) => (Number.isFinite(n) ? Number(n.toFixed(digits)) : n);

export function testHooksRequested(search = globalThis.location?.search ?? '') {
  return new URLSearchParams(search).has('testhooks');
}

/**
 * `?testhooks&gpubudget=MB` replaces the GPU memory budget of the engine, so that browser tests can
 * force the "level does not fit" case. null without the test hooks or without a valid number.
 */
export function gpuBudgetOverride(search = globalThis.location?.search ?? '') {
  if (!testHooksRequested(search)) return null;
  const value = Number(new URLSearchParams(search).get('gpubudget'));
  return Number.isFinite(value) && value > 0 ? value : null;
}

/**
 * A feed for the frame times of the graphics automatic: every real frame counts as `repeat` frames
 * of `dt` seconds, so that a browser test does not have to wait through the warm-up and windows of
 * the governors (3 s, 10 s, 20 s) at the slow software renderer. The engine counts what it
 * measured in `fedSeconds`. null for an invalid feed.
 */
function createFrameFeed({ dt, repeat = 1 } = {}) {
  if (!(Number.isFinite(dt) && dt > 0) || !Number.isInteger(repeat) || repeat < 1) return null;
  return { dt, repeat, fedSeconds: 0 };
}

function snapshotObstacles(obstacles) {
  return obstacles.map((o) => ({
    number: o.number,
    directed: o.directed,
    elements: o.elements.map((e) => ({
      id: e.id,
      kind: e.kind,
      height: e.height,
      spread: e.spread,
      x: e.x,
      z: e.z,
      rot: e.rot,
    })),
  }));
}

function snapshotRide(ride, app) {
  if (!ride) return null;
  const view = ride.session.view;
  const horse = view.horse;
  return {
    mode: ride.session.modeId,
    paused: ride.screen.paused,
    cameraMode: ride.engine.cameraRig.mode,
    graphicsLevel: ride.engine.level,
    contextLost: ride.engine.contextLost,
    // a level change is still being applied in stages / shaders are compiling
    graphicsSettling: ride.engine.settling,
    graphicsPixelRatio: ride.engine.renderer?.getPixelRatio?.(),
    horse: {
      x: round(horse.x),
      z: round(horse.z),
      y: round(horse.y ?? 0),
      heading: round(horse.heading),
      speed: round(horse.speed),
      gait: horse.gait,
      gallop: horse.gallop,
      jump: horse.jump ? { ...horse.jump } : null,
      hop: horse.hop ? { ...horse.hop } : null,
      refusal: horse.refusal ? { ...horse.refusal } : null,
    },
    rails: Object.fromEntries([...view.rails].map(([id, rails]) => [id, [...rails]])),
    aid: view.aid ? { ...view.aid } : null,
    highlight: view.highlight ? { ...view.highlight } : null,
    finishMarked: view.finishMarked,
    lines: view.lines ? JSON.parse(JSON.stringify(view.lines)) : null,
    hud: view.hud ? JSON.parse(JSON.stringify(view.hud)) : null,
    obstacles: snapshotObstacles(ride.session.obstacles),
    horsePosition: ride.engine.horse.object.position.toArray().map((n) => round(n)),
    app: app.stack,
  };
}

/**
 * Returns a function that calls loseContext / restoreContext of the engine's WebGL context
 * (false if that is not possible). A lost context hands out no extensions any more, so the
 * extension is kept from the first call.
 */
function createContextLossControl(app) {
  let extension = null;
  return (method) => {
    extension ??=
      app.services.engine?.renderer.getContext().getExtension('WEBGL_lose_context') ?? null;
    if (!extension) return false;
    extension[method]();
    return true;
  };
}

/** Installs `window.__zhfTest` (read-only helpers) for browser tests. */
export function installTestHooks({ app, store, inputMode, target = window }) {
  const contextLoss = createContextLossControl(app);
  target.__zhfTest = {
    screen: () => app.current,
    stack: () => app.stack,
    ride: () => snapshotRide(app.services.ride, app),
    store: (section) => store.get(section),
    audio: () => app.services.audio?.getState?.() ?? null,
    touchMode: () => inputMode.touch,
    go: (name, params) => app.go(name, params),
    // The way the frame-rate governor changes the level: lowers it with "Automatic" staying on
    setAutoLevel: (level) => app.ctx.settings.setAutoLevel(level),
    // Simulates a lost / restored WebGL context (WEBGL_lose_context) to test rule 4. Returns false
    // when there is no engine or the browser does not offer the extension.
    loseContext: () => contextLoss('loseContext'),
    restoreContext: () => contextLoss('restoreContext'),
    // Frame times for the graphics automatic (null: the real ones again), see createFrameFeed
    setFrameFeed: (feed) => {
      app.services.frameFeed = feed ? createFrameFeed(feed) : null;
    },
    frameFeed: () => (app.services.frameFeed ? { ...app.services.frameFeed } : null),
  };
}
