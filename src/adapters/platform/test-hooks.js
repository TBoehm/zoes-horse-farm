// Test hook for browser tests (smoke tests, manual play-tests): `window.__zhfTest`.
// Only installed when the URL contains `?testhooks`; the game itself never uses it. The helpers
// return plain copies of the state; `go` is the only action (jumps to a screen, e.g. to look at
// the results screen without riding a whole course).

const round = (n, digits = 3) => (Number.isFinite(n) ? Number(n.toFixed(digits)) : n);

export function testHooksRequested(search = globalThis.location?.search ?? '') {
  return new URLSearchParams(search).has('testhooks');
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

/** Installs `window.__zhfTest` (read-only helpers) for browser tests. */
export function installTestHooks({ app, store, inputMode, target = window }) {
  target.__zhfTest = {
    screen: () => app.current,
    stack: () => app.stack,
    ride: () => snapshotRide(app.services.ride, app),
    store: (section) => store.get(section),
    audio: () => app.services.audio?.getState?.() ?? null,
    touchMode: () => inputMode.touch,
    go: (name, params) => app.go(name, params),
  };
}
