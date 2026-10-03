// SRT-004 (and the badge/progress part of SRT-005) in the browser: course selection, pre-start,
// HUD, pause, results and a complete ride of course 1 with a keyboard bot.
import { expect, test } from '@playwright/test';
import {
  NAMED,
  openGameMenu,
  rideCourseOne,
  rideState,
  screenName,
  sfxCounts,
  storeSection,
  waitForRide,
  watchPage,
} from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

const RESULT = {
  courseId: 1,
  result: {
    courseId: 1,
    timeCs: 4827,
    faults: { knockdowns: 2, refusals: 1, timeFaults: 3, total: 15 },
    stars: 1,
  },
  isNewBest: true,
  unlockedCourse: 2,
  awarded: ['clean'],
};

test.describe('course selection', () => {
  test('five cards, only course 1 is open at the start, a locked card cannot be started', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="courses"]').click();
    const cards = page.locator('.course-card');
    await expect(cards).toHaveCount(5);
    await expect(page.locator('.course-card.is-open')).toHaveCount(1);
    await expect(page.locator('[data-course="1"]')).toBeEnabled();
    for (const id of [2, 3, 4, 5]) {
      await expect(page.locator(`[data-course="${id}"]`)).toBeDisabled();
    }
    // even a forced click does nothing
    await page.locator('[data-course="2"]').dispatchEvent('click');
    expect(await screenName(page)).toBe('courseSelect');
    // nor does a forced screen change to a locked or unknown course: back to the selection
    for (const courseId of [3, 99]) {
      await page.evaluate((id) => window.__zhfTest.go('prestart', { courseId: id }), courseId);
      await expect(page.locator('.course-card')).toHaveCount(5);
      expect(await screenName(page)).toBe('courseSelect');
      expect(await page.evaluate(() => window.__zhfTest.stack())).toEqual(['courseSelect']);
    }
    await expect(page.locator('canvas.course-plan')).toHaveCount(0);
    // each card shows number and obstacle count
    await expect(page.locator('[data-course="1"]')).toContainText('4');
    await expect(page.locator('[data-course="5"]')).toContainText('8');
  });

  test('best result and stars of a saved game appear on the cards, the next course is open', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: {
        ...NAMED,
        progress: { unlocked: 2, courses: { 1: { faults: 4, timeCs: 5230, stars: 2 } } },
      },
    });
    await page.locator('[data-entry="courses"]').click();
    await expect(page.locator('.course-card.is-open')).toHaveCount(2);
    await expect(page.locator('[data-course="1"] [data-stars]')).toHaveAttribute('data-stars', '2');
    await expect(page.locator('[data-course="1"]')).toContainText('00:52.30');
  });
});

test.describe('pre-start, HUD and pause in a course', () => {
  test('plan, aid switch, Go, HUD, highlight, pause with "To courses", abort without credit', async ({
    page,
    browserName,
  }) => {
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    // pre-start card: plan, allowed time, aid switch (the same setting as in the settings)
    await expect(page.locator('canvas.course-plan')).toBeVisible();
    await expect(page.locator('[data-action="go"]')).toBeVisible();
    await expect(page.locator('[data-name="aidCourse"]')).toHaveAttribute('aria-checked', 'false');
    await page.locator('[data-name="aidCourse"]').click();
    expect((await storeSection(page, 'settings')).aidCourse).toBe(true);
    await page.locator('[data-action="go"]').click();

    const ride = await waitForRide(page, 'course');
    expect(ride.horse.gait).toBe('halt');
    expect(ride.hud).toMatchObject({ phase: 'prestart', nextLabel: 1, faults: 0, allowedS: 64 });
    expect(ride.highlight).toMatchObject({ number: 1 });
    expect(ride.lines.start).toBeTruthy();
    expect(ride.lines.finish).toBeTruthy();
    expect(ride.finishMarked).toBe(false);
    // the course obstacles are directed and numbered
    expect(ride.obstacles.map((o) => o.number)).toEqual([1, 2, 3, 4]);
    expect(ride.obstacles.every((o) => o.directed)).toBe(true);
    // the HUD shows allowed time and the next obstacle
    await expect(page.locator('.ride-hud')).toContainText('64');

    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await expect(page.locator('[data-action="quit"]')).toHaveText('To courses');
    // "Start again" goes to the pre-start of the same course
    await page.locator('[data-action="restart"]').click();
    await page.waitForFunction(() => window.__zhfTest.ride().hud.phase === 'prestart');
    // an aborted ride gives no credit (rule 40)
    await page.keyboard.press('Escape');
    await page.locator('[data-action="quit"]').click();
    await expect(page.locator('.course-card')).toHaveCount(5);
    expect(await storeSection(page, 'progress')).toMatchObject({
      unlocked: 1,
      finishedRides: 0,
      courses: {},
    });
    expect(watch.errors).toEqual([]);
    expect(watch.forbidden).toEqual([]);
  });
});

test.describe('aborting a course ride after the start', () => {
  test('after crossing the start line and a first jump: no credit, but the jump counts; the HUD shows mm:ss,hh', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(180_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { aidCourse: true } },
    });
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    await page.locator('[data-action="go"]').click();
    await waitForRide(page, 'course');
    // the time chip only shows once the start line is crossed
    await expect(page.locator('[data-chip="time"]')).toBeHidden();
    // ride over the start line to the first jump (the bot stops once obstacle 1 is behind us)
    await rideCourseOne(page, {
      stopWhen: (ride) => ride.hud.phase === 'riding' && ride.hud.nextLabel === 2,
    });
    const ride = await rideState(page);
    expect(ride.hud.phase).toBe('riding');
    await expect(page.locator('[data-hud="time"]')).toHaveText(/^\d\d:\d\d[,.]\d\d$/);
    // now abort: pause, "To courses"
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    await page.locator('[data-action="quit"]').click();
    await expect(page.locator('.course-card')).toHaveCount(5);
    const progress = await storeSection(page, 'progress');
    // no credit for an aborted ride (rule 40) ...
    expect(progress).toMatchObject({ unlocked: 1, finishedRides: 0, courses: {} });
    expect(progress.badges).toEqual({ firstJump: expect.any(String) });
    // ... but the jump that was made still counts
    expect(progress.jumps).toBeGreaterThanOrEqual(1);
    expect(watch.errors).toEqual([]);
  });
});

test.describe('results screen', () => {
  test('shows name, time, penalty points as count x points and the buttons (English)', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, progress: { unlocked: 2 } },
    });
    await page.evaluate((params) => window.__zhfTest.go('results', params), RESULT);
    await expect(page.locator('.panel-results')).toBeVisible();
    await expect(page.locator('.results-horse')).toContainText('Blitz');
    await expect(page.locator('[data-result="time"]')).toContainText('00:48.27');
    await expect(page.locator('[data-result="knockdowns"]')).toContainText('2 × 4 = 8');
    await expect(page.locator('[data-result="refusals"]')).toContainText('1 × 4 = 4');
    await expect(page.locator('[data-result="timeFaults"]')).toContainText('3');
    await expect(page.locator('[data-result="total"]')).toContainText('15');
    await expect(page.locator('[data-result="newBest"]')).toBeVisible();
    await expect(page.locator('.new-badges [data-badge="clean"]')).toBeVisible();
    await expect(page.locator('[data-action="again"]')).toBeVisible();
    await expect(page.locator('[data-action="next"]')).toBeVisible();
    await expect(page.locator('[data-action="toSelect"]')).toBeVisible();
    // "Again" leads to the pre-start of the same course
    await page.locator('[data-action="again"]').click();
    await expect(page.locator('canvas.course-plan')).toBeVisible();
  });

  for (const [width, height] of [
    [640, 360],
    [568, 320],
  ]) {
    test(`new badges and the unlock note are visible without scrolling on a phone (${width}x${height})`, async ({
      page,
      browserName,
    }) => {
      await page.setViewportSize({ width, height });
      await openGameMenu(page, test, browserName, {
        save: { ...NAMED, progress: { unlocked: 2 } },
      });
      await page.evaluate(
        (params) =>
          window.__zhfTest.go('results', { ...params, awarded: ['clean', 'oxerPro', 'comboPro'] }),
        RESULT,
      );
      await expect(page.locator('.new-badges [data-badge]')).toHaveCount(3);
      // nothing is hidden below the fold: the results body does not scroll ...
      const fit = await page.evaluate(() => {
        const body = document.querySelector('.results-body');
        return { scroll: body.scrollHeight, client: body.clientHeight };
      });
      expect(fit.scroll).toBeLessThanOrEqual(fit.client + 1);
      // ... and the badges, the unlock note and the buttons are all inside the screen
      for (const selector of ['.new-badges', '.unlocked-note', '[data-action="again"]']) {
        const box = await page.locator(selector).boundingBox();
        expect(box.y, selector).toBeGreaterThanOrEqual(0);
        expect(box.y + box.height, selector).toBeLessThanOrEqual(height);
      }
    });
  }

  test('six new badges still fit on a phone (568x320) without scrolling', async ({
    page,
    browserName,
  }) => {
    await page.setViewportSize({ width: 568, height: 320 });
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, progress: { unlocked: 2 } },
    });
    // the ride-end badges of the longest realistic case: six at once
    const awarded = ['clean', 'oxerPro', 'comboPro', 'allOpen', 'starRider', 'busy'];
    await page.evaluate((params) => window.__zhfTest.go('results', params), { ...RESULT, awarded });
    await expect(page.locator('.new-badges [data-badge]')).toHaveCount(6);
    const fit = await page.evaluate(() => {
      const body = document.querySelector('.results-body');
      return { scroll: body.scrollHeight, client: body.clientHeight };
    });
    expect(fit.scroll).toBeLessThanOrEqual(fit.client + 1);
    for (const selector of ['.new-badges', '.unlocked-note', '[data-action="again"]']) {
      const box = await page.locator(selector).boundingBox();
      expect(box.y, selector).toBeGreaterThanOrEqual(0);
      expect(box.y + box.height, selector).toBeLessThanOrEqual(320);
    }
  });

  test('the same figures in German; "next course" only when it is open', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName, { lang: 'de' });
    await page.evaluate(
      (params) => window.__zhfTest.go('results', { ...params, unlockedCourse: null }),
      RESULT,
    );
    await expect(page.locator('[data-result="knockdowns"]')).toContainText('2 × 4 = 8');
    await expect(page.locator('[data-result="refusals"]')).toContainText('1 × 4 = 4');
    await expect(page.locator('[data-action="next"]')).toHaveCount(0);
    await expect(page.locator('.panel-results h2')).toHaveText('Geschafft!');
  });
});

test.describe('a complete ride of course 1', () => {
  test('time, results, stars, unlocking, badge and saved progress', async ({
    page,
    browserName,
  }) => {
    test.setTimeout(300_000);
    const watch = watchPage(page);
    await openGameMenu(page, test, browserName, {
      save: { ...NAMED, settings: { aidCourse: true } },
    });
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    await page.locator('[data-action="go"]').click();
    await waitForRide(page, 'course');
    // the clock only runs after the start line
    const before = await rideState(page);
    expect(before.hud.timeCs).toBe(0);
    await rideCourseOne(page);

    await expect(page.locator('.panel-results')).toBeVisible();
    expect(await screenName(page)).toBe('results');
    await expect(page.locator('[data-result="newBest"]')).toBeVisible();
    const progress = await storeSection(page, 'progress');
    // every finished ride opens the next course; the best result and the counters are saved
    expect(progress.unlocked).toBe(2);
    expect(progress.finishedRides).toBe(1);
    expect(progress.jumps).toBeGreaterThanOrEqual(4);
    expect(progress.courses[1].timeCs).toBeGreaterThan(0);
    expect(progress.courses[1].stars).toBeGreaterThanOrEqual(1);
    await expect(page.locator('[data-action="next"]')).toBeVisible();
    await expect(page.locator('.unlocked-note')).toBeVisible();
    // one start signal (on "Go") and one finish signal for the whole ride
    expect(await sfxCounts(page)).toMatchObject({ startSignal: 1, finishSignal: 1 });
    // the same data after a reload
    await page.reload();
    await page.locator('[data-screen="menu"]').waitFor();
    expect(await storeSection(page, 'progress')).toMatchObject({ unlocked: 2, finishedRides: 1 });
    expect(watch.errors).toEqual([]);
  });
});
