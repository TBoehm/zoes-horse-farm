// SRT-014 in the browser, as smoke tests: the graphics automatic starts at "low" and climbs while
// riding (concept rule 4). Chromium only. The rules (frame rate and slow-frame limits, warm-up,
// cooldown, blocked, over-budget and left levels) are unit-tested in
// src/adapters/view3d/quality-upgrade.test.js, this proves that the engine, the settings and the
// debug box work together. The real frame rate of the software renderer in the CI browser is far
// from 57 fps, so the test feeds the governors with frame times through the test hook
// `window.__zhfTest.setFrameFeed({ dt, repeat })`: every real frame then counts as `repeat` frames
// of `dt` seconds.
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

// At 640 × 400 the estimates are about 22 MB (low), 38 MB (medium) and 67 MB (high): with this
// budget the automatic can climb to medium and not further, so only one level is ever compiled
const BUDGET_MEDIUM_FITS = 50;

const AUTO_LOW = { graphicsAuto: true, graphicsLevel: 'low' };

// 0.5 s of riding time per real frame: warm-up, window and cooldown pass in seconds
const FAST = { dt: 1 / 60, repeat: 30 };

const debugBox = (page) => page.locator('[data-hud="debug"]');
const setFeed = (page, feed) => page.evaluate((f) => window.__zhfTest.setFrameFeed(f), feed);

test('a first start without a saved level is automatic at low', async ({ page, browserName }) => {
  await openGameMenu(page, test, browserName);
  expect(await storeSection(page, 'settings')).toMatchObject(AUTO_LOW);
  expect((await startFreeRide(page)).graphicsLevel).toBe('low');
});

test('fast frames: low → medium, saved, the debug box says why, still steerable', async ({
  page,
  browserName,
}) => {
  test.setTimeout(150_000); // the climb has to compile the medium level on the software renderer
  const watch = watchPage(page);
  await openGameMenu(page, test, browserName, {
    save: { ...NAMED, settings: AUTO_LOW },
    lang: 'en',
    query: `&debug&gpubudget=${BUDGET_MEDIUM_FITS}`,
  });
  await startFreeRide(page);
  await expect(debugBox(page)).toContainText('Last level change (auto): none');
  await setFeed(page, FAST);

  await page.waitForFunction(() => window.__zhfTest.ride().graphicsLevel === 'medium', null, {
    polling: 200,
    timeout: 100_000,
  });
  expect(await storeSection(page, 'settings')).toMatchObject({
    graphicsAuto: true,
    graphicsLevel: 'medium',
  });
  await expect(debugBox(page)).toContainText(/Last level change \(auto\): up at 6\d fps/);
  await expect(debugBox(page)).toContainText('Level: Medium (auto)');
  await page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling, null, {
    polling: 200,
    timeout: 60_000,
  });

  // still steerable, and the reached level is the one of the next start
  const keys = createKeys(page);
  const startZ = (await rideState(page)).horse.z;
  await keys.set('w', true);
  await page.waitForFunction((z) => window.__zhfTest.ride().horse.z > z + 0.5, startZ, {
    polling: 100,
    timeout: 45_000,
  });
  await keys.releaseAll();
  await page.reload();
  await page.locator('[data-screen="menu"]').waitFor();
  expect((await startFreeRide(page)).graphicsLevel).toBe('medium');
  expect(watch.errors).toEqual([]);
});
