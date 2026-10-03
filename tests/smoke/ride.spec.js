// SRT-002 and SRT-003 in the browser: free riding, gaits, camera, pause, graphics, touch controls,
// jumping and the jump counter. The tests read the state through the hook `window.__zhfTest`;
// time-based waits are replaced by "wait for state" (the software renderer of CI is slow).
import { expect, test } from '@playwright/test';
import {
  canvasScreenshotSize,
  createKeys,
  jumpOverCross,
  NAMED,
  openGameMenu,
  rideState,
  screenName,
  startFreeRide,
  storeSection,
  waitForRide,
  watchPage,
} from './helpers.js';

// A small window keeps the software renderer of the CI browser fast enough
test.use({ viewport: { width: 640, height: 400 } });

/** Touch input through the Chrome DevTools Protocol (real touch events, like a finger). */
async function createFinger(page) {
  const client = await page.context().newCDPSession(page);
  const send = (type, points) =>
    client.send('Input.dispatchTouchEvent', { type, touchPoints: points });
  return {
    down: (x, y) => send('touchStart', [{ x, y, id: 1 }]),
    move: (x, y) => send('touchMove', [{ x, y, id: 1 }]),
    up: () => send('touchEnd', []),
  };
}

test.describe('free riding (SRT-002)', () => {
  test('starts, renders a non-blank 3D scene without console errors or asset files', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName);
    const ride = await startFreeRide(page);
    // standing at the start in a halt, default look: bay with star (rule 43)
    expect(ride.horse).toMatchObject({ gait: 'halt', speed: 0, gallop: false });
    expect(await storeSection(page, 'horse')).toMatchObject({ coat: 'bay', marking: 'star' });
    // a few frames later the canvas shows a real scene, not one flat colour
    await page.waitForFunction(
      () => document.querySelector('canvas.scene-canvas')?.clientWidth > 0,
    );
    expect(await canvasScreenshotSize(page)).toBeGreaterThan(30_000);
    await page.waitForLoadState('networkidle');
    expect(watch.errors).toEqual([]);
    expect(watch.forbidden).toEqual([]);
  });

  test('W raises the speed, Shift gives canter, the horse moves', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    const start = await startFreeRide(page);
    const keys = createKeys(page);
    await keys.set('w', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.gait === 'trot');
    await keys.set('Shift', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.gait === 'canter');
    const canter = await rideState(page);
    expect(canter.horse.gallop).toBe(true);
    expect(canter.horse.z).toBeGreaterThan(start.horse.z);
    // the 3D horse stands where the simulation puts it
    expect(canter.horsePosition[2]).toBeCloseTo(canter.horse.z, 0);
    await keys.releaseAll();
  });

  test('the fence stops the horse head-on: halt, gallop off', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    const keys = createKeys(page);
    await keys.set('w', true);
    await keys.set('Shift', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.z > 30, null, { polling: 100 });
    await page.waitForFunction(() => window.__zhfTest.ride().horse.gait === 'halt', null, {
      polling: 100,
    });
    const { horse } = await rideState(page);
    expect(horse.z).toBeLessThanOrEqual(35);
    expect(horse.gallop).toBe(false);
    // Shift is still held: the canter only starts again after pressing it anew
    await page.evaluate(
      () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
    );
    expect((await rideState(page)).horse.gait).toBe('halt');
    // Turn away from the fence on the spot (W released), Shift still held ...
    await keys.set('w', false);
    await keys.set('a', true);
    await page.waitForFunction(() => Math.cos(window.__zhfTest.ride().horse.heading) < -0.7, null, {
      polling: 50,
    });
    await keys.set('a', false);
    // ... trotting off does not start the canter: the old Shift press is used up ...
    await keys.set('w', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.gait === 'trot', null, {
      polling: 50,
    });
    await page.evaluate(
      () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
    );
    const trotting = (await rideState(page)).horse;
    expect(trotting.gallop).toBe(false);
    expect(trotting.gait).not.toBe('canter');
    // ... only releasing and pressing Shift again does
    await keys.set('Shift', false);
    await keys.set('Shift', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.gait === 'canter', null, {
      polling: 50,
    });
    expect((await rideState(page)).horse.gallop).toBe(true);
    await keys.releaseAll();
  });

  test('A and D turn the horse on the spot', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    const keys = createKeys(page);
    await keys.set('a', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.heading > 0.2);
    await keys.set('a', false);
    await keys.set('d', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.heading < 0);
    await keys.releaseAll();
    expect((await rideState(page)).horse.speed).toBe(0);
  });

  test('Esc pauses: the horse stands still, the pause menu offers four actions', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    const keys = createKeys(page);
    await keys.set('w', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.speed > 1);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await keys.releaseAll();
    await expect(page.locator('[data-overlay="pause"] [data-action]')).toHaveCount(4);
    // the pause menu is a modal dialog with a name
    const dialog = page.locator('[data-overlay="pause"]');
    await expect(dialog).toHaveAttribute('role', 'dialog');
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    await expect(dialog).toHaveAttribute('aria-labelledby', 'ride-pause-title');
    const before = await rideState(page);
    // a few frames pass while paused; nothing moves
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    );
    const after = await rideState(page);
    expect(after.horse.z).toBe(before.horse.z);
    expect(after.horse.x).toBe(before.horse.x);
    // "Continue" keeps going with the previous speed
    await page.locator('[data-action="resume"]').click();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    expect((await rideState(page)).horse.speed).toBeGreaterThan(0.5);
  });

  test('pause menu: Esc continues, Tab stays inside, Space presses the focused button', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await expect(page.locator('[data-action="resume"]')).toBeFocused();
    // Tab never leaves the four buttons of the dialog
    for (let i = 0; i < 6; i += 1) await page.keyboard.press('Tab');
    expect(
      await page.evaluate(() => document.activeElement.closest('[data-overlay="pause"]') !== null),
    ).toBe(true);
    // Esc in the pause menu continues the ride
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    // Space on a focused menu button is not swallowed by the game: it presses the button
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await expect(page.locator('[data-action="resume"]')).toBeFocused();
    await page.keyboard.press('Space');
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
    // while the ride is paused (and in the settings on top of it) keys do nothing in the game
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('.panel-settings')).toBeVisible();
    await page.keyboard.down('w');
    await page.keyboard.down('ArrowLeft');
    await page.keyboard.up('ArrowLeft');
    await page.keyboard.up('w');
    await page.locator('.panel-settings [data-action="back"]').click();
    expect((await rideState(page)).horse.speed).toBe(0);
  });

  test('pause menu: restart puts the horse back, "to menu" leaves, settings keep the pause', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    const start = await startFreeRide(page);
    const keys = createKeys(page);
    await keys.set('w', true);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.z > -25);
    await keys.releaseAll();
    await page.keyboard.press('Escape');
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('.panel-settings')).toBeVisible();
    // from the pause menu there is no "delete progress" (SRT-005)
    await expect(page.locator('[data-action="reset"]')).toHaveCount(0);
    await page.locator('.panel-settings [data-action="back"]').click();
    await expect(page.locator('[data-overlay="pause"]')).toBeVisible();
    await page.locator('[data-action="restart"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride().horse.speed === 0);
    const restarted = await rideState(page);
    expect(restarted).toMatchObject({ paused: false });
    expect(restarted.horse).toMatchObject({ x: start.horse.x, z: start.horse.z, gait: 'halt' });
    await page.keyboard.press('Escape');
    await page.locator('[data-action="quit"]').click();
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();
    expect(await screenName(page)).toBe('menu');
  });

  test('C toggles the camera, the choice survives a reload, settings have no camera choice', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    expect((await startFreeRide(page)).cameraMode).toBe('follow');
    await page.keyboard.press('c');
    await page.waitForFunction(() => window.__zhfTest.ride().cameraMode === 'rider');
    expect((await storeSection(page, 'settings')).camera).toBe('rider');
    await page.reload();
    await page.locator('[data-entry="free"]').click();
    expect((await waitForRide(page)).cameraMode).toBe('rider');
    await page.keyboard.press('c');
    await page.waitForFunction(() => window.__zhfTest.ride().cameraMode === 'follow');
    await page.keyboard.press('Escape');
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('[data-name="camera"]')).toHaveCount(0);
  });

  test('the graphics setting offers automatic and three levels and applies the choice', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    // first start: automatic, with a level that fits the device (rule 4)
    const first = await storeSection(page, 'settings');
    await page.locator('[data-entry="settings"]').click();
    await expect(page.locator('[data-name="graphics"]')).toHaveCount(4);
    await expect(page.locator('[data-name="graphics"][data-value="auto"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(first.graphicsAuto).toBe(true);
    // a manual choice is saved, automatic is off, and it is used in the ride
    await page.locator('[data-name="graphics"][data-value="medium"]').click();
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: false,
      graphicsLevel: 'medium',
    });
    await page.locator('[data-action="back"]').click();
    expect((await startFreeRide(page)).graphicsLevel).toBe('medium');
    await page.reload();
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: false,
      graphicsLevel: 'medium',
    });
  });

  test('invalid saved graphics and camera values fall back without a crash', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: {
        ...NAMED,
        settings: { graphicsLevel: 'ultra', graphicsAuto: 'maybe', camera: 'sideways' },
      },
    });
    await startFreeRide(page);
    const settings = await storeSection(page, 'settings');
    expect(settings.camera).toBe('follow');
    expect(settings.graphicsAuto).toBe(true);
    expect(['low', 'medium', 'high']).toContain(settings.graphicsLevel);
    expect(watch.errors).toEqual([]);
  });

  test('a first start without a graphics level in the save picks one', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { lang: 'de' } },
    });
    await startFreeRide(page);
    expect(['low', 'medium', 'high']).toContain(
      (await storeSection(page, 'settings')).graphicsLevel,
    );
  });
});

test.describe('touch controls (SRT-002)', () => {
  test.use({ viewport: { width: 900, height: 420 }, hasTouch: true, isMobile: true });

  test('joystick, gallop, jump, pause and camera are visible and at least 44x44 px', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName);
    expect(await page.evaluate(() => window.__zhfTest.touchMode())).toBe(true);
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    for (const selector of [
      '[data-control="joystick"]',
      '.touch-gallop',
      '.touch-jump',
      '.touch-pause',
      '.touch-camera',
    ]) {
      const box = await page.locator(selector).boundingBox();
      expect(box, selector).not.toBeNull();
      expect(box.width, `${selector} width`).toBeGreaterThanOrEqual(44);
      expect(box.height, `${selector} height`).toBeGreaterThanOrEqual(44);
    }
    // "Gallop" shows whether it is on, and the horse starts with it off (rule 39)
    await expect(page.locator('.touch-gallop')).toHaveAttribute('aria-pressed', 'false');
    await page.locator('.touch-gallop').tap();
    await expect(page.locator('.touch-gallop')).toHaveAttribute('aria-pressed', 'true');
    // the pause button pauses; the pause menu is usable by touch
    await page.locator('.touch-pause').tap();
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await expect(page.locator('[data-action="resume"]')).toBeVisible();
    // the camera button toggles
    await page.locator('[data-action="resume"]').tap();
    await page.locator('.touch-camera').tap();
    await page.waitForFunction(() => window.__zhfTest.ride().cameraMode === 'rider');
  });

  test('dragging the joystick rides: speed rises, the horse turns, no page errors', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName !== 'chromium', 'CDP touch input needs Chromium');
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="free"]').tap();
    const start = await waitForRide(page, 'free');
    expect(start.horse.speed).toBe(0);
    const box = await page.locator('[data-control="joystick"]').boundingBox();
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    const finger = await createFinger(page);

    // push forward and to the right: the horse speeds up and turns right (heading decreases)
    await finger.down(cx, cy);
    await finger.move(cx + 10, cy - 10);
    await finger.move(cx + 40, cy - 60);
    await finger.move(cx + 45, cy - 62);
    await page.waitForFunction(() => window.__zhfTest.ride().horse.speed > 0.5, null, {
      polling: 100,
    });
    await page.waitForFunction(() => window.__zhfTest.ride().horse.heading < -0.05, null, {
      polling: 100,
    });
    const moving = await rideState(page);
    expect(moving.horse.gait).not.toBe('halt');

    // the stick released: steering and throttle stop (the speed stays, like after releasing W)
    await finger.up();
    const frames = (n) =>
      page.evaluate(
        (count) =>
          new Promise((resolve) => {
            let left = count;
            const tick = () => (--left <= 0 ? resolve() : requestAnimationFrame(tick));
            requestAnimationFrame(tick);
          }),
        n,
      );
    await frames(6); // the turn rate eases out
    const released = await rideState(page);
    await frames(6);
    const later = await rideState(page);
    expect(Math.abs(later.horse.heading - released.horse.heading)).toBeLessThan(0.03);
    expect(Math.abs(later.horse.speed - released.horse.speed)).toBeLessThan(0.3);

    // pulling the stick down brakes
    await finger.down(cx, cy);
    await finger.move(cx, cy + 20);
    await finger.move(cx, cy + 60);
    await page.waitForFunction(
      (before) => window.__zhfTest.ride().horse.speed < before - 0.3,
      later.horse.speed,
      { polling: 100 },
    );
    await finger.up();
    expect(watch.errors).toEqual([]);
  });

  test('turning to portrait pauses the ride and shows the rotate notice', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    await page.setViewportSize({ width: 420, height: 900 });
    await expect(page.locator('[data-notice="rotate"]')).toBeVisible();
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.setViewportSize({ width: 900, height: 420 });
    await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
    // still paused until "Continue"
    expect((await rideState(page)).paused).toBe(true);
    await page.locator('[data-action="resume"]').tap();
    await page.waitForFunction(() => !window.__zhfTest.ride().paused);
  });

  test('menus fit the low landscape screen and every button is at least 44 px high', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName);
    const heights = await page.evaluate(() =>
      [...document.querySelectorAll('[data-screen="menu"] button')].map((b) => [
        b.getBoundingClientRect().height,
        b.getBoundingClientRect().bottom,
      ]),
    );
    expect(heights).toHaveLength(5);
    for (const [height, bottom] of heights) {
      expect(height).toBeGreaterThanOrEqual(44);
      expect(bottom).toBeLessThanOrEqual(420);
    }
  });
});

test.describe('no touch controls without touch mode', () => {
  test('the desktop ride has no touch controls, only the pause hint', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    await expect(page.locator('.touch-controls')).toBeHidden();
    await expect(page.locator('.ride-hint')).toBeVisible();
  });
});

test.describe('jumping in free mode (SRT-003)', () => {
  test('a jump over the cross counts once and is saved at once; the aid is shown', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName);
    const ride = await startFreeRide(page);
    // the free-mode layout has all four kinds, undirected, and the aid is on by default (rule 42)
    const kinds = ride.obstacles.flatMap((o) => o.elements.map((e) => e.kind));
    expect(new Set(kinds)).toEqual(new Set(['cross', 'vertical', 'oxer']));
    expect(ride.obstacles.some((o) => o.elements.length === 2)).toBe(true);
    expect(ride.obstacles.every((o) => o.number === null && !o.directed)).toBe(true);
    expect((await storeSection(page, 'progress')).jumps).toBe(0);

    const { sawAid } = await jumpOverCross(page);
    expect(sawAid).toBe(true);
    // the jump is counted and saved immediately (also visible after a reload)
    await expect.poll(async () => (await storeSection(page, 'progress')).jumps).toBe(1);
    const saved = await page.evaluate(
      (key) => JSON.parse(localStorage.getItem(key)).progress.jumps,
      'zoes-horse-farm.save',
    );
    expect(saved).toBe(1);
    await page.reload();
    await page.locator('[data-screen="menu"]').waitFor();
    expect((await storeSection(page, 'progress')).jumps).toBe(1);
  });

  test('both jump-aid settings are offered and kept after a reload', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    expect(await storeSection(page, 'settings')).toMatchObject({ aidFree: true, aidCourse: false });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-name="aidFree"]').click();
    await page.locator('[data-name="aidCourse"]').click();
    await page.reload();
    expect(await storeSection(page, 'settings')).toMatchObject({ aidFree: false, aidCourse: true });
    await page.locator('[data-entry="settings"]').click();
    await expect(page.locator('[data-name="aidFree"]')).toHaveAttribute('aria-checked', 'false');
    await expect(page.locator('[data-name="aidCourse"]')).toHaveAttribute('aria-checked', 'true');
  });
});
