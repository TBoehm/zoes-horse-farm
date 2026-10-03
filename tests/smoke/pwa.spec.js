// Offline start and update behaviour on the built output (rule 5).
import { expect, test } from '@playwright/test';
import { createServer } from 'node:http';
import { cpSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { tmpdir } from 'node:os';

const TYPES = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webmanifest': 'application/manifest+json',
};

function startServer(getRoot) {
  const server = createServer((req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
    let file = join(getRoot(), path === '/' ? 'index.html' : path);
    if (!existsSync(file)) file = join(getRoot(), 'index.html');
    res.setHeader('Content-Type', TYPES[extname(file)] ?? 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.end(readFileSync(file));
  });
  return new Promise((resolve) => server.listen(0, () => resolve(server)));
}

function makeVersionB(distDir) {
  const dir = mkdtempSync(join(tmpdir(), 'zhf-v2-'));
  cpSync(distDir, dir, { recursive: true });
  const indexPath = join(dir, 'index.html');
  writeFileSync(
    indexPath,
    readFileSync(indexPath, 'utf8').replace(
      '<head>',
      '<head><meta name="app-version" content="B">',
    ),
  );
  const swPath = join(dir, 'sw.js');
  const sw = readFileSync(swPath, 'utf8').replace(
    /(url:\s*"index\.html",\s*revision:\s*")[^"]+/,
    '$1version-b',
  );
  writeFileSync(swPath, sw);
  return dir;
}

const versionOf = (page) =>
  page.evaluate(() => document.querySelector('meta[name="app-version"]')?.content ?? 'A');

test.describe('PWA', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'service worker control in Chromium');

  test('starts offline after the first load', async ({ browser }) => {
    const server = await startServer(() => 'dist');
    const url = `http://localhost:${server.address().port}/`;
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(url);
    await page.evaluate(() => navigator.serviceWorker.ready);
    await context.setOffline(true);
    await page.reload();
    const menu = page.locator('[data-screen="menu"]');
    const namePrompt = page.locator('[data-screen="namePrompt"]');
    const no3d = page.locator('[data-notice="no3d"]');
    await expect(menu.or(namePrompt).or(no3d).first()).toBeVisible();
    if (await namePrompt.isVisible()) {
      await page.locator('[data-action="skip"]').click();
      await expect(menu).toBeVisible();
    }
    // without WebGL the game shows its notice instead; with WebGL a free ride must start offline
    if (await menu.isVisible()) {
      await page.locator('[data-entry="free"]').click();
      await expect(page.locator('[data-screen="ride"]')).toBeVisible();
      await page.waitForFunction(
        () => document.querySelector('canvas.scene-canvas')?.clientWidth > 0,
      );
    }
    await context.close();
    server.close();
  });

  test('new version only after closing all tabs, save data is kept', async ({ browser }) => {
    let root = 'dist';
    const server = await startServer(() => root);
    const url = `http://localhost:${server.address().port}/`;
    const context = await browser.newContext();
    let page = await context.newPage();
    await page.goto(url);
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.reload();
    await page.evaluate(() => {
      const save = JSON.parse(localStorage.getItem('zoes-horse-farm.save') || '{}');
      save.settings = { ...save.settings, lang: 'en' };
      save.futureArea = { kept: true };
      localStorage.setItem('zoes-horse-farm.save', JSON.stringify(save));
    });
    expect(await versionOf(page)).toBe('A');

    root = makeVersionB('dist');
    await page.evaluate(async () => {
      const reg = await navigator.serviceWorker.getRegistration();
      await reg.update();
      await new Promise((resolve) => {
        const check = () => (reg.waiting ? resolve() : setTimeout(check, 100));
        check();
      });
    });
    // Reloading an open tab does not switch the version
    await page.reload();
    expect(await versionOf(page)).toBe('A');
    await expect(
      page.locator('[data-screen="namePrompt"], [data-screen="menu"]').first(),
    ).toBeVisible();

    // Close all tabs and reopen → new version
    await page.close();
    await new Promise((r) => setTimeout(r, 500));
    page = await context.newPage();
    await page.goto(url);
    await expect.poll(() => versionOf(page), { timeout: 10_000 }).toBe('B');
    const save = await page.evaluate(() =>
      JSON.parse(localStorage.getItem('zoes-horse-farm.save')),
    );
    expect(save.settings.lang).toBe('en');
    expect(save.futureArea).toEqual({ kept: true });
    await context.close();
    server.close();
  });
});
