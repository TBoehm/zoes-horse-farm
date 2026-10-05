import { describe, expect, it } from 'vitest';
import { DEV_VERSION, formatAppVersion, resolveAppVersion } from '../../scripts/app-version.mjs';

const SHA = '3fdf19e1234567890abcdef1234567890abcdef1';

const fakeGit =
  (answers) =>
  ([cmd]) => {
    if (!(cmd in answers)) throw new Error('git failed');
    return answers[cmd];
  };

describe('formatAppVersion', () => {
  it('joins the commit date and the 7-character sha', () => {
    expect(formatAppVersion({ date: '2026-10-05', sha: SHA })).toBe('2026-10-05 · 3fdf19e');
  });

  it('lower-cases the sha and tolerates surrounding whitespace', () => {
    expect(formatAppVersion({ date: ' 2026-10-05\n', sha: '3FDF19E\n' })).toBe(
      '2026-10-05 · 3fdf19e',
    );
  });

  it('returns null for a missing or malformed date or sha', () => {
    expect(formatAppVersion({ date: '', sha: SHA })).toBeNull();
    expect(formatAppVersion({ date: '05.10.2026', sha: SHA })).toBeNull();
    expect(formatAppVersion({ date: '2026-10-05', sha: null })).toBeNull();
    expect(formatAppVersion({ date: '2026-10-05', sha: 'xyz' })).toBeNull();
  });
});

describe('resolveAppVersion', () => {
  it('uses git for the sha and the commit date locally', () => {
    const git = fakeGit({ 'rev-parse': SHA, log: '2026-10-05' });
    expect(resolveAppVersion({ env: {}, git })).toBe('2026-10-05 · 3fdf19e');
  });

  it('prefers GITHUB_SHA over git for the sha but keeps the git commit date', () => {
    const git = fakeGit({ 'rev-parse': 'a'.repeat(40), log: '2026-10-05' });
    expect(resolveAppVersion({ env: { GITHUB_SHA: SHA }, git })).toBe('2026-10-05 · 3fdf19e');
  });

  it('falls back to "dev" when git is unavailable', () => {
    expect(resolveAppVersion({ env: {}, git: fakeGit({}) })).toBe(DEV_VERSION);
    expect(DEV_VERSION).toBe('dev');
  });

  it('falls back to "dev" when only the sha is known', () => {
    expect(resolveAppVersion({ env: { GITHUB_SHA: SHA }, git: fakeGit({}) })).toBe('dev');
  });

  it('falls back to "dev" when git answers with garbage', () => {
    const git = fakeGit({ 'rev-parse': 'fatal', log: '' });
    expect(resolveAppVersion({ env: {}, git })).toBe('dev');
  });
});
