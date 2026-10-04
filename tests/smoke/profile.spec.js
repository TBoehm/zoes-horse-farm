// SRT-005 in the browser: name question, "My horse", badges overview and "Delete progress".
import { expect, test } from '@playwright/test';
import {
  jumpOverCross,
  NAMED,
  openGame,
  openGameMenu,
  startFreeRide,
  storeSection,
  waitForRide,
  watchPage,
  webglOrSkip,
} from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

test.describe('name question (first start)', () => {
  test('appears before the menu, rejects invalid names and accepts a valid one', async ({
    page,
    browserName,
  }) => {
    await openGame(page);
    await webglOrSkip(page, test, browserName);
    const ok = page.locator('[data-action="ok"]');
    const input = page.locator('[data-field="horseName"]');
    await expect(page.locator('[data-screen="namePrompt"]')).toBeVisible();
    await expect(page.locator('[data-screen="menu"]')).toHaveCount(0);
    await expect(ok).toBeDisabled();
    await input.fill('   ');
    await expect(ok).toBeDisabled();
    await input.fill('A'.repeat(17));
    await expect(ok).toBeDisabled();
    await input.fill('  Sternchen  ');
    await expect(ok).toBeEnabled();
    // the question comes back after a reload as long as it is unanswered
    await page.reload();
    await expect(page.locator('[data-screen="namePrompt"]')).toBeVisible();
    await input.fill('  Sternchen  ');
    await ok.click();
    await expect(page.locator('[data-field="greeting"]')).toContainText('Sternchen');
    // spaces around the name are removed; the answer is saved
    expect(await storeSection(page, 'horse')).toMatchObject({
      name: 'Sternchen',
      nameAnswered: true,
    });
    await page.reload();
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();
  });

  test('skipping gives the default name, which follows the language', async ({
    page,
    browserName,
  }) => {
    await openGame(page);
    await webglOrSkip(page, test, browserName);
    await page.locator('[data-action="skip"]').click();
    const greeting = page.locator('[data-field="greeting"]');
    await expect(greeting).toContainText('Flash');
    // the default name changes with the language as long as there is no own name
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-name="lang"][data-value="de"]').click();
    await page.locator('[data-action="back"]').click();
    await expect(greeting).toContainText('Blitz');
    expect((await storeSection(page, 'horse')).name).toBeNull();
    await page.reload();
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();
  });
});

test.describe('name question: language default name', () => {
  test('typing the default name keeps it following the language (name stays empty)', async ({
    page,
    browserName,
  }) => {
    await openGame(page, { lang: 'en' });
    await webglOrSkip(page, test, browserName);
    await page.locator('[data-field="horseName"]').fill('Flash');
    await page.locator('[data-action="ok"]').click();
    await expect(page.locator('[data-field="greeting"]')).toContainText('Flash');
    expect(await storeSection(page, 'horse')).toMatchObject({ name: null, nameAnswered: true });
  });
});

test.describe('main menu entries', () => {
  test('all entries of rule 53 in order, with the horse name in the greeting', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    const ids = await page
      .locator('[data-screen="menu"] [data-entry]')
      .evaluateAll((nodes) => nodes.map((n) => n.dataset.entry));
    expect(ids).toEqual(['courses', 'free', 'horse', 'badges', 'settings']);
    await expect(page.locator('[data-field="greeting"]')).toContainText('Blitz');
  });
});

test.describe('my horse', () => {
  test('coat and marking can be changed, are saved and used when riding', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="horse"]').click();
    await expect(page.locator('[data-name="coat"]')).toHaveCount(5);
    await expect(page.locator('[data-name="marking"]')).toHaveCount(4);
    await expect(page.locator('[data-name="coat"][data-value="bay"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await page.locator('[data-name="coat"][data-value="grey"]').click();
    await page.locator('[data-name="marking"][data-value="blaze"]').click();
    expect(await storeSection(page, 'horse')).toMatchObject({ coat: 'grey', marking: 'blaze' });
    await page.reload();
    await page.locator('[data-entry="horse"]').click();
    await expect(page.locator('[data-name="coat"][data-value="grey"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await expect(page.locator('[data-name="marking"][data-value="blaze"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await page.locator('[data-action="back"]').click();
    await startFreeRide(page);
    expect(await page.evaluate(() => window.__zhfTest.ride().mode)).toBe('free');
    expect(watch.errors).toEqual([]);
  });

  test('a changed name is saved; an invalid one keeps the last valid name', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="horse"]').click();
    const input = page.locator('[data-field="horseName"]');
    await input.fill('  Donner ');
    await expect.poll(async () => (await storeSection(page, 'horse')).name).toBe('Donner');
    await input.fill('   ');
    await input.fill('X'.repeat(17));
    expect((await storeSection(page, 'horse')).name).toBe('Donner');
    await page.locator('[data-action="back"]').click();
    await expect(page.locator('[data-field="greeting"]')).toContainText('Donner');
  });
});

test.describe('my horse on a phone', () => {
  test.use({ viewport: { width: 568, height: 320 } });

  test('the name field is a full-width row and shows a hint while the name is invalid', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'de' });
    await page.locator('[data-entry="horse"]').click();
    const input = page.locator('[data-field="horseName"]');
    const hint = page.locator('.setting-name .field-hint');
    await expect(hint).toBeHidden();
    expect((await input.boundingBox()).width).toBeGreaterThanOrEqual(150);
    // an invalid name: the hint "1 bis 16 Zeichen" and a red field, the old name stays
    await input.fill('   ');
    await expect(hint).toBeVisible();
    await expect(hint).toHaveText('1 bis 16 Zeichen');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    expect((await storeSection(page, 'horse')).name).toBe('Blitz');
    // a valid name clears it again
    await input.fill('Donner');
    await expect(hint).toBeHidden();
    await expect(input).toHaveAttribute('aria-invalid', 'false');
    await expect.poll(async () => (await storeSection(page, 'horse')).name).toBe('Donner');
  });

  test('the head marking is called "Kopfabzeichen" (German)', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { lang: 'de' });
    await page.locator('[data-entry="horse"]').click();
    await expect(page.getByRole('radiogroup', { name: 'Kopfabzeichen' })).toHaveCount(1);
  });
});

test.describe('badges overview', () => {
  test('shows all 8 badges; earned ones with a date, missing ones with their condition', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: {
        ...NAMED,
        progress: { jumps: 5, badges: { firstJump: '2026-10-01T10:00:00.000Z' } },
      },
    });
    await page.locator('[data-entry="badges"]').click();
    await expect(page.locator('.badge-card')).toHaveCount(8);
    await expect(page.locator('.badge-card.is-earned')).toHaveCount(1);
    await expect(page.locator('.badge-card.is-missing')).toHaveCount(7);
    await expect(page.locator('[data-badge="firstJump"]')).toContainText('2026');
    await expect(page.locator('[data-badge="jumpMouse"] .badge-condition')).not.toBeEmpty();
  });

  test('oxer and combination badges say that it must happen in a course ride up to the finish', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'de' });
    await page.locator('[data-entry="badges"]').click();
    for (const id of ['oxerPro', 'comboPro']) {
      const condition = page.locator(`[data-badge="${id}"] .badge-condition`);
      await expect(condition).toContainText('im Parcours');
      await expect(condition).toContainText('komm ins Ziel');
    }
  });
});

test.describe('badge toast', () => {
  test('is small, sits at the bottom centre and goes away again', async ({ page, browserName }) => {
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    await jumpOverCross(page);
    // the first counted jump gives the first badge
    const toast = page.locator('.badge-toast[data-toast="firstJump"]');
    await expect(toast).toBeVisible();
    const box = await toast.boundingBox();
    const view = page.viewportSize();
    // at the bottom, centred (the course HUD sits top left)
    expect(box.y + box.height).toBeGreaterThan(view.height * 0.6);
    expect(Math.abs(box.x + box.width / 2 - view.width / 2)).toBeLessThan(4);
    expect(box.height).toBeLessThanOrEqual(48);
    const emblem = await toast.locator('.badge-emblem').boundingBox();
    expect(emblem.width).toBeLessThanOrEqual(40);
    await expect(toast).toHaveCount(0, { timeout: 6000 });
  });
});

test.describe('badge toast on a phone (touch mode)', () => {
  test.use({ viewport: { width: 568, height: 320 }, hasTouch: true, isMobile: true });

  test('stays in the gap between the joystick and the buttons and covers no touch control', async ({
    page,
    browserName,
  }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    test.setTimeout(150_000);
    await openGameMenu(page, test, browserName, { lang: 'de' });
    expect(await page.evaluate(() => window.__zhfTest.touchMode())).toBe(true);
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    await jumpOverCross(page);
    const toast = page.locator('.badge-toast[data-toast="firstJump"]');
    await expect(toast).toBeVisible();
    // measure once the entry animation is over
    await page.evaluate(() => Promise.all(document.getAnimations().map((a) => a.finished)));
    const toastBox = await toast.boundingBox();
    const view = page.viewportSize();
    expect(toastBox.x).toBeGreaterThanOrEqual(0);
    expect(toastBox.x + toastBox.width).toBeLessThanOrEqual(view.width);
    expect(toastBox.y + toastBox.height).toBeLessThanOrEqual(view.height);
    // toast box ∩ every touch control box = ∅ (joystick area, Gallop, Jump, Camera, Pause)
    const controls = await page
      .locator('.touch-btn, .touch-left')
      .evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()));
    expect(controls.length).toBeGreaterThanOrEqual(5);
    for (const c of controls) {
      const overlaps =
        toastBox.x < c.right &&
        toastBox.x + toastBox.width > c.left &&
        toastBox.y < c.bottom &&
        toastBox.y + toastBox.height > c.top;
      expect(
        overlaps,
        `toast overlaps a control at ${Math.round(c.left)},${Math.round(c.top)}`,
      ).toBe(false);
    }
  });
});

test.describe('delete progress', () => {
  const SAVE = {
    ...NAMED,
    settings: { lang: 'en', graphicsAuto: false, graphicsLevel: 'low', musicVolume: 0.8 },
    horse: { nameAnswered: true, name: 'Blitz', coat: 'black', marking: 'snip' },
    progress: {
      unlocked: 3,
      courses: { 1: { faults: 0, timeCs: 3000, stars: 3 } },
      jumps: 150,
      finishedRides: 4,
      badges: { firstJump: '2026-10-01T10:00:00.000Z' },
    },
  };

  test('only in the settings of the main menu, only after confirming, keeps settings and horse', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { save: SAVE });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-action="reset"]').click();
    await expect(page.locator('[data-dialog="reset"]')).toBeVisible();
    // the safe choice has the focus
    await expect(page.locator('[data-action="reset-cancel"]')).toBeFocused();
    // "Cancel" changes nothing
    await page.locator('[data-action="reset-cancel"]').click();
    expect((await storeSection(page, 'progress')).jumps).toBe(150);
    await page.locator('[data-action="reset"]').click();
    await page.locator('[data-action="reset-confirm"]').click();
    expect(await storeSection(page, 'progress')).toMatchObject({
      unlocked: 1,
      courses: {},
      jumps: 0,
      finishedRides: 0,
      badges: {},
    });
    // settings and the horse stay
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsLevel: 'low',
      graphicsAuto: false,
      musicVolume: 0.8,
    });
    expect(await storeSection(page, 'horse')).toMatchObject({
      name: 'Blitz',
      coat: 'black',
      marking: 'snip',
    });
    // the name question does not come back
    await page.reload();
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();
  });

  test('the confirmation is fully visible on a low phone screen (568x320)', async ({
    page,
    browserName,
  }) => {
    await page.setViewportSize({ width: 568, height: 320 });
    await openGameMenu(page, test, browserName, { save: SAVE });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-action="reset"]').click();
    const body = await page.locator('.panel-settings .settings-body').boundingBox();
    // the question and both buttons are inside the visible part of the scrolling settings list
    for (const selector of [
      '[data-dialog="reset"]',
      '[data-action="reset-confirm"]',
      '[data-action="reset-cancel"]',
    ]) {
      const box = await page.locator(selector).boundingBox();
      expect(box.y, selector).toBeGreaterThanOrEqual(body.y - 1);
      expect(box.y + box.height, selector).toBeLessThanOrEqual(body.y + body.height + 1);
    }
    // nothing of the settings list sticks out sideways
    const overflow = await page.evaluate(() => {
      const el = document.querySelector('.panel-settings .settings-body');
      return { scroll: el.scrollWidth, client: el.clientWidth };
    });
    expect(overflow.scroll).toBeLessThanOrEqual(overflow.client);
  });

  test('the settings from the pause menu have no delete button', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { save: SAVE });
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('.panel-settings')).toBeVisible();
    await expect(page.locator('[data-action="reset"]')).toHaveCount(0);
  });
});
