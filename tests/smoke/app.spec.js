import { expect, test } from '@playwright/test';
import { hasWebGL, watchPage } from './helpers.js';

test('Seite lädt, Hauptmenü erscheint, keine Fehler, keine verbotenen Dateien', async ({
  page,
}) => {
  const watch = watchPage(page);
  await page.goto('./');
  if (await hasWebGL(page)) {
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

test('Sprache umschalten wirkt sofort und bleibt nach Neuladen', async ({ page }) => {
  await page.goto('./');
  test.skip(!(await hasWebGL(page)), 'kein WebGL im Testbrowser');
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
    test.skip(!(await hasWebGL(page)), 'kein WebGL im Testbrowser');
    await expect(page.locator('[data-notice="rotate"]')).toBeVisible();
    await page.setViewportSize({ width: 800, height: 400 });
    await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
  });
});

test('Desktop im Hochformat zeigt keinen Dreh-Hinweis', async ({ page }) => {
  await page.setViewportSize({ width: 500, height: 900 });
  await page.goto('./');
  test.skip(!(await hasWebGL(page)), 'kein WebGL im Testbrowser');
  await expect(page.locator('[data-screen="menu"]')).toBeVisible();
  await expect(page.locator('[data-notice="rotate"]')).toBeHidden();
});

test('Speichern blockiert: Hinweis einmal je Sitzung, App bedienbar', async ({ page }) => {
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (this === window.localStorage) throw new DOMException('quota', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  await page.goto('./');
  test.skip(!(await hasWebGL(page)), 'kein WebGL im Testbrowser');
  await expect(page.locator('[data-notice="save"]')).toBeVisible();
  await page.click('[data-entry="settings"]');
  await expect(page.locator('.panel-settings')).toBeVisible();
});

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
