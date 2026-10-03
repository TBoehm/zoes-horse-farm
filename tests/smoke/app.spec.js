import { expect, test } from '@playwright/test';
import { hasWebGL, openMenu, watchPage, webglOrSkip } from './helpers.js';

test('Seite lädt, Hauptmenü erscheint, keine Fehler, keine verbotenen Dateien', async ({
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
    // Browser ohne WebGL (z. B. manche CI-Läufe): dann muss der Hinweis erscheinen (Regel 7)
    test.info().annotations.push({ type: 'info', description: 'kein WebGL im Testbrowser' });
    await expect(page.locator('[data-notice="no3d"]')).toBeVisible();
  }
  await page.waitForLoadState('networkidle');
  expect(watch.errors).toEqual([]);
  expect(watch.forbidden).toEqual([]);
});

test('Sprache umschalten wirkt sofort und bleibt nach Neuladen', async ({ page, browserName }) => {
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

test('ohne WebGL erscheint nur der Hinweis statt der App', async ({ page }) => {
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

test.describe('Touch-Gerät im Hochformat', () => {
  test.use({ viewport: { width: 400, height: 800 }, hasTouch: true, isMobile: true });
  test('zeigt den Dreh-Hinweis', async ({ page, browserName }) => {
    test.skip(browserName === 'firefox', 'isMobile wird von Firefox nicht unterstützt');
    await page.goto('./');
    await webglOrSkip(page, test, browserName);
    await expect(page.locator('[data-notice="rotate"]')).toBeVisible();
    await page.setViewportSize({ width: 800, height: 400 });
    await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
  });
});

test('Desktop im Hochformat zeigt keinen Dreh-Hinweis', async ({ page, browserName }) => {
  await page.setViewportSize({ width: 500, height: 900 });
  await page.goto('./');
  await webglOrSkip(page, test, browserName);
  await openMenu(page);
  await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
});

for (const variant of ['localStorage', 'beide Speicher']) {
  test(`Speichern blockiert (${variant}): Hinweis einmal je Sitzung, App bedienbar`, async ({
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
    // Neuladen ist keine neue Sitzung (Regel 46)
    await page.reload();
    await page.locator('[data-screen="menu"], [data-screen="namePrompt"]').first().waitFor();
    await expect(page.locator('[data-notice="save"]')).toHaveCount(0);
  });
}

test('PWA: Manifest und Service Worker vorhanden', async ({ page }) => {
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
