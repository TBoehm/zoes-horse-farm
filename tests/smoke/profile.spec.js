// SRT-005 in the browser: name question, "My horse", badges overview and "Delete progress".
import { expect, test } from '@playwright/test';
import {
  NAMED,
  openGame,
  openGameMenu,
  startFreeRide,
  storeSection,
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

  test('the settings from the pause menu have no delete button', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, { save: SAVE });
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('.panel-settings')).toBeVisible();
    await expect(page.locator('[data-action="reset"]')).toHaveCount(0);
  });
});
