import js from '@eslint/js';
import globals from 'globals';
import prettier from 'eslint-config-prettier';

// Clean-Architecture boundaries (see CLAUDE.md and
// docs/specs/springreiten-trainer/architecture.md):
// adapters -> application -> domain; shared is usable from every layer and imports nothing else.
const THREE_AND_NIPPLE = ['three', 'three/*', 'nipplejs'];
const DOM_GLOBALS = ['window', 'document', 'localStorage', 'sessionStorage', 'navigator'];

const restrictImports = (group, message) => ({
  'no-restricted-imports': ['error', { patterns: [{ group, message }] }],
});

const noDomGlobals = {
  'no-restricted-globals': [
    'error',
    ...DOM_GLOBALS.map((name) => ({
      name,
      message: 'DOM and storage access belongs in src/adapters/.',
    })),
  ],
};

const noHiddenInputs = {
  'no-restricted-properties': [
    'error',
    { object: 'Math', property: 'random', message: 'Inject an rng instead.' },
    { object: 'Date', property: 'now', message: 'Inject a clock or pass time as a parameter.' },
  ],
  'no-restricted-syntax': [
    'error',
    {
      selector: "NewExpression[callee.name='Date'][arguments.length=0]",
      message: 'Inject a clock or pass time as a parameter.',
    },
  ],
};

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'public/generated/**',
      'test-results/**',
      'playwright-report/**',
      '.claude/**',
    ],
  },
  js.configs.recommended,
  {
    files: ['**/*.js', '**/*.mjs'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      eqeqeq: ['error', 'always'],
      'no-var': 'error',
      'prefer-const': 'error',
    },
  },
  {
    files: ['scripts/**', 'tests/**', '*.config.js', 'vite.config.js', 'playwright.config.js'],
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
  },

  // --- Layer boundaries ---
  {
    files: ['src/domain/**/*.js'],
    rules: {
      ...restrictImports(
        [...THREE_AND_NIPPLE, '**/application/**', '**/adapters/**', '**/main.js'],
        'domain may only import domain and shared.',
      ),
      ...noDomGlobals,
      ...noHiddenInputs,
    },
  },
  {
    files: ['src/application/**/*.js'],
    rules: {
      ...restrictImports(
        [...THREE_AND_NIPPLE, '**/adapters/**', '**/main.js'],
        'application may only import domain, application and shared.',
      ),
      ...noDomGlobals,
      ...noHiddenInputs,
    },
  },
  {
    files: ['src/shared/**/*.js'],
    rules: {
      ...restrictImports(
        [...THREE_AND_NIPPLE, '**/domain/**', '**/application/**', '**/adapters/**', '**/main.js'],
        'shared may only import shared.',
      ),
      ...noDomGlobals,
      ...noHiddenInputs,
    },
  },
  {
    files: ['src/adapters/**/*.js'],
    rules: restrictImports(['**/main.js'], 'main.js is the composition root; nothing imports it.'),
  },
  prettier,
];
