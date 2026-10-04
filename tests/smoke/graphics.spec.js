// SRT-007 and SRT-008 in the browser, as smoke tests (rule 4, rule 44): the graphics level switch
// during a ride, WebGL context loss with its pause and restore, a budget-capped "high" that still
// renders, the debug box, the fps display. Chromium only. The rules behind them (staged changes,
// governors, budget fitting, hints, fallbacks) are unit-tested in src/adapters/view3d/ and
// src/adapters/ui/; these tests only prove that the pieces work together in a real browser. They
// read the state through `window.__zhfTest`.
import { expect, test } from '@playwright/test';
import {
  createFinger,
  createKeys,
  expectPicture,
  NAMED,
  openGameMenu,
  rideCourseOne,
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
    test.setTimeout(120_000); // the switch compiles the medium shaders on the software renderer
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
    await expectPicture(page);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('graphics level switch with touch (rule 4)', () => {
  // A small window keeps the software renderer fast enough
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

  test('low → medium by tapping: the joystick still speeds the horse up', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName !== 'chromium', 'CDP touch input needs Chromium');
    test.setTimeout(120_000); // the switch compiles the medium shaders on the software renderer
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, { save: MANUAL_LOW });
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    const finger = await createFinger(page);
    await pushForward(page, finger);

    await pullToStop(page, finger);
    await tapSwitchLevel(page, 'medium');
    expect((await rideState(page)).graphicsLevel).toBe('medium');
    // the speed rises from a standstill after the switch
    expect((await rideState(page)).horse.speed).toBe(0);
    await pushForward(page, finger);

    await expectPicture(page);
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
    await expectPicture(page);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('GPU memory budget (rule 4)', () => {
  // a device pixel ratio of 2 gives the level's pixel ratio something to cap
  test.use({ deviceScaleFactor: 2 });

  test('a budget that "high" does not fit caps the pixel ratio and the ride still renders', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(120_000); // software rendering at "high" is slow on a busy runner
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { graphicsAuto: false, graphicsLevel: 'high' } },
      lang: 'en',
      query: '&debug&gpubudget=90',
    });
    await startFreeRide(page);
    const ratio = (await rideState(page)).graphicsPixelRatio;
    expect(ratio).toBeGreaterThanOrEqual(1);
    expect(ratio).toBeLessThan(2);
    expect((await rideState(page)).graphicsLevel).toBe('high'); // the level stays
    await expect(page.locator('[data-hud="debug"]')).toContainText(
      new RegExp(`GPU est\\. \\d+ / 90 MB, ratio capped 2 → ${ratio}`),
    );
    expect(await rideForward(page)).toBeGreaterThan(0.5);
    await expectPicture(page);
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
});

test.describe('fps display (rules 4, 44)', () => {
  test('off by default; the toggle shows fps and level in the ride, updates live and is saved', async ({
    page,
    browserName,
  }) => {
    // three ride starts, a switch to medium (shader compile) and a reload on the software renderer
    test.setTimeout(120_000);
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
