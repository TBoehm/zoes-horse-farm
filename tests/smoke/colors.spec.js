// SRT-010 in the browser: button colors follow their meaning (rule 57), read from computed styles
// on every screen that has buttons.
import { expect, test } from '@playwright/test';
import { openGame, openGameMenu, startFreeRide, webglOrSkip, waitForRide } from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

// Fill colors of the roles (the tokens of main.css, as RGB)
const FILL = {
  positive: [46, 125, 50],
  neutral: [255, 244, 224],
  danger: [179, 38, 30],
  choice: [239, 228, 211],
  choiceOn: [242, 169, 0],
  toggleOff: [205, 191, 169],
};
const WHITE = [255, 255, 255];

// What a button means, by its `data-action` (menu entries are always "go on"): the role it must
// have no matter which class it carries
const MEANING = {
  go: 'positive',
  next: 'positive',
  again: 'positive',
  ok: 'positive',
  resume: 'positive',
  'help-done': 'positive',
  back: 'neutral',
  skip: 'neutral',
  restart: 'neutral',
  quit: 'neutral',
  settings: 'neutral',
  help: 'neutral',
  toSelect: 'neutral',
  'reset-cancel': 'neutral',
  reset: 'danger',
  'reset-confirm': 'danger',
};
// Only buttons that delete something are red
const DELETE_BUTTONS = new Set(['reset', 'reset-confirm']);

/** Every visible `.btn` of the page with its colors as [r, g, b, a] (read through a canvas). */
const collectButtons = (page) =>
  page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    // the browsers write computed colors in different notations (rgb(), color(srgb ...))
    const toRgba = (value) => {
      ctx.clearRect(0, 0, 1, 1);
      ctx.fillStyle = '#000';
      ctx.fillStyle = value;
      ctx.fillRect(0, 0, 1, 1);
      return [...ctx.getImageData(0, 0, 1, 1).data];
    };
    return [...document.querySelectorAll('.btn')]
      .filter(
        (el) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden',
      )
      .map((el) => {
        const style = getComputedStyle(el);
        return {
          key: el.dataset.action ?? (el.dataset.entry ? `entry:${el.dataset.entry}` : el.className),
          classes: [...el.classList],
          checked: el.getAttribute('aria-checked'),
          fill: toRgba(style.backgroundColor),
          ink: toRgba(style.color),
        };
      });
  });

/** The role a button is drawn as, from its classes. */
function drawnRole(button) {
  const has = (name) => button.classes.includes(name);
  if (has('btn-danger')) return 'danger';
  if (has('btn-secondary')) return 'neutral';
  if (has('btn-choice')) return button.checked === 'true' ? 'choiceOn' : 'choice';
  if (has('btn-toggle')) return button.checked === 'true' ? 'positive' : 'toggleOff';
  return 'positive';
}

// a real red: the red channel dominates both others (terracotta and danger are red, amber is not)
const isRed = ([r, g, b]) => r > 2 * g && r > 2 * b;

/**
 * Every visible button has the fill of its role, the buttons with a known meaning have the right
 * role, and nothing but a delete button is red. Returns the buttons for further checks.
 */
async function expectRoles(page, where, { min = 1 } = {}) {
  await page.mouse.move(0, 0); // no hover color on an outlined button
  const buttons = await collectButtons(page);
  expect(buttons.length, `${where}: buttons found`).toBeGreaterThanOrEqual(min);
  for (const button of buttons) {
    const label = `${where}: ${button.key}`;
    const role = drawnRole(button);
    expect(button.fill.slice(0, 3), `${label} fill (${role})`).toEqual(FILL[role]);
    expect(button.fill[3], `${label} is opaque`).toBe(255);
    if (role === 'positive' || role === 'danger') expect(button.ink.slice(0, 3)).toEqual(WHITE);
    const meant = button.key.startsWith('entry:') ? 'positive' : MEANING[button.key];
    if (meant) expect(role, `${label} means ${meant}`).toBe(meant);
    if (isRed(button.fill)) {
      expect(DELETE_BUTTONS.has(button.key), `${label} is red but deletes nothing`).toBe(true);
    }
  }
  return buttons;
}

const keysOf = (buttons) => buttons.map((b) => b.key);

test.describe('button colors', () => {
  test('main menu: every entry is green, none is red', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    const buttons = await expectRoles(page, 'menu', { min: 3 });
    for (const button of buttons) expect(drawnRole(button), button.key).toBe('positive');
    // the first entry as an example: green fill (the white text is checked for every button)
    const [r, g, b] = buttons.find((x) => x.key === 'entry:free').fill;
    expect(g).toBeGreaterThan(r);
    expect(g).toBeGreaterThan(b);
  });

  test('settings: "Back" is outlined, options and switches have their own look, only reset is red', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click();
    await page.locator('[data-action="back"]').waitFor();
    const buttons = await expectRoles(page, 'settings', { min: 4 });
    expect(keysOf(buttons)).toContain('back');
    expect(buttons.some((b) => drawnRole(b) === 'choiceOn')).toBe(true);
    expect(buttons.some((b) => b.classes.includes('btn-toggle'))).toBe(true);

    // restrained: light fill with a visible border in the text color
    const back = page.locator('[data-action="back"]');
    const style = (locator, prop) => locator.evaluate((el, p) => getComputedStyle(el)[p], prop);
    expect(await style(back, 'borderTopColor')).toBe(await style(back, 'color'));
    expect(parseFloat(await style(back, 'borderTopWidth'))).toBeGreaterThanOrEqual(2);

    const reset = page.locator('[data-action="reset"]');
    await reset.scrollIntoViewIfNeeded();
    expect(keysOf(await expectRoles(page, 'settings'))).toContain('reset');
    await reset.click();
    await expect(page.locator('[data-action="reset-confirm"]')).toBeVisible();
    const confirm = await expectRoles(page, 'reset confirmation');
    expect(keysOf(confirm)).toEqual(expect.arrayContaining(['reset-confirm', 'reset-cancel']));
    // the deleting button is red, "Cancel" is not (and expectRoles saw no other red button)
    const red = confirm.filter((b) => isRed(b.fill)).map((b) => b.key);
    expect(red).toContain('reset-confirm');
    expect(red).not.toContain('reset-cancel');
  });

  test('the toggle knob is centred in its switch', async ({ page, browserName }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="settings"]').click();
    const toggle = page.locator('.btn-toggle').first();
    await toggle.scrollIntoViewIfNeeded();
    const gaps = await toggle.evaluate((el) => {
      const outer = el.getBoundingClientRect();
      const knob = el.querySelector('.toggle-knob').getBoundingClientRect();
      return {
        top: knob.top - outer.top,
        bottom: outer.bottom - knob.bottom,
        left: knob.left - outer.left,
        right: outer.right - knob.right,
      };
    });
    expect(Math.abs(gaps.top - gaps.bottom)).toBeLessThanOrEqual(1);
    // off: the knob sits left, on: right, with the same gap at its end
    expect(Math.min(gaps.left, gaps.right)).toBeCloseTo(gaps.top, 0);
  });

  test('name question: "Skip" is outlined, "OK" is green', async ({ page, browserName }) => {
    await openGame(page);
    await webglOrSkip(page, test, browserName);
    await page.locator('[data-action="skip"]').waitFor();
    const buttons = await expectRoles(page, 'name question', { min: 2 });
    expect(keysOf(buttons)).toEqual(expect.arrayContaining(['ok', 'skip']));
  });

  test('pause menu: "Resume" is green, the other entries are outlined', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await startFreeRide(page);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.__zhfTest.ride().paused);
    const buttons = await expectRoles(page, 'pause', { min: 5 });
    expect(keysOf(buttons)).toEqual(
      expect.arrayContaining(['resume', 'restart', 'quit', 'settings', 'help']),
    );
  });

  test('pre-start card and results screen follow the same colors', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="courses"]').click();
    await page.locator('[data-course="1"]').click();
    await page.locator('[data-action="go"]').waitFor();
    const prestart = await expectRoles(page, 'pre-start', { min: 2 });
    expect(keysOf(prestart)).toEqual(expect.arrayContaining(['go', 'back']));

    await page.evaluate((params) => window.__zhfTest.go('results', params), {
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
    });
    await page.locator('.panel-results').waitFor();
    const results = await expectRoles(page, 'results', { min: 2 });
    expect(keysOf(results)).toEqual(expect.arrayContaining(['again', 'toSelect']));
  });

  test('controls help: "Got it" is green, the input type switch is an option group', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="help"]').click();
    await page.locator('[data-action="help-done"]').waitFor();
    const buttons = await expectRoles(page, 'help', { min: 3 });
    expect(keysOf(buttons)).toContain('help-done');
    expect(buttons.filter((b) => b.classes.includes('btn-choice'))).toHaveLength(2);
  });
});

test.describe('touch jump button', () => {
  test.use({ viewport: { width: 800, height: 400 }, hasTouch: true, isMobile: true });

  test('is blue, never red', async ({ page, browserName }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await openGameMenu(page, test, browserName);
    await page.locator('[data-entry="free"]').tap();
    await waitForRide(page, 'free');
    const jump = page.locator('.touch-jump');
    await expect(jump).toBeVisible();
    const [r, g, b] = await jump.evaluate((el) => {
      const canvas = document.createElement('canvas');
      canvas.width = 1;
      canvas.height = 1;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.fillStyle = getComputedStyle(el).backgroundColor;
      ctx.fillRect(0, 0, 1, 1);
      return [...ctx.getImageData(0, 0, 1, 1).data];
    });
    expect(isRed([r, g, b])).toBe(false);
    expect(b).toBeGreaterThan(r);
    expect(b).toBeGreaterThan(g);
  });
});
