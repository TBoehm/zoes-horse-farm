// SRT-007 and SRT-008 in the browser: switching the graphics level during a ride (staged), WebGL
// context loss with its fallback, the fps display and the debug box (rule 4, rule 44). The tests
// read the state through `window.__zhfTest`.
import { expect, test } from '@playwright/test';
import {
  canvasScreenshotSize,
  createFinger,
  createKeys,
  NAMED,
  openGameMenu,
  rideCourseOne,
  rideState,
  setTabHidden,
  showTabAndLoseContext,
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

test.describe('graphics level switch with touch (rule 4)', () => {
  // A small window keeps the software renderer fast enough, also for "high"
  test.use({ viewport: { width: 640, height: 360 }, hasTouch: true, isMobile: true });

  /** Pause → settings → pick a graphics level → back → continue, all by tapping. */
  async function tapSwitchLevel(page, value) {
    await page.locator('.touch-pause').tap();
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.locator('[data-action="settings"]').tap();
    await page.locator(`[data-name="graphics"][data-value="${value}"]`).tap();
    await page.locator('.panel-settings [data-action="back"]').tap();
    await page.locator('[data-action="resume"]').tap();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
  }

  const waitSpeed = (page, test, arg) =>
    page.waitForFunction(test, arg, { polling: 100, timeout: 45_000 });

  /** Pushes the joystick forward: the speed must rise. Then lets go (the speed stays). */
  async function pushForward(page, finger) {
    const box = await page.locator('[data-control="joystick"]').boundingBox();
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    await finger.down(cx, cy);
    await finger.move(cx, cy - 10);
    await finger.move(cx, cy - 60);
    await waitSpeed(page, () => window.__zhfTest.ride().horse.speed > 0.5);
    await finger.up();
  }

  /** Pulls the joystick down until the horse stands still again. */
  async function pullToStop(page, finger) {
    const box = await page.locator('[data-control="joystick"]').boundingBox();
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    await finger.down(cx, cy);
    await finger.move(cx, cy + 20);
    await finger.move(cx, cy + 60);
    await waitSpeed(page, () => window.__zhfTest.ride().horse.speed < 0);
    await finger.up();
    await waitSpeed(page, () => window.__zhfTest.ride().horse.speed === 0);
  }

  test('low → high → medium → low by tapping: the joystick still speeds the horse up', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName !== 'chromium', 'CDP touch input needs Chromium');
    test.setTimeout(240_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    const finger = await createFinger(page);
    await pushForward(page, finger);

    for (const level of ['high', 'medium', 'low']) {
      await pullToStop(page, finger);
      await tapSwitchLevel(page, level);
      expect((await rideState(page)).graphicsLevel).toBe(level);
      // the speed rises from a standstill after the switch
      expect((await rideState(page)).horse.speed).toBe(0);
      await pushForward(page, finger);
    }

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

  test('a context that does not come back asks for a reload after a few seconds', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(90_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW, lang: 'en' });
    await startFreeRide(page);

    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    const note = page.locator('[data-note="graphics-lost"]');
    const reload = page.locator('[data-action="reload"]');
    await expect(note).toHaveText(/Back in a second/);
    await expect(reload).toBeHidden();
    // Continue is off, so the focus goes to the first button that works
    await expect(page.locator('[data-action="restart"]')).toBeFocused();

    // no restore for ~8 s: the note asks for a reload and the button appears and gets the focus
    await expect(reload).toBeVisible({ timeout: 20_000 });
    await expect(note).toHaveText('Please reload the page.');
    await expect(reload).toHaveText('Reload');
    await expect(reload).toBeFocused();
    await expect(page.locator('[data-action="resume"]')).toBeDisabled();

    // a late restore still works: back to the normal pause menu
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await expect(reload).toBeHidden();
    await expect(note).toBeHidden();
    await expect(page.locator('[data-action="resume"]')).toBeEnabled();
    await expect(page.locator('[data-action="resume"]')).toBeFocused();

    // lost again and never restored: the button reloads the page (back in the main menu)
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await expect(reload).toBeVisible({ timeout: 20_000 });
    await reload.click();
    await page.locator('[data-screen="menu"]').waitFor();
    expect(await page.evaluate(() => window.__zhfTest.screen())).toBe('menu');
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

test.describe('context loss fallback (rule 4)', () => {
  const lostToasts = (page) => page.locator('.ride-feedback.is-visible');

  /** Loses the context, waits for the pause, restores it and continues the ride. */
  async function loseRestoreResume(page) {
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
  }

  test('"Automatic": the level goes to low (saved, automatic stays on) and the ride goes on', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);
    expect((await rideState(page)).graphicsLevel).toBe('medium');

    // lost: the level is already low while nothing is drawn, so the restored scene comes back low
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect((await rideState(page)).graphicsLevel).toBe('low');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'low',
    });

    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    expect((await rideState(page)).graphicsLevel).toBe('low');
    // no extra switch after the restore, and no hint in automatic mode
    await page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling);
    await expect(lostToasts(page)).toHaveCount(0);

    expect(await rideForward(page)).toBeGreaterThan(0.5);
    await expect
      .poll(() => canvasScreenshotSize(page), { timeout: 30_000 })
      .toBeGreaterThan(30_000);
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'low',
    });
    expect(watch.errors).toEqual([]);
  });

  test('a manual level above low stays and a hint asks for a lower one once the ride goes on', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);
    await loseRestoreResume(page);

    await expect(lostToasts(page)).toHaveText(
      'The graphics were too much for this device. Pick a lower level in the settings.',
    );
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: false,
      graphicsLevel: 'medium',
    });
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    expect(watch.errors).toEqual([]);
  });

  test('a loss while the tab is in the background is no overload: the level stays, no hint', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);

    await setTabHidden(page, true); // the app goes to the background, the ride pauses
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'medium',
    });

    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);
    await setTabHidden(page, false);
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect(await storeSection(page, 'settings')).toMatchObject({ graphicsLevel: 'medium' });
    expect(watch.errors).toEqual([]);
  });

  test('a manual level gets no hint for a loss in the background', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);
    await setTabHidden(page, true); // the loss comes while the app is in the background
    await loseRestoreResume(page);
    await setTabHidden(page, false);
    await expect(lostToasts(page)).toHaveCount(0);
    expect((await rideState(page)).graphicsLevel).toBe('medium');
  });

  test('a loss right after the tab came back is no overload; a few seconds later it is', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(90_000);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);

    await setTabHidden(page, true);
    // back and lost in one task: the grace time cannot run out in between (slow CI frames)
    expect(await showTabAndLoseContext(page)).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForFunction(() => !window.__zhfTest.ride().contextLost);

    // settled in the foreground (the grace time is 3 s): a loss now counts
    await page.waitForTimeout(3500);
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await page.waitForFunction(() => window.__zhfTest.ride().contextLost);
    expect((await rideState(page)).graphicsLevel).toBe('low');
  });

  test('a manual low level gets no hint', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW, lang: 'en' });
    await startFreeRide(page);
    await loseRestoreResume(page);
    await expect(lostToasts(page)).toHaveCount(0);
    expect((await rideState(page)).graphicsLevel).toBe('low');
  });
});

test.describe('context loss fallback: drawing buffer (rule 4)', () => {
  // a device pixel ratio of 2 gives "high" the full ratio 2, "low" gets 1
  test.use({ deviceScaleFactor: 2 });

  const bufferWidth = (page) => page.evaluate(() => document.querySelector('.scene-canvas').width);

  test('the buffer is already small while the context is lost, so the restore does not rebuild the big one', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'high' } },
    });
    await startFreeRide(page);
    expect((await rideState(page)).graphicsPixelRatio).toBe(2);
    const big = await bufferWidth(page);
    // back to the menu: no frame loop, so only the loss handler can change the buffer now
    await page.keyboard.press('Escape');
    await page.locator('[data-action="quit"]').click();
    await page.locator('[data-screen="menu"]').waitFor();

    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await expect.poll(async () => (await storeSection(page, 'settings')).graphicsLevel).toBe('low');
    // still lost: the buffer already has the size of the low level (ratio 1 instead of 2)
    expect(await bufferWidth(page)).toBe(big / 2);

    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await page.waitForTimeout(500);
    expect(await bufferWidth(page)).toBe(big / 2);
    await page.locator('[data-entry="free"]').click();
    await page.waitForFunction(
      () => window.__zhfTest.ride() && !window.__zhfTest.ride().contextLost,
    );
    expect((await rideState(page)).graphicsLevel).toBe('low');
    expect((await rideState(page)).graphicsPixelRatio).toBe(1);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('staged downgrade during a ride (rule 4)', () => {
  test('a governor-style change medium → low is applied over several frames, the picture stays', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(120_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
    });
    await startFreeRide(page);
    expect(await rideForward(page)).toBeGreaterThan(0.5);

    // lower the level while riding and count the drawn frames until the switch is complete
    const frames = await page.evaluate(
      () =>
        new Promise((resolve) => {
          window.__zhfTest.setAutoLevel('low');
          let settlingFrames = 0;
          const check = () => {
            if (window.__zhfTest.ride().graphicsSettling) settlingFrames += 1;
            else if (settlingFrames > 0) return resolve(settlingFrames);
            requestAnimationFrame(check);
          };
          requestAnimationFrame(check);
          setTimeout(() => resolve(settlingFrames), 60_000);
        }),
    );
    // five stages with a gap of several frames each: never a single frame
    expect(frames).toBeGreaterThanOrEqual(5);

    const ride = await rideState(page);
    expect(ride.graphicsLevel).toBe('low');
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'low',
    });
    // still steerable and the canvas is not blank
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    const keys = createKeys(page);
    const heading = (await rideState(page)).horse.heading;
    await keys.set('a', true);
    await page.waitForFunction((h) => window.__zhfTest.ride().horse.heading !== h, heading);
    await keys.releaseAll();
    expect(await canvasScreenshotSize(page)).toBeGreaterThan(30_000);
    expect(watch.errors).toEqual([]);
  });

  test('the real governor on a slow device lowers the level without losing the picture', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName !== 'chromium', 'CPU throttling needs the Chromium DevTools protocol');
    test.setTimeout(150_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
    });
    await startFreeRide(page);
    const client = await page.context().newCDPSession(page);
    await client.send('Emulation.setCPUThrottlingRate', { rate: 8 });
    // 3 s grace + 5 s window below 50 fps: the governor steps down
    await page.waitForFunction(() => window.__zhfTest.ride().graphicsLevel === 'low', null, {
      timeout: 90_000,
      polling: 250,
    });
    await page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling, null, {
      timeout: 90_000,
      polling: 250,
    });
    await client.send('Emulation.setCPUThrottlingRate', { rate: 1 });

    const ride = await rideState(page);
    expect(ride.contextLost).toBe(false);
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: true,
      graphicsLevel: 'low',
    });
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    expect(await canvasScreenshotSize(page)).toBeGreaterThan(30_000);
    expect(watch.errors).toEqual([]);
  });

  test('a change back up and down again in a row ends at the last level', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(120_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'medium' } },
    });
    await startFreeRide(page);
    await page.evaluate(() => {
      window.__zhfTest.setAutoLevel('low');
      window.__zhfTest.setAutoLevel('medium');
      window.__zhfTest.setAutoLevel('low');
    });
    await page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling, null, {
      timeout: 60_000,
    });
    expect((await rideState(page)).graphicsLevel).toBe('low');
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    expect(await canvasScreenshotSize(page)).toBeGreaterThan(30_000);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('GPU memory budget (rule 4)', () => {
  // a device pixel ratio of 2 gives the level's pixel ratio something to cap
  test.use({ deviceScaleFactor: 2 });

  const MANUAL_HIGH = { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'high' } };
  const debugBox = (page) => page.locator('[data-hud="debug"]');

  /** Opens a free ride at "high" with a forced budget (MiB) and the debug box. */
  async function rideWithBudget(page, test, browserName, budget, extra = {}) {
    await openGameMenu(page, test, browserName, {
      save: MANUAL_HIGH,
      lang: 'en',
      query: `&debug&gpubudget=${budget}`,
      ...extra,
    });
    await startFreeRide(page);
  }

  const steerAndDraw = async (page) => {
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    await expect
      .poll(() => canvasScreenshotSize(page), { timeout: 30_000 })
      .toBeGreaterThan(30_000);
  };

  test('without a small budget "high" keeps its full pixel ratio', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: MANUAL_HIGH,
      lang: 'en',
      query: '&debug',
    });
    await startFreeRide(page);
    expect((await rideState(page)).graphicsPixelRatio).toBe(2);
    await expect(debugBox(page)).toContainText(/GPU est\. \d+ \/ \d+ MB/);
    await expect(debugBox(page)).not.toContainText('capped');
  });

  test('a budget that "high" does not fit caps the pixel ratio and the ride still renders', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await rideWithBudget(page, test, browserName, 90);
    const ratio = (await rideState(page)).graphicsPixelRatio;
    expect(ratio).toBeGreaterThanOrEqual(1);
    expect(ratio).toBeLessThan(2);
    expect((await rideState(page)).graphicsLevel).toBe('high'); // the level stays
    await expect(debugBox(page)).toContainText(
      new RegExp(`GPU est\\. \\d+ / 90 MB, ratio capped 2 → ${ratio}`),
    );
    await steerAndDraw(page);
    expect(watch.errors).toEqual([]);
  });

  test('a smaller budget goes down to ratio 1, then halves the shadow map', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await rideWithBudget(page, test, browserName, 60);
    expect((await rideState(page)).graphicsPixelRatio).toBe(1);
    await expect(debugBox(page)).toContainText('ratio capped 2 → 1');
    await expect(debugBox(page)).toContainText('Shadow map capped 2048 → 1024');
    await steerAndDraw(page);
    expect(watch.errors).toEqual([]);
  });

  test('a budget that not even the multisampled buffers fit creates the context without antialiasing', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await rideWithBudget(page, test, browserName, 12);
    await expect(debugBox(page)).toContainText('Antialiasing: off (not enough graphics memory)');
    expect((await rideState(page)).graphicsPixelRatio).toBe(1);
    await steerAndDraw(page);
    expect(watch.errors).toEqual([]);
  });

  test('a manual switch to "high" is checked against the budget too', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(120_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: MANUAL_LOW,
      lang: 'en',
      // the context was made for "low", without multisampling: that is cheaper than the "high"
      // contexts of the tests above, so the budget has to be smaller to cap anything
      query: '&debug&gpubudget=65',
    });
    await startFreeRide(page);
    expect((await rideState(page)).graphicsPixelRatio).toBe(1);
    await switchLevelInPause(page, 'high');
    await page.waitForFunction(() => !window.__zhfTest.ride().graphicsSettling, null, {
      timeout: 60_000,
    });
    const ride = await rideState(page);
    expect(ride.graphicsLevel).toBe('high');
    expect(ride.graphicsPixelRatio).toBeGreaterThan(1);
    expect(ride.graphicsPixelRatio).toBeLessThan(2);
    await steerAndDraw(page);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('debug box (?debug)', () => {
  const debugBox = (page) => page.locator('[data-hud="debug"]');

  test('shows GPU, level, pixel ratios, buffer, context counts and the last errors', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: true, graphicsLevel: 'low' } },
      lang: 'en',
      query: '&debug',
    });
    await startFreeRide(page);
    const box = debugBox(page);
    await expect(box).toBeVisible();
    await expect(box).toContainText(/^GPU: \S+/);
    await expect(box).toContainText('Level: Low (auto)');
    await expect(box).toContainText(/Pixel ratio: device [\d.]+, drawing [\d.]+/);
    await expect(box).toContainText(/Canvas: \d+ × \d+/);
    await expect(box).toContainText(/Largest texture: \d+/);
    await expect(box).toContainText(/Antialiasing: (on|off)/);
    await expect(box).toContainText(/GPU est\. \d+ \/ \d+ MB/);
    await expect(box).toContainText('Graphics lost: 0× (–), back: 0× (–)');
    await expect(box).toContainText('Errors: none');

    // it sits under the fps line in the HUD column (the fps line is off: it is the first line)
    const y = await box.evaluate((el) => el.getBoundingClientRect().top);
    expect(y).toBeLessThan(120);

    // errors show up (newest first), about twice per second
    await page.evaluate(() => {
      window.dispatchEvent(new ErrorEvent('error', { message: 'first problem' }));
      window.dispatchEvent(new ErrorEvent('error', { message: 'second problem' }));
    });
    await expect(box).toContainText('Last errors (2):', { timeout: 5000 });
    const text = await box.innerText();
    expect(text.indexOf('second problem')).toBeLessThan(text.indexOf('first problem'));

    // context counts follow a loss and a restore
    expect(await page.evaluate(() => window.__zhfTest.loseContext())).toBe(true);
    await expect(box).toContainText(/Graphics lost: 1× \(at \d+ s\)/, { timeout: 5000 });
    expect(await page.evaluate(() => window.__zhfTest.restoreContext())).toBe(true);
    await expect(box).toContainText(/back: 1× \(at \d+ s\)/, { timeout: 15_000 });
    expect(watch.errors).toEqual([]);
  });

  test('is in German too', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW, lang: 'de', query: '&debug' });
    await startFreeRide(page);
    await expect(debugBox(page)).toContainText('Stufe: Niedrig');
    await expect(debugBox(page)).toContainText('Zeichenfläche:');
  });

  test('is not there without ?debug', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    await startFreeRide(page);
    await expect(page.locator('[data-hud="fps"]')).toBeHidden();
    await expect(debugBox(page)).toHaveCount(0);
    expect(await page.evaluate(() => window.__zhfTest.ride().graphicsLevel)).toBe('low');
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
    // count text changes over 4 s: about 2 per second (slack for slow software rendering)
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
    expect(changes).toBeGreaterThanOrEqual(4);
    expect(changes).toBeLessThanOrEqual(10);
  });
});

test.describe('hint for a level that is too high (rule 4)', () => {
  test('a slow device at a manual level gets the hint once, the level stays', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName !== 'chromium', 'CPU throttling needs the Chromium DevTools protocol');
    test.setTimeout(120_000);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'medium' } },
      lang: 'en',
    });
    await startFreeRide(page);
    // a slow device: the average over the measuring window falls below 30 fps
    const client = await page.context().newCDPSession(page);
    await client.send('Emulation.setCPUThrottlingRate', { rate: 40 });

    const toast = page.locator('.ride-feedback.is-visible');
    await expect(toast).toHaveText(
      'The graphics are too high for this device. Pick a lower level.',
      {
        timeout: 90_000,
      },
    );
    await client.send('Emulation.setCPUThrottlingRate', { rate: 1 });
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    expect((await storeSection(page, 'settings')).graphicsLevel).toBe('medium');

    // the toast goes away after a few seconds ("only once per ride" is unit-tested in
    // quality.test.js)
    await expect(toast).toBeHidden({ timeout: 15_000 });
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
    expect((await rideState(page)).hud.phase).toBe('prestart');
    await expectFpsFree(page);

    // cross the start line: the timer runs and the HUD shows the ride chips
    await rideCourseOne(page, { stopWhen: (ride) => ride.hud.phase === 'riding' });
    expect((await rideState(page)).hud.phase).toBe('riding');
    await expect(fpsHud(page)).toBeVisible();
    await expectFpsFree(page);
  });
});
