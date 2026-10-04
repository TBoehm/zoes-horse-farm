// SRT-013 in the browser, as smoke tests: the crash guard (concept rule 4). A leftover "rendering"
// mark in the save stands for a game that ended unexpectedly during the 3D picture (the browser
// killed the tab). The kill is simulated by seeding that mark before the page loads. Normal exits
// must not leave the mark behind. Chromium only. The rules (own and foreign tab marks, grace times,
// blocked levels, hint once, old saves, the debug line) are unit-tested in
// src/application/crash-guard.test.js and src/adapters/ui/debug-display.test.js.
import { expect, test } from '@playwright/test';
import { NAMED, openGameMenu, startFreeRide, storeSection, watchPage } from './helpers.js';

// A small window keeps the software renderer of the CI browser fast enough
test.use({ viewport: { width: 640, height: 400 } });

// Seconds between the start mark (1000) and the last heartbeat (8400) → 7 s into the session
const rendering = (level, auto) => ({
  rendering: true,
  level,
  auto,
  since: 1000,
  lastSeen: 8400,
});

const seed = ({ level, auto, guard }) => ({
  ...NAMED,
  settings: { graphicsAuto: auto, graphicsLevel: level },
  crashGuard: guard,
});

// the toast text (it stays in the element after it faded out, which a slow frame loop can cause)
const feedback = (page) => page.locator('.ride-feedback');
const guardState = (page) => storeSection(page, 'crashGuard');

async function quitRide(page) {
  await page.keyboard.press('Escape');
  await page.locator('[data-action="quit"]').click();
  await page.locator('[data-screen="menu"]').waitFor();
}

test.describe('crash guard (rule 4)', () => {
  test('after a crash with Automatic on, the level is low and stays automatic', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      lang: 'en',
      save: seed({ level: 'medium', auto: true, guard: rendering('medium', true) }),
    });
    const settings = await storeSection(page, 'settings');
    expect(settings).toMatchObject({ graphicsAuto: true, graphicsLevel: 'low' });
    const guard = await guardState(page);
    expect(guard.rendering).toBe(false);
    expect(guard.blockedLevels).toEqual(['medium']);
    expect(guard.lastCrash).toMatchObject({ level: 'medium', auto: true, seconds: 7 });
    expect(guard.hintPending).toBe(false);
    // no hint with Automatic: the level was lowered by itself
    await startFreeRide(page);
    await expect(feedback(page)).toHaveText('');
    expect(watch.errors).toEqual([]);
  });

  test('after a crash at a manual level above low, the next ride shows the hint once', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      lang: 'en',
      save: seed({ level: 'high', auto: false, guard: rendering('high', false) }),
    });
    // the level stays; only the hint is flagged
    expect(await storeSection(page, 'settings')).toMatchObject({
      graphicsAuto: false,
      graphicsLevel: 'high',
    });
    expect((await guardState(page)).hintPending).toBe(true);
    await startFreeRide(page);
    await expect(feedback(page)).toContainText('Pick a lower level in the settings');
    expect((await guardState(page)).hintPending).toBe(false);
  });

  test('the ride marks rendering while it draws and clean when it ends', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'en' });
    expect((await guardState(page)).rendering).toBe(false);
    await startFreeRide(page);
    expect(await guardState(page)).toMatchObject({ rendering: true });
    await quitRide(page);
    expect((await guardState(page)).rendering).toBe(false);
  });

  test('a normal reload during the ride is not a crash (pagehide)', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'en' });
    await startFreeRide(page);
    expect((await guardState(page)).rendering).toBe(true);
    await page.reload();
    await page.locator('[data-screen="menu"]').waitFor();
    const guard = await guardState(page);
    expect(guard.lastCrash).toBeNull();
    expect(guard.blockedLevels).toEqual([]);
  });
});
