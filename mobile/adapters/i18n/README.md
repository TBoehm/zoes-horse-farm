# `:adapters:i18n` – texts in German and English

Port of `src/adapters/ui/i18n.js` and `src/adapters/ui/i18n/*.js` (web). Package `app.zoeshorsefarm.i18n`.
Depends on `:core:application` (`Language`, `LANGS`; the badge test also uses the domain's `BADGES`).

- `I18n` – the text tables and the current language: `t(key, params)`, `lang`, `setLang`,
  `onLangChange(listener)` (returns the unsubscribe function), `registerStrings(area)`. An instance, not a
  global: the composition root creates one (`I18n()` registers all areas, German is the start language).
  A missing key falls back to German, then English; an unknown key calls `onMissing(key)` (web:
  `console.error`) and gives `""`. `{name}` placeholders are filled from `params`, unknown ones stay.
- `detectLang(preferredLanguages)` – German if the first non-empty language tag starts with `de`.
- `StringArea(de, en)` and `texts(...)` – one table per feature area; `texts` rejects a key listed twice.
- `STRING_AREAS` – the areas (`CORE_STRINGS`, `RIDING_STRINGS`, `PROFILE_STRINGS`, `BADGES_STRINGS`,
  `COURSES_STRINGS`, `AUDIO_STRINGS`, `HELP_STRINGS`, `DEBUG_STRINGS`): the same 200 keys and texts as the web
  app (checked mechanically against the JS tables when they were ported). Plus the native-only `DATE_STRINGS`
  (`date.long` pattern and `date.month.1..12`; the web lets the browser format dates).

Rules (checked by the tests): same keys and `{placeholders}` in `de` and `en`, no empty texts, no key in two
areas, every domain badge has a name and a condition text. Keep new texts in the area files, never in the UI.

Not ported: setting `document.documentElement.lang` (DOM only; the UI sets the platform locale if needed).
