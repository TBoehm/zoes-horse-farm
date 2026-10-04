// Shared checks for smoke tests: console errors and forbidden files (rule 2).
const ALLOWED_FILES = [/\/icon\.svg$/, /\/generated\/[\w-]+\.png$/];
const FORBIDDEN_EXT =
  /\.(png|jpe?g|gif|webp|avif|bmp|ico|svg|mp3|wav|ogg|m4a|aac|flac|webm|mp4|glb|gltf|obj|fbx|ktx2?|basis|hdr|exr|woff2?|ttf|otf|eot)(\?|$)/i;

export function watchPage(page) {
  const errors = [];
  const forbidden = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' || /Missing text/.test(msg.text())) errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(String(err)));
  page.on('request', (req) => {
    const url = req.url();
    const type = req.resourceType();
    if (ALLOWED_FILES.some((re) => re.test(new URL(url).pathname))) return;
    if (['image', 'media', 'font'].includes(type) || FORBIDDEN_EXT.test(url)) {
      forbidden.push(`${type} ${url}`);
    }
  });
  return { errors, forbidden };
}

// Browsers in which WebGL MUST be available in the test (otherwise the test fails instead of
// skipping)
const REQUIRE_WEBGL = (process.env.SMOKE_REQUIRE_WEBGL ?? 'chromium,msedge').split(',');

/** true = WebGL available; false = not available and allowed for this browser (test is skipped). */
export async function webglOrSkip(page, test, browserName) {
  const ok = await hasWebGL(page);
  if (!ok && REQUIRE_WEBGL.includes(browserName)) {
    throw new Error(`WebGL is missing in ${browserName}, but required there`);
  }
  test.skip(!ok, 'no WebGL in the test browser');
  return ok;
}

export async function hasWebGL(page) {
  return page.evaluate(() => {
    const c = document.createElement('canvas');
    return Boolean(c.getContext('webgl2') || c.getContext('webgl'));
  });
}

/** Opens the app and skips the name prompt on first start if it appears. */
export async function openMenu(page) {
  await page.goto('./');
  const skip = page.locator('[data-action="skip"]');
  const menu = page.locator('[data-screen="menu"]');
  await skip.or(menu).first().waitFor();
  if (await skip.isVisible()) await skip.click();
  await menu.waitFor();
}

// ---------------------------------------------------------------------------------------------
// Helpers for the game flows. They use the test hook `window.__zhfTest` (only active with
// `?testhooks`, see src/adapters/platform/test-hooks.js).

export const SAVE_KEY = 'zoes-horse-farm.save';

/**
 * Opens the app with the test hook; an optional save game is written once before the first load.
 * `query`: more URL parameters, e.g. '&debug'.
 */
export async function openGame(page, { save, lang, query = '' } = {}) {
  if (save || lang) {
    await page.addInitScript(
      ({ key, data, lang: l }) => {
        if (localStorage.getItem(key)) return;
        const base = { version: 1, settings: l ? { lang: l } : {}, ...data };
        if (l) base.settings = { lang: l, ...base.settings };
        localStorage.setItem(key, JSON.stringify(base));
      },
      { key: SAVE_KEY, data: save ?? {}, lang },
    );
  }
  await page.goto(`./?testhooks${query}`);
}

/** Save game of a player who already named the horse (no name question). */
export const NAMED = { horse: { nameAnswered: true, name: 'Blitz' } };

/**
 * Opens the app with the hook and the name question already answered. Skips the test when the
 * browser has no WebGL (see webglOrSkip), then waits for the main menu.
 */
export async function openGameMenu(page, test, browserName, options = {}) {
  await openGame(page, { save: NAMED, ...options });
  await webglOrSkip(page, test, browserName);
  await page.locator('[data-screen="menu"]').waitFor();
}

export const screenName = (page) => page.evaluate(() => window.__zhfTest.screen());
export const rideState = (page) => page.evaluate(() => window.__zhfTest.ride());
export const storeSection = (page, name) => page.evaluate((n) => window.__zhfTest.store(n), name);
export const audioState = (page) => page.evaluate(() => window.__zhfTest.audio());
/** How often each effect was really played (dropped ones are not counted). */
export const sfxCounts = async (page) => (await audioState(page)).sfxCounts;

/** Emulates a tab that goes to the background (or comes back): `hidden` + `visibilitychange`. */
export const setTabHidden = (page, hidden) =>
  page.evaluate((value) => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => value });
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);

/** Waits until a ride is running (optionally in a given mode) and returns its state. */
export async function waitForRide(page, mode) {
  await page.waitForFunction((m) => {
    const ride = window.__zhfTest?.ride();
    return Boolean(ride) && (!m || ride.mode === m);
  }, mode);
  return rideState(page);
}

/** Starts free riding from the main menu. */
export async function startFreeRide(page) {
  await page.locator('[data-entry="free"]').click();
  return waitForRide(page, 'free');
}

/** PNG size of the 3D canvas alone (a blank or flat canvas gives a tiny file). */
export async function canvasScreenshotSize(page) {
  await page.addStyleTag({
    content: '.layer-ui, .layer-overlay { visibility: hidden !important; }',
  });
  const buffer = await page.locator('canvas.scene-canvas').screenshot();
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('style')) {
      if (el.textContent.includes('.layer-overlay { visibility: hidden !important; }')) el.remove();
    }
  });
  return buffer.length;
}

/** Touch input through the Chrome DevTools Protocol (real touch events, like a finger). */
export async function createFinger(page) {
  const client = await page.context().newCDPSession(page);
  const send = (type, points) =>
    client.send('Input.dispatchTouchEvent', { type, touchPoints: points });
  return {
    down: (x, y) => send('touchStart', [{ x, y, id: 1 }]),
    move: (x, y) => send('touchMove', [{ x, y, id: 1 }]),
    up: () => send('touchEnd', []),
  };
}

// ---- a small rider bot (keyboard) -------------------------------------------------------------

const wrapAngle = (a) => {
  let x = a;
  while (x > Math.PI) x -= 2 * Math.PI;
  while (x < -Math.PI) x += 2 * Math.PI;
  return x;
};

/** Holds or releases keys; remembers what is down so that repeated calls cost nothing. */
export function createKeys(page) {
  const down = new Set();
  return {
    async set(key, on) {
      if (down.has(key) === on) return;
      if (on) down.add(key);
      else down.delete(key);
      await page.keyboard[on ? 'down' : 'up'](key);
    },
    async releaseAll() {
      for (const key of [...down]) await this.set(key, false);
    },
  };
}

/**
 * Rides a free-mode line toward the cross `f1` (13, −12), jumping in the canter, and returns once
 * the jump is over. The horse starts at the free-mode start pose.
 */
export async function jumpOverCross(page, { timeoutMs = 90_000 } = {}) {
  const keys = createKeys(page);
  const t0 = Date.now();
  const check = (what) => {
    if (Date.now() - t0 > timeoutMs) throw new Error(`bot: timeout (${what})`);
  };
  // Steers toward a point with A/D and returns the current ride state
  const steer = async (tx, tz) => {
    const ride = await rideState(page);
    const err = wrapAngle(Math.atan2(tx - ride.horse.x, tz - ride.horse.z) - ride.horse.heading);
    await keys.set('d', err < -0.04);
    await keys.set('a', err > 0.04);
    return ride;
  };
  // 1. trot to the line in front of the cross
  await keys.set('w', true);
  for (;;) {
    const { horse } = await steer(13, -27);
    if (Math.hypot(13 - horse.x, -27 - horse.z) < 2.5) break;
    check('line');
  }
  // 2. stop (the speed stays when W is released), 3. turn toward the cross on the spot
  await keys.releaseAll();
  await keys.set('s', true);
  await page.waitForFunction(() => window.__zhfTest.ride().horse.speed < 0.2, null, {
    polling: 100,
  });
  await keys.set('s', false);
  for (;;) {
    const { horse } = await rideState(page);
    const err = wrapAngle(Math.atan2(13 - horse.x, -12 - horse.z) - horse.heading);
    if (Math.abs(err) < 0.04) break;
    await keys.set('d', err < 0);
    await keys.set('a', err > 0);
    check('turn');
  }
  await keys.releaseAll();
  // 4. canter straight at the cross; at the last take-off point the horse jumps by itself
  await keys.set('w', true);
  await keys.set('Shift', true);
  let sawAid = false;
  let jumped = false;
  for (;;) {
    const ride = await steer(13, 5);
    sawAid ||= Boolean(ride.aid);
    if (ride.horse.jump) jumped = true;
    if (jumped && !ride.horse.jump) break;
    check('jump');
  }
  await keys.releaseAll();
  return { sawAid };
}

/** Waypoints of the ride line of course 1 (start line at x = −1, finish line at z = −25). */
const COURSE_1_LINE = [
  [4, -28],
  [10, -22],
  [10, -2],
  [10, 8],
  [10, 17],
  [9, 24],
  [3, 29],
  [-5, 27],
  [-10, 20],
  [-10, 6],
  [-10, -6],
  [-10, -18],
  [-10, -26],
  [-10, -34],
];

/**
 * Rides course 1 from the pre-start to the finish in the canter (needs the jump aid in the courses:
 * Space is pressed in the middle of the take-off zone). Returns once the results screen is shown,
 * or earlier when `stopWhen(ride)` (a function of the ride state) returns true.
 */
export async function rideCourseOne(page, { timeoutMs = 240_000, stopWhen } = {}) {
  const keys = createKeys(page);
  const t0 = Date.now();
  const pressed = new Set();
  let waypoint = 0;
  await keys.set('Shift', true);
  while ((await screenName(page)) === 'ride') {
    if (Date.now() - t0 > timeoutMs) throw new Error('bot: course 1 not finished in time');
    const ride = await rideState(page);
    if (!ride) break;
    if (stopWhen?.(ride)) break;
    const { horse, aid } = ride;
    // canter at medium speed
    await keys.set('w', horse.speed < 5.0);
    await keys.set('s', horse.speed > 6.0);
    // Space in the middle of the take-off zone
    if (aid?.zone && !horse.jump) {
      const el = ride.obstacles.flatMap((o) => o.elements).find((e) => e.id === aid.elementId);
      const nx = Math.sin(el.rot) * aid.dir;
      const nz = Math.cos(el.rot) * aid.dir;
      const distance = (el.x - horse.x) * nx + (el.z - horse.z) * nz - el.spread / 2;
      const middle = (aid.zone.far + aid.zone.near) / 2;
      const once = `${el.id}:${aid.dir}:${Math.round(horse.z / 5)}`;
      if (distance > 0 && distance <= middle + horse.speed * 0.12 && !pressed.has(once)) {
        pressed.add(once);
        await page.keyboard.press('Space');
      }
    }
    const [tx, tz] = COURSE_1_LINE[Math.min(waypoint, COURSE_1_LINE.length - 1)];
    if (Math.hypot(tx - horse.x, tz - horse.z) < 3 && waypoint < COURSE_1_LINE.length - 1) {
      waypoint++;
    }
    const err = wrapAngle(Math.atan2(tx - horse.x, tz - horse.z) - horse.heading);
    await keys.set('d', err < -0.05);
    await keys.set('a', err > 0.05);
  }
  await keys.releaseAll();
}
