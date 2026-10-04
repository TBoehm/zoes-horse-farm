// SRT-014 in the browser: the graphics automatic starts at "low" and climbs while riding (concept
// rule 4). The real frame rate of the software renderer in the CI browser is far from 57 fps, and
// the governors need minutes of riding, so the tests feed the governors with frame times through
// the test hook `window.__zhfTest.setFrameFeed({ dt, repeat })`: every real frame then counts as
// `repeat` frames of `dt` seconds, and `frameFeed().fedSeconds` tells how much riding time the
// governors have measured so far.
import { expect, test } from '@playwright/test';
import {
  createKeys,
  NAMED,
  openGameMenu,
  rideState,
  startFreeRide,
  storeSection,
  watchPage,
} from './helpers.js';

// A small window keeps the software renderer of the CI browser fast enough
test.use({ viewport: { width: 640, height: 400 } });

// At 640 × 400 the estimates are about 22 MB (low), 38 MB (medium) and 67 MB (high)
const BUDGET_MEDIUM_FITS = 50;
const BUDGET_NOTHING_ABOVE_LOW = 30;

const AUTO_LOW = { graphicsAuto: true, graphicsLevel: 'low' };
const saveWith = (settings, crashGuard) => ({ ...NAMED, settings, crashGuard });

// 0.5 s of riding time per real frame: warm-up, window and cooldown pass in seconds
const FAST = { dt: 1 / 60, repeat: 30 };
const SLOW = { dt: 1 / 30, repeat: 30 };

const debugBox = (page) => page.locator('[data-hud="debug"]');
const setFeed = (page, feed) => page.evaluate((f) => window.__zhfTest.setFrameFeed(f), feed);
const level = async (page) => (await rideState(page)).graphicsLevel;

/** Waits until the governors have measured this many seconds of riding (in total). */
const waitFed = (page, seconds) =>
  page.waitForFunction((s) => window.__zhfTest.frameFeed()?.fedSeconds >= s, seconds, {
    polling: 200,
    timeout: 100_000,
  });

const waitLevel = (page, wanted) =>
  page.waitForFunction((l) => window.__zhfTest.ride().graphicsLevel === l, wanted, {
    polling: 200,
    timeout: 100_000,
  });

const waitSettled = (page) =>
  page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling, null, {
    polling: 200,
    timeout: 60_000,
  });

/** Holds W until the horse has moved: the ride is steerable. */
async function rideForward(page) {
  const keys = createKeys(page);
  const start = await rideState(page);
  await keys.set('w', true);
  await page.waitForFunction((z) => window.__zhfTest.ride().horse.z > z + 0.5, start.horse.z, {
    polling: 100,
    timeout: 45_000,
  });
  await keys.releaseAll();
}

test.describe('first start and "Automatic" (rule 4)', () => {
  test('a first start without a saved level is automatic at low', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    expect(await storeSection(page, 'settings')).toMatchObject(AUTO_LOW);
    expect((await startFreeRide(page)).graphicsLevel).toBe('low');
  });

  test('a saved automatic level applies at the next start', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: true, graphicsLevel: 'medium' }),
    });
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'medium',
    });
    expect((await startFreeRide(page)).graphicsLevel).toBe('medium');
  });

  test('selecting "Automatic" anew starts at low again', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: false, graphicsLevel: 'high' }),
    });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-name="graphics"][data-value="auto"]').click();
    expect(await storeSection(page, 'settings')).toMatchObject(AUTO_LOW);
    await page.locator('[data-action="back"]').click();
    expect((await startFreeRide(page)).graphicsLevel).toBe('low');
  });
});

test.describe('climbing while riding (rule 4)', () => {
  test('fast frames: low → medium, saved, the debug box says why; no step above the budget', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(180_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: saveWith(AUTO_LOW),
      lang: 'en',
      query: `&debug&gpubudget=${BUDGET_MEDIUM_FITS}`,
    });
    await startFreeRide(page);
    await expect(debugBox(page)).toContainText('Last level change (auto): none');
    await setFeed(page, FAST);

    await waitLevel(page, 'medium');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'medium',
    });
    await expect(debugBox(page)).toContainText(/Last level change \(auto\): up at 6\d fps/);
    await expect(debugBox(page)).toContainText('Level: Medium (auto)');
    await waitSettled(page);

    // more than the cooldown and a window later, the next level does not fit the budget
    const fed = (await page.evaluate(() => window.__zhfTest.frameFeed())).fedSeconds;
    await waitFed(page, fed + 45);
    expect(await level(page)).toBe('medium');
    expect(await storeSection(page, 'settings')).toMatchObject({ graphicsLevel: 'medium' });

    // still steerable, and the reached level is the one of the next start
    await rideForward(page);
    await page.reload();
    await page.locator('[data-screen="menu"]').waitFor();
    expect((await startFreeRide(page)).graphicsLevel).toBe('medium');
    expect(watch.errors).toEqual([]);
  });

  test('without a budget limit it climbs to high and stops there', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(240_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: saveWith(AUTO_LOW) });
    await startFreeRide(page);
    await setFeed(page, FAST);
    await waitLevel(page, 'medium');
    await waitLevel(page, 'high');
    await waitSettled(page);
    const fed = (await page.evaluate(() => window.__zhfTest.frameFeed())).fedSeconds;
    await waitFed(page, fed + 45);
    expect(await level(page)).toBe('high');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'high',
    });
    await rideForward(page);
    expect(watch.errors).toEqual([]);
  });

  test('slow frames: it stays at low', async ({ page, browserName }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, { save: saveWith(AUTO_LOW) });
    await startFreeRide(page);
    await setFeed(page, SLOW);
    await waitFed(page, 60);
    expect(await level(page)).toBe('low');
    expect(await storeSection(page, 'settings')).toMatchObject(AUTO_LOW);
  });

  test('a manually chosen level is never raised', async ({ page, browserName }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: false, graphicsLevel: 'low' }),
    });
    await startFreeRide(page);
    await setFeed(page, FAST);
    await waitFed(page, 60);
    expect(await level(page)).toBe('low');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: false,
      graphicsLevel: 'low',
    });
  });

  test('a level that does not fit the memory budget of the device is not climbed to', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, {
      save: saveWith(AUTO_LOW),
      query: `&gpubudget=${BUDGET_NOTHING_ABOVE_LOW}`,
    });
    await startFreeRide(page);
    await setFeed(page, FAST);
    await waitFed(page, 60);
    expect(await level(page)).toBe('low');
  });

  test('a blocked level (crash guard) is not climbed to', async ({ page, browserName }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, {
      save: saveWith(AUTO_LOW, { blockedLevels: ['medium'] }),
      lang: 'en',
      query: '&debug',
    });
    await startFreeRide(page);
    await expect(debugBox(page)).toContainText('Blocked levels: Medium');
    await setFeed(page, FAST);
    await waitFed(page, 60);
    expect(await level(page)).toBe('low');
    expect((await storeSection(page, 'crashGuard')).blockedLevels).toEqual(['medium']);
  });

  test('selecting "Automatic" anew releases the blocked levels: it climbs again', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: true, graphicsLevel: 'low' }, { blockedLevels: ['medium'] }),
      query: `&gpubudget=${BUDGET_MEDIUM_FITS}`,
    });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-name="graphics"][data-value="low"]').click();
    await page.locator('[data-name="graphics"][data-value="auto"]').click();
    expect((await storeSection(page, 'crashGuard')).blockedLevels).toEqual([]);
    await page.locator('[data-action="back"]').click();
    await startFreeRide(page);
    await setFeed(page, FAST);
    await waitLevel(page, 'medium');
  });

  test('a lost WebGL context blocks the level it happened at (saved) and the automatic stays below', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: true, graphicsLevel: 'medium' }),
      lang: 'en',
      query: '&debug',
    });
    await startFreeRide(page);
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect(await level(page)).toBe('low');
    expect((await storeSection(page, 'crashGuard')).blockedLevels).toEqual(['medium']);
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    await expect(debugBox(page)).toContainText('Last level change (auto): graphics lost');
    await expect(debugBox(page)).toContainText('Blocked levels: Medium');

    await setFeed(page, FAST);
    await waitFed(page, 60);
    expect(await level(page)).toBe('low');
  });

  test('a level left in this session because of a low frame rate is not climbed back to', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(180_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: saveWith({ graphicsAuto: true, graphicsLevel: 'medium' }),
      lang: 'en',
      query: '&debug',
    });
    await startFreeRide(page);
    await setFeed(page, SLOW);
    await waitLevel(page, 'low');
    await waitSettled(page);
    await expect(debugBox(page)).toContainText(/Last level change \(auto\): down at 30 fps/);
    await expect(debugBox(page)).toContainText('Left because of low fps: Medium');

    // now the frame rate is fine again, but the level that did not run well stays out
    await setFeed(page, FAST);
    const fed = (await page.evaluate(() => window.__zhfTest.frameFeed())).fedSeconds;
    await waitFed(page, fed + 60);
    expect(await level(page)).toBe('low');
    expect(await storeSection(page, 'settings')).toMatchObject(AUTO_LOW);
    // nothing of this is saved as a blocked level
    expect((await storeSection(page, 'crashGuard')).blockedLevels).toEqual([]);
    expect(watch.errors).toEqual([]);
  });
});
