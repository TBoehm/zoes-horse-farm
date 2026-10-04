// Button colors (rule 57): every text/fill pair of the design tokens in main.css must reach the
// WCAG contrast of 4.5:1, and the meaning of the roles must stay distinct.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contrastRatio } from '../../../shared/color.js';

const MIN_TEXT_CONTRAST = 4.5;
const MIN_NON_TEXT_CONTRAST = 3;

/** Custom properties of the first :root block as { '--name': 'value' }. */
function readRootTokens(css) {
  const block = /:root\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
  const withoutComments = block.replace(/\/\*[\s\S]*?\*\//g, '');
  const tokens = {};
  for (const m of withoutComments.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g))
    tokens[m[1]] = m[2].trim();
  return tokens;
}

const css = readFileSync(new URL('./main.css', import.meta.url), 'utf8');
const tokens = readRootTokens(css);
const color = (name) => {
  expect(tokens, `token ${name}`).toHaveProperty([name]);
  return tokens[name];
};

// [description, ink token, fill token]
const TEXT_PAIRS = [
  ['positive button (default)', '--c-positive-ink', '--c-positive'],
  ['restrained button (outlined)', '--c-neutral-ink', '--c-neutral-bg'],
  ['restrained button while pressed', '--c-neutral-ink', '--c-neutral-press'],
  ['delete button', '--c-danger-ink', '--c-danger'],
  ['option button, not selected', '--c-choice-ink', '--c-choice-bg'],
  ['option button, selected', '--c-choice-on-ink', '--c-choice-on'],
  ['touch jump button label', '--c-touch-ink', '--c-touch-jump'],
  ['title on the panel cream', '--c-brand', '--c-neutral-bg'],
];

describe('button palette', () => {
  it('reads the tokens from main.css', () => {
    expect(Object.keys(tokens).length).toBeGreaterThan(10);
  });

  it.each(TEXT_PAIRS)('%s: text reaches 4.5:1', (_name, ink, fill) => {
    expect(contrastRatio(color(ink), color(fill))).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST);
  });

  it('the border of the outlined button is visible on the panel (3:1)', () => {
    expect(contrastRatio(color('--c-neutral-ink'), color('--c-neutral-bg'))).toBeGreaterThanOrEqual(
      MIN_NON_TEXT_CONTRAST,
    );
  });

  it('filled buttons stand out from the panel cream (3:1)', () => {
    for (const fill of ['--c-positive', '--c-danger']) {
      expect(contrastRatio(color(fill), color('--c-neutral-bg'))).toBeGreaterThanOrEqual(
        MIN_NON_TEXT_CONTRAST,
      );
    }
  });

  it('keeps the roles apart: positive is not the delete color and not a legacy red', () => {
    expect(color('--c-positive').toLowerCase()).not.toBe(color('--c-danger').toLowerCase());
    expect(tokens).not.toHaveProperty(['--c-primary']);
    expect(tokens).not.toHaveProperty(['--c-secondary']);
  });

  it('every color token used in the style sheets is defined', () => {
    const files = ['main.css', 'ride.css', 'profile.css', 'courses.css'];
    for (const file of files) {
      const source = readFileSync(new URL(`./${file}`, import.meta.url), 'utf8');
      for (const m of source.matchAll(/var\((--c-[\w-]+)\)/g)) {
        expect(tokens, `${file} uses ${m[1]}`).toHaveProperty([m[1]]);
      }
    }
  });
});
