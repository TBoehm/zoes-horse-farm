// SRT-012 in the browser: controls help on the first start, from the main menu and from the pause
// menu (rule 56).
import { expect, test } from '@playwright/test';
import {
  openGame,
  openGameMenu,
  rideState,
  screenName,
  startFreeRide,
  storeSection,
  watchPage,
  webglOrSkip,
} from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

const help = (page) => page.locator('[data-screen="controlsHelp"]');
const menu = (page) => page.locator('[data-screen="menu"]');
const done = (page) => page.locator('[data-action="help-done"]');

test.describe('first start', () => {
  test('name question, then the controls help, then the menu; not again after a reload', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGame(page);
    await webglOrSkip(page, test, browserName);
    await page.locator('[data-action="skip"]').click();
    await expect(help(page)).toBeVisible();
    await expect(menu(page)).toHaveCount(0);
    // reloading before "Got it" shows the help again: it was not closed yet
    await page.reload();
    await expect(help(page)).toBeVisible();
    expect((await storeSection(page, 'settings')).controlsHelpSeen).toBe(false);
    await done(page).click();
    await expect(menu(page)).toBeVisible();
    expect((await storeSection(page, 'settings')).controlsHelpSeen).toBe(true);
    await page.reload();
    await expect(menu(page)).toBeVisible();
    await expect(help(page)).toHaveCount(0);
    expect(watch.errors).toEqual([]);
  });

  test('an existing save that never closed the help sees it once before the menu', async ({
    page,
    browserName,
  }) => {
    await openGame(page, {
      save: { horse: { nameAnswered: true, name: 'Blitz' } },
      helpSeen: false,
    });
    await webglOrSkip(page, test, browserName);
    await expect(help(page)).toBeVisible();
    await expect(page.locator('[data-screen="namePrompt"]')).toHaveCount(0);
    await done(page).click();
    await expect(menu(page)).toBeVisible();
    await expect(page.locator('[data-field="greeting"]')).toContainText('Blitz');
  });
});

test.describe('content and switch', () => {
  test('desktop: key caps first, the switch shows the touch controls and back', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'de' });
    await page.locator('[data-entry="help"]').click();
    const keyboard = page.locator('[data-name="helpMode"][data-value="keyboard"]');
    const touch = page.locator('[data-name="helpMode"][data-value="touch"]');
    await expect(keyboard).toHaveAttribute('aria-checked', 'true');
    await expect(touch).toHaveAttribute('aria-checked', 'false');
    // keyboard focus starts on the switch
    await expect(keyboard).toBeFocused();
    const rows = page.locator('[data-screen="controlsHelp"] [data-help]');
    expect(await rows.evaluateAll((nodes) => nodes.map((n) => n.dataset.help))).toEqual([
      'faster',
      'slower',
      'steer',
      'gallop',
      'jump',
      'camera',
      'pause',
    ]);
    const caps = await help(page)
      .locator('kbd.keycap')
      .evaluateAll((nodes) => nodes.map((n) => n.textContent));
    expect(caps).toEqual([
      'W',
      '↑',
      'S',
      '↓',
      'A',
      'D',
      '←',
      '→',
      'Shift',
      'Leertaste',
      'C',
      'Esc',
    ]);

    await touch.click();
    await expect(touch).toHaveAttribute('aria-checked', 'true');
    expect(await rows.evaluateAll((nodes) => nodes.map((n) => n.dataset.help))).toEqual([
      'speed',
      'steer',
      'gallop',
      'jump',
      'cameraPause',
    ]);
    await expect(help(page).locator('kbd')).toHaveCount(0);
    await expect(help(page).locator('.help-glyph-jump')).toHaveText('Springen');
    await keyboard.click();
    await expect(help(page).locator('kbd.keycap').first()).toHaveText('W');
  });

  test('shows the texts of the chosen language, for the key caps and the touch symbols', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'en' });
    await page.locator('[data-entry="help"]').click();
    await expect(help(page).locator('h2')).toHaveText('Controls');
    await expect(done(page)).toHaveText('Got it');
    await page.locator('[data-name="helpMode"][data-value="touch"]').click();
    await expect(help(page).locator('.help-glyph-jump')).toHaveText('Jump');
  });

  test('every control is at least 44 px and the help fits a low phone screen', async ({
    page,
    browserName,
  }) => {
    await page.setViewportSize({ width: 568, height: 320 });
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="help"]').click();
    for (const mode of ['keyboard', 'touch']) {
      await page.locator(`[data-name="helpMode"][data-value="${mode}"]`).click();
      const boxes = await help(page)
        .locator('button')
        .evaluateAll((nodes) =>
          nodes.map((n) => {
            const r = n.getBoundingClientRect();
            return { w: r.width, h: r.height, bottom: r.bottom };
          }),
        );
      for (const box of boxes) {
        expect(box.w).toBeGreaterThanOrEqual(44);
        expect(box.h).toBeGreaterThanOrEqual(44);
      }
      // "Got it" stays inside the window (only the list scrolls)
      const doneBox = await done(page).boundingBox();
      expect(doneBox.y + doneBox.height).toBeLessThanOrEqual(320);
    }
  });
});

test.describe('touch device', () => {
  test.use({ viewport: { width: 800, height: 400 }, hasTouch: true, isMobile: true });
  test('starts on the touch controls', async ({ page, browserName }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="help"]').click();
    await expect(page.locator('[data-name="helpMode"][data-value="touch"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await expect(help(page).locator('.help-glyph-gallop')).toBeVisible();
  });
});

test.describe('open again', () => {
  test('main menu entry opens the help, "Got it" leads back to the menu', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="help"]').click();
    await expect(help(page)).toBeVisible();
    await done(page).click();
    await expect(menu(page)).toBeVisible();
    await expect(help(page)).toHaveCount(0);
  });

  test('pause menu: the help opens on top, "Got it" returns to the open pause menu', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.locator('[data-action="help"]').click();
    await expect(help(page)).toBeVisible();
    expect(await screenName(page)).toBe('controlsHelp');
    const before = await rideState(page);
    await done(page).click();
    await expect(help(page)).toHaveCount(0);
    expect(await screenName(page)).toBe('ride');
    await expect(page.locator('[data-overlay="pause"]')).toBeVisible();
    // the ride did not go on
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    );
    const after = await rideState(page);
    expect(after.paused).toBe(true);
    expect(after.horse.z).toBe(before.horse.z);
    expect(after.horse.x).toBe(before.horse.x);
    // focus is back in the pause menu
    expect(
      await page.evaluate(() => document.activeElement.closest('[data-overlay="pause"]') !== null),
    ).toBe(true);
  });
});
