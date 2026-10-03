import { defineConfig, devices } from '@playwright/test';

// Lokal kann ein vorinstalliertes Chromium genutzt werden (PW_CHROMIUM_PATH),
// in CI installiert Playwright die passenden Browser selbst.
const chromiumPath = process.env.PW_CHROMIUM_PATH;
const browsers = (process.env.SMOKE_BROWSERS || 'chromium').split(',');
const GPU_ARGS = ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'];

const projects = [
  {
    name: 'chromium',
    use: {
      ...devices['Desktop Chrome'],
      launchOptions: {
        executablePath: chromiumPath || undefined,
        args: GPU_ARGS,
      },
    },
  },
  { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  {
    name: 'msedge',
    use: {
      ...devices['Desktop Edge'],
      channel: 'msedge',
      launchOptions: { args: GPU_ARGS },
    },
  },
].filter((p) => browsers.includes(p.name));

export default defineConfig({
  testDir: 'tests/smoke',
  timeout: 60_000,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173/',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run preview',
    url: 'http://localhost:4173/',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
  projects,
});
