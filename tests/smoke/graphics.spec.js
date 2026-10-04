// SRT-007 in the browser: switching the graphics level during a ride, WebGL context loss and the
// fps display (rule 4, rule 44). The tests read the state through `window.__zhfTest`.
import { expect, test } from '@playwright/test';
import {
  canvasScreenshotSize,
  createKeys,
  NAMED,
  openGameMenu,
  rideState,
  startFreeRide,
  storeSection,
  waitForRide,
  watchPage,
} from './helpers.js';

// A small window keeps the software renderer of the CI browser fast enough
test.use({ viewport: { width: 640, height: 400 } });

const MANUAL_LOW = { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'low' } };

const fpsHud = (page) => page.locator('[data-hud="fps"]');

/** Holds W until the horse has moved, then lets go and returns the distance covered. */
async function rideForward(page) {
  const keys = createKeys(page);
  const start = await rideState(page);
  await keys.set('w', true);
  await page.waitForFunction((z) => window.__zhfTest.ride().horse.z > z + 0.5, start.horse.z, {
    polling: 100,
    timeout: 45_000,
  });
  await keys.releaseAll();
  return (await rideState(page)).horse.z - start.horse.z;
}

/** Pause → settings → pick a graphics level → back → continue. */
async function switchLevelInPause(page, value) {
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => window.__zhfTest.ride().paused);
  await page.locator('[data-action="settings"]').click();
  await page.locator(`[data-name="graphics"][data-value="${value}"]`).click();
  await page.locator('.panel-settings [data-action="back"]').click();
  await page.locator('[data-action="resume"]').click();
  await page.waitForFunction(() => !window.__zhfTest.ride().paused);
}

test.describe('graphics level switch during a ride (rule 4)', () => {
  test('after changing the level in both directions the ride stays steerable', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    await startFreeRide(page);
    expect(await rideForward(page)).toBeGreaterThan(0.5);

    // up: low → medium
    await switchLevelInPause(page, 'medium');
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect(await rideForward(page)).toBeGreaterThan(0.5);

    // down: medium → low
    await switchLevelInPause(page, 'low');
    expect((await rideState(page)).graphicsLevel).toBe('low');
    expect(await rideForward(page)).toBeGreaterThan(0.5);

    // steering works as well
    const keys = createKeys(page);
    const heading = (await rideState(page)).horse.heading;
    await keys.set('a', true);
    await page.waitForFunction((h) => window.__zhfTest.ride().horse.heading !== h, heading);
    await keys.releaseAll();

    // the picture is still there
    expect(await canvasScreenshotSize(page)).toBeGreaterThan(30_000);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('WebGL context loss (rule 4)', () => {
  test('the ride pauses on loss and can go on after the restore', async ({ page, browserName }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    await startFreeRide(page);
    expect((await rideState(page)).paused).toBe(false);

    // the device takes the graphics memory away
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect((await rideState(page)).paused).toBe(true);
    await expect(page.locator('[data-overlay="pause"]')).toBeVisible();
    await expect(page.locator('[data-note="graphics-lost"]')).toBeVisible();
    // there is no going on without a picture: Continue is off and Esc does nothing
    await expect(page.locator('[data-action="resume"]')).toBeDisabled();
    await page.keyboard.press('Escape');
    expect((await rideState(page)).paused).toBe(true);

    // the browser gives the memory back: the note goes, the ride can be continued
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await expect(page.locator('[data-note="graphics-lost"]')).toBeHidden();
    await expect(page.locator('[data-action="resume"]')).toBeEnabled();
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);

    // the horse moves again and the scene is drawn again
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    await expect
      .poll(() => canvasScreenshotSize(page), { timeout: 30_000 })
      .toBeGreaterThan(30_000);
    expect(watch.errors).toEqual([]);
  });

  test('a loss before the ride starts: the ride begins paused until the restore', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    // the engine (and its context) exists once a ride was opened
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.locator('[data-action="quit"]').click();
    await page.locator('[data-screen="menu"]').waitFor();

    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.locator('[data-entry="free"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride()?.contextLost);
    expect((await rideState(page)).paused).toBe(true);

    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    expect(await rideForward(page)).toBeGreaterThan(0.5);
  });
});

test.describe('fps display (rules 4, 44)', () => {
  test('off by default; the toggle shows fps and level in the ride, updates live and is saved', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'en' });
    expect((await storeSection(page, 'settings')).showFps).toBe(false);

    // off: nothing in the ride
    await startFreeRide(page);
    await expect(fpsHud(page)).toBeHidden();
    await page.keyboard.press('Escape');
    await page.locator('[data-action="quit"]').click();

    // switch on in the settings
    await page.locator('[data-entry="settings"]').click();
    const toggle = page.locator('[data-name="showFps"]');
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect((await storeSection(page, 'settings')).showFps).toBe(true);
    await page.locator('[data-action="back"]').click();

    // on: a number and the level (first start: picked automatically)
    await startFreeRide(page);
    await expect(fpsHud(page)).toBeVisible();
    await expect(fpsHud(page)).toHaveText(/^\d+ fps · (Low|Medium|High) \(auto\)$/, {
      timeout: 15_000,
    });

    // a manual choice in the pause menu shows up at once, without "(auto)"
    await switchLevelInPause(page, 'low');
    await expect(fpsHud(page)).toHaveText(/ · Low$/);
    await expect(fpsHud(page)).not.toContainText('auto');
    await switchLevelInPause(page, 'medium');
    await expect(fpsHud(page)).toHaveText(/ · Medium$/);

    // saved: it survives a reload and shows in the next ride
    await page.reload();
    await page.locator('[data-screen="menu"]').waitFor();
    expect((await storeSection(page, 'settings')).showFps).toBe(true);
    await page.locator('[data-entry="free"]').click();
    await waitForRide(page, 'free');
    await expect(fpsHud(page)).toBeVisible();

    // off again from the pause menu: gone at once
    await page.keyboard.press('Escape');
    await page.locator('[data-action="settings"]').click();
    await page.locator('[data-name="showFps"]').click();
    await page.locator('.panel-settings [data-action="back"]').click();
    await expect(fpsHud(page)).toBeHidden();
    expect((await storeSection(page, 'settings')).showFps).toBe(false);
  });

  test('the value updates about twice per second', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { showFps: true, graphicsAuto: false, graphicsLevel: 'low' } },
      lang: 'en',
    });
    await startFreeRide(page);
    await expect(fpsHud(page)).toHaveText(/^\d+ fps/, { timeout: 15_000 });
    // count text changes over 4 s: at most ~2 per second (plus a little slack)
    const changes = await page.evaluate(
      () =>
        new Promise((resolve) => {
          const el = document.querySelector('[data-hud="fps"]');
          let count = 0;
          const observer = new MutationObserver(() => (count += 1));
          observer.observe(el, { childList: true, characterData: true, subtree: true });
          setTimeout(() => {
            observer.disconnect();
            resolve(count);
          }, 4000);
        }),
    );
    expect(changes).toBeLessThanOrEqual(10);
  });
});

test.describe('fps display layout (touch)', () => {
  test.use({ viewport: { width: 900, height: 420 }, hasTouch: true, isMobile: true });

  const overlaps = (a, b) =>
    a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

  /** The fps box must not touch any control, chip or hint of the ride screen. */
  async function expectFpsFree(page) {
    const fps = await fpsHud(page).boundingBox();
    expect(fps).not.toBeNull();
    const selectors = [
      '[data-control="joystick"]',
      '.touch-gallop',
      '.touch-jump',
      '.touch-pause',
      '.touch-camera',
      '.ride-hint:not([hidden])',
      '.hud-chip:not([hidden])',
    ];
    for (const selector of selectors) {
      const boxes = await page.locator(selector).evaluateAll((els) =>
        els.map((el) => {
          const r = el.getBoundingClientRect();
          return { x: r.x, y: r.y, width: r.width, height: r.height };
        }),
      );
      for (const box of boxes) {
        expect(overlaps(fps, box), `${selector} overlaps the fps display`).toBe(false);
      }
    }
  }

  test('in free mode, pre-start and course ride it covers no control', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { showFps: true } },
      lang: 'en',
    });
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    await expect(fpsHud(page)).toBeVisible();
    await expectFpsFree(page);
    await page.locator('.touch-pause').tap();
    await page.locator('[data-action="quit"]').tap();

    // course pre-start (HUD with the notice chip) and the ride itself
    await page.locator('[data-entry="courses"]').tap();
    await page.locator('[data-course="1"]').tap();
    await page.locator('[data-action="go"]').tap();
    await waitForRide(page, 'course');
    await expect(fpsHud(page)).toBeVisible();
    await expectFpsFree(page);
  });
});
