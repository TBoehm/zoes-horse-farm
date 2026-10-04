import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Smoke tests (tests/smoke/*.spec.js) run in Playwright, not here.
    include: ['src/**/*.test.js', 'tests/**/*.test.js'],
    environment: 'node',
  },
});
