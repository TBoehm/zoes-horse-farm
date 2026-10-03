// SRT-006 in the browser: no sound before the first interaction, no audio files, settings for
// music and effects, music only in menus. Real listening is not possible here; the tests check the
// state of the audio service through the hook.
import { expect, test } from '@playwright/test';
import { audioState, openGameMenu, startFreeRide, storeSection, watchPage } from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

test.describe('audio', () => {
  test('nothing starts before the first interaction, then the sound is unlocked', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName);
    const before = await audioState(page);
    expect(before).toMatchObject({ unlocked: false, musicPlaying: false });
    // the menu wants music, but it only starts after a user gesture (rule 52)
    expect(before.musicWanted).toBe(true);
    await page.locator('[data-entry="settings"]').click();
    await expect.poll(async () => (await audioState(page)).unlocked).toBe(true);
    await page.waitForLoadState('networkidle');
    // everything is synthesized: no audio, media or sound files were requested
    expect(watch.forbidden).toEqual([]);
    expect(watch.errors).toEqual([]);
  });

  test('music in the menus, none in the free ride, the pause menu or its settings', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click();
    expect((await audioState(page)).musicWanted).toBe(true);
    await page.locator('[data-action="back"]').click();
    await startFreeRide(page);
    expect((await audioState(page)).musicWanted).toBe(false);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    expect(await audioState(page)).toMatchObject({ musicWanted: false, paused: true });
    // settings opened from the pause menu: still no music
    await page.locator('[data-action="settings"]').click();
    await expect(page.locator('.panel-settings')).toBeVisible();
    expect((await audioState(page)).musicWanted).toBe(false);
    await page.locator('.panel-settings [data-action="back"]').click();
    await page.locator('[data-action="quit"]').click();
    await expect.poll(async () => (await audioState(page)).musicWanted).toBe(true);
  });

  test('music is also wanted on the pre-start card, none in the pre-start ride', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    await expect(page.locator('canvas.course-plan')).toBeVisible();
    expect((await audioState(page)).musicWanted).toBe(true);
    await page.locator('[data-action="go"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride());
    expect((await audioState(page)).musicWanted).toBe(false);
  });

  test('volume and mute of both channels are saved separately and survive a reload', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    // first start: both channels on at medium volume (rule 52)
    expect(await storeSection(page, 'settings')).toMatchObject({
      musicVolume: 0.5,
      musicMuted: false,
      sfxVolume: 0.5,
      sfxMuted: false,
    });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-name="musicVolume"]').fill('20');
    await page.locator('[data-name="sfxVolume"]').fill('90');
    await page.locator('[data-name="musicMuted"]').click();
    expect(await storeSection(page, 'settings')).toMatchObject({
      musicVolume: 0.2,
      musicMuted: true,
      sfxVolume: 0.9,
      sfxMuted: false,
    });
    await page.reload();
    expect(await storeSection(page, 'settings')).toMatchObject({
      musicVolume: 0.2,
      musicMuted: true,
      sfxVolume: 0.9,
      sfxMuted: false,
    });
    await page.locator('[data-entry="settings"]').click();
    await expect(page.locator('[data-name="musicVolume"]')).toHaveValue('20');
    await expect(page.locator('[data-name="musicMuted"]')).toHaveAttribute('aria-checked', 'false');
    await expect(page.locator('[data-name="sfxMuted"]')).toHaveAttribute('aria-checked', 'true');
    // un-muting brings back the volume that was set before
    await page.locator('[data-name="musicMuted"]').click();
    expect(await storeSection(page, 'settings')).toMatchObject({
      musicVolume: 0.2,
      musicMuted: false,
    });
  });

  test('the volume sliders and switches are large enough for touch', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click();
    for (const name of ['musicMuted', 'sfxMuted']) {
      const box = await page.locator(`[data-name="${name}"]`).boundingBox();
      expect(box.width).toBeGreaterThanOrEqual(44);
      expect(box.height).toBeGreaterThanOrEqual(44);
    }
  });
});
