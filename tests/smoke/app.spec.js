import { expect, test } from '@playwright/test';
import { hasWebGL, openMenu, watchPage, webglOrSkip } from './helpers.js';

test('page loads, main menu appears, no errors, no forbidden files', async ({
  page,
  browserName,
}) => {
  const watch = watchPage(page);
  await page.goto('./');
  const required = (process.env.SMOKE_REQUIRE_WEBGL ?? 'chromium,msedge').split(',');
  if (required.includes(browserName)) expect(await hasWebGL(page)).toBe(true);
  if (await hasWebGL(page)) {
    await openMenu(page);
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();
    await expect(page.locator('.game-title')).toHaveText("Zoe's Horse Farm");
    await expect(page.locator('[data-entry="settings"]')).toBeVisible();
  } else {
    // Browser without WebGL (e.g. some CI runs): the notice must appear then (rule 7)
    test.info().annotations.push({ type: 'info', description: 'no WebGL in the test browser' });
    await expect(page.locator('[data-notice="no3d"]')).toBeVisible();
  }
  await page.waitForLoadState('networkidle');
  expect(watch.errors).toEqual([]);
  expect(watch.forbidden).toEqual([]);
});

test('switching the language takes effect immediately and persists after a reload', async ({
  page,
  browserName,
}) => {
  await page.goto('./');
  await webglOrSkip(page, test, browserName);
  await openMenu(page);
  await page.click('[data-entry="settings"]');
  await page.click('[data-name="lang"][data-value="en"]');
  await expect(page.locator('.panel-settings h2')).toHaveText('Settings');
  await page.reload();
  await expect(page.locator('[data-entry="settings"]')).toHaveText('Settings');
  await page.click('[data-entry="settings"]');
  await page.click('[data-name="lang"][data-value="de"]');
  await expect(page.locator('.panel-settings h2')).toHaveText('Einstellungen');
});

test('the settings show the build version at the bottom (rule 58)', async ({
  page,
  browserName,
}) => {
  await page.goto('./');
  await webglOrSkip(page, test, browserName);
  await openMenu(page);
  await page.click('[data-entry="settings"]');
  const line = page.locator('.panel-settings [data-field="version"]');
  await expect(line).toHaveText(/^Version (\d{4}-\d{2}-\d{2} · [0-9a-f]{7}|dev)$/);
  await line.scrollIntoViewIfNeeded();
  await expect(line).toBeInViewport();
});

test('without WebGL only the notice appears instead of the app', async ({ page }) => {
  await page.addInitScript(() => {
    const orig = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type, ...rest) {
      if (/webgl/.test(type)) return null;
      return orig.call(this, type, ...rest);
    };
  });
  await page.goto('./');
  await expect(page.locator('[data-notice="no3d"]')).toBeVisible();
  await expect(page.locator('[data-screen="menu"]')).toHaveCount(0);
});

test.describe('touch device in portrait', () => {
  test.use({ viewport: { width: 400, height: 800 }, hasTouch: true, isMobile: true });
  test('shows the rotate notice', async ({ page, browserName }) => {
    test.skip(browserName === 'firefox', 'isMobile is not supported by Firefox');
    await page.goto('./');
    await webglOrSkip(page, test, browserName);
    await expect(page.locator('[data-notice="rotate"]')).toBeVisible();
    await page.setViewportSize({ width: 800, height: 400 });
    await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
  });
});

test('desktop in portrait shows no rotate notice', async ({ page, browserName }) => {
  await page.setViewportSize({ width: 500, height: 900 });
  await page.goto('./');
  await webglOrSkip(page, test, browserName);
  await openMenu(page);
  await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
});

for (const variant of ['localStorage', 'both storages']) {
  test(`saving blocked (${variant}): notice once per session, app usable`, async ({
    page,
    browserName,
  }) => {
    await page.addInitScript((both) => {
      const original = Storage.prototype.setItem;
      Storage.prototype.setItem = function (key, value) {
        if (both || this === window.localStorage) {
          throw new DOMException('quota', 'QuotaExceededError');
        }
        return original.call(this, key, value);
      };
    }, variant !== 'localStorage');
    await page.goto('./');
    await webglOrSkip(page, test, browserName);
    await expect(page.locator('[data-notice="save"]')).toBeVisible();
    await page.click('[data-notice="save"] button');
    await openMenu(page);
    await page.click('[data-entry="settings"]');
    await expect(page.locator('.panel-settings')).toBeVisible();
    // A reload is not a new session (rule 46)
    await page.reload();
    await page.locator('[data-screen="menu"], [data-screen="namePrompt"]').first().waitFor();
    await expect(page.locator('[data-notice="save"]')).toHaveCount(0);
  });
}

test('PWA: manifest and service worker present', async ({ page }) => {
  await page.goto('./');
  const manifestHref = await page.getAttribute('link[rel="manifest"]', 'href');
  expect(manifestHref).toBeTruthy();
  const res = await page.request.get(new URL(manifestHref, page.url()).toString());
  const manifest = await res.json();
  expect(manifest.icons.some((i) => i.sizes === '512x512')).toBe(true);
  expect(manifest.display).toBe('standalone');
  const hasSw = await page.evaluate(async () => {
    const reg = await navigator.serviceWorker.ready;
    return Boolean(reg.active);
  });
  expect(hasSw).toBe(true);
});
