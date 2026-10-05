import { describe, expect, it } from 'vitest';
import { APP_VERSION } from './app-version.js';

describe('APP_VERSION', () => {
  it('falls back to "dev" when the build did not inject a version', () => {
    expect(APP_VERSION).toBe('dev');
  });
});
