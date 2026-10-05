// Build-time app version (rule 58): "<commit date> · <short sha>", e.g. "2026-10-05 · 3fdf19e",
// or "dev" when no git state is available. The date is the date of the commit, not of the build.
// Pure helpers; vite.config.js injects the result as `__APP_VERSION__`.
import { execFileSync } from 'node:child_process';

export const DEV_VERSION = 'dev';
const SHA_LENGTH = 7;
const SHA_PATTERN = /^[0-9a-f]{7,40}$/i;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** "2026-10-05 · 3fdf19e", or null when the date or the sha is missing or malformed. */
export function formatAppVersion({ date, sha }) {
  const day = String(date ?? '').trim();
  const id = String(sha ?? '').trim();
  if (!DATE_PATTERN.test(day) || !SHA_PATTERN.test(id)) return null;
  return `${day} · ${id.slice(0, SHA_LENGTH).toLowerCase()}`;
}

/**
 * The version of this build. `env` is the process environment (GITHUB_SHA is preferred for the
 * sha in GitHub Actions), `git(args)` returns the trimmed stdout of a git command or throws.
 * Never throws: any failure yields "dev".
 */
export function resolveAppVersion({ env = {}, git }) {
  const attempt = (args) => {
    try {
      return git(args);
    } catch {
      return null;
    }
  };
  const sha = env.GITHUB_SHA || attempt(['rev-parse', 'HEAD']);
  const date = attempt(['log', '-1', '--format=%cs']);
  return formatAppVersion({ date, sha }) ?? DEV_VERSION;
}

/** Runs git in `cwd` (stderr silenced); throws when git or the repository is missing. */
export function runGit(args, cwd = process.cwd()) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();
}

export function currentAppVersion(cwd = process.cwd()) {
  return resolveAppVersion({ env: process.env, git: (args) => runGit(args, cwd) });
}
