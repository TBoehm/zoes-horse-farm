// The build's version string (rule 58), injected by vite.config.js as `__APP_VERSION__`
// ("2026-10-05 · 3fdf19e", or "dev"). The only place that reads the build-time constant; without
// it (e.g. in Vitest) the version is "dev".
export const APP_VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev';
