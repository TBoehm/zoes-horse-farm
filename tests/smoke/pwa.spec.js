// Offline-Start und Update-Verhalten am gebauten Stand (Regel 5).
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
  test.skip(
    ({ browserName }) => browserName !== 'chromium',
    'Service-Worker-Steuerung in Chromium',
  );

  test('startet nach dem ersten Laden offline', async ({ browser }) => {
    const server = await startServer(() => 'dist');
    const url = `http://localhost:${server.address().port}/`;
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(url);
    await page.evaluate(() => navigator.serviceWorker.ready);
    await context.setOffline(true);
    await page.reload();
    await expect(page.locator('[data-screen="menu"], [data-notice="no3d"]').first()).toBeVisible();
    await context.close();
    server.close();
  });

  test('neue Version erst nach Schließen aller Tabs, Spielstand bleibt', async ({ browser }) => {
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
    // Neuladen eines offenen Tabs wechselt die Version nicht
    await page.reload();
    expect(await versionOf(page)).toBe('A');
    await expect(page.locator('[data-screen="menu"]')).toBeVisible();

    // Alle Tabs schließen und neu öffnen → neue Version
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
