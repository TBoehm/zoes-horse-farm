// SRT-006 in the browser: no sound before the first interaction, no audio files, settings for
// music and effects, music only in menus. Real listening is not possible here; the tests check the
// state of the audio service through the hook.
import { expect, test } from '@playwright/test';
import {
  audioState,
  createKeys,
  NAMED,
  openGameMenu,
  setTabHidden,
  sfxCounts,
  startFreeRide,
  storeSection,
  watchPage,
} from './helpers.js';

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

  test('the audio context really runs after the first gesture (precondition of the cases below)', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click();
    await expect.poll(async () => (await audioState(page)).running).toBe(true);
    expect((await audioState(page)).sfxCounts).toMatchObject({ startSignal: 0, finishSignal: 0 });
  });
});

// Effects are counted in the audio state (sfxCounts), so that the smoke tests can see which signals
// were really played without listening.
test.describe('start signal and effects', () => {
  const openCourse = async (page, test, browserName) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    await expect(page.locator('canvas.course-plan')).toBeVisible();
    await expect.poll(async () => (await audioState(page)).running).toBe(true);
  };

  test('"Go" plays the start signal once; start again and the pause menu do not play it', async ({
    page,
    browserName,
  }) => {
    await openCourse(page, test, browserName);
    expect((await sfxCounts(page)).startSignal).toBe(0);
    await page.locator('[data-action="go"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride());
    expect((await sfxCounts(page)).startSignal).toBe(1);
    // pause, "Start again": back to the pre-start of the course, no new signal
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.locator('[data-action="restart"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride().hud.phase === 'prestart');
    await page.waitForTimeout(400);
    expect((await sfxCounts(page)).startSignal).toBe(1);
  });

  test('"Again" on the results screen opens the pre-start card; the signal only comes with "Go"', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, progress: { unlocked: 2 } },
    });
    await page.evaluate(() =>
      window.__zhfTest.go('results', {
        courseId: 1,
        result: {
          courseId: 1,
          timeCs: 4827,
          faults: { knockdowns: 0, refusals: 0, timeFaults: 0, total: 0 },
          stars: 3,
        },
        isNewBest: true,
        unlockedCourse: null,
        awarded: [],
      }),
    );
    await page.locator('[data-action="again"]').click(); // also unlocks the audio
    await expect(page.locator('canvas.course-plan')).toBeVisible();
    await expect.poll(async () => (await audioState(page)).running).toBe(true);
    await page.waitForTimeout(400);
    expect((await sfxCounts(page)).startSignal).toBe(0);
    await page.locator('[data-action="go"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride());
    expect((await sfxCounts(page)).startSignal).toBe(1);
  });

  test('no effects while the game is paused', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="free"]').click(); // also unlocks the audio
    await page.waitForFunction(() => window.__zhfTest.ride());
    await expect.poll(async () => (await audioState(page)).running).toBe(true);
    const keys = createKeys(page);
    await keys.set('w', true);
    // the horse walks: hoofbeats are played
    await expect
      .poll(async () => (await sfxCounts(page)).hoof, { timeout: 20_000 })
      .toBeGreaterThan(0);
    await keys.releaseAll();
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    const paused = await sfxCounts(page);
    await page.waitForTimeout(800);
    expect(await sfxCounts(page)).toEqual(paused);
    // settings from the pause menu do not play effects either
    await page.locator('[data-action="settings"]').click();
    await page.waitForTimeout(300);
    expect(await sfxCounts(page)).toEqual(paused);
  });

  test('a tab in the background plays no music; back in the menu it plays again', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click(); // unlocks the audio, menu music wanted
    await expect.poll(async () => (await audioState(page)).musicPlaying).toBe(true);
    await setTabHidden(page, true);
    await expect.poll(async () => (await audioState(page)).musicPlaying).toBe(false);
    expect(await audioState(page)).toMatchObject({ hidden: true, musicWanted: true });
    await setTabHidden(page, false);
    await expect.poll(async () => (await audioState(page)).musicPlaying).toBe(true);
    expect(await audioState(page)).toMatchObject({ hidden: false, running: true });
  });
});

test.describe('sound settings and deleting progress', () => {
  test('all four sound fields are kept after "Delete progress"', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName, {
      save: {
        ...NAMED,
        settings: { musicVolume: 0.2, musicMuted: true, sfxVolume: 0.9, sfxMuted: true },
        progress: { unlocked: 3, jumps: 12, finishedRides: 2 },
      },
    });
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-action="reset"]').click();
    await page.locator('[data-action="reset-confirm"]').click();
    expect(await storeSection(page, 'progress')).toMatchObject({ unlocked: 1, jumps: 0 });
    const expected = { musicVolume: 0.2, musicMuted: true, sfxVolume: 0.9, sfxMuted: true };
    expect(await storeSection(page, 'settings')).toMatchObject(expected);
    await page.reload();
    expect(await storeSection(page, 'settings')).toMatchObject(expected);
  });
});
