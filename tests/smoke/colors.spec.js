// SRT-010 in the browser: button colors follow their meaning (rule 57), read from computed styles.
import { expect, test } from '@playwright/test';
import { openGameMenu } from './helpers.js';

test.use({ viewport: { width: 640, height: 400 } });

const style = (locator, prop) => locator.evaluate((el, p) => getComputedStyle(el)[p], prop);

/** 'rgb(r, g, b)' → [r, g, b] */
const rgb = (value) => value.match(/\d+/g).slice(0, 3).map(Number);

test.describe('button colors', () => {
  test('menu entries are green, "Back" is outlined, only the delete buttons are red', async ({
    page,
    browserName,
  }) => {
    await openGameMenu(page, test, browserName);
    const entry = page.locator('[data-entry="free"]');
    const [r, g, b] = rgb(await style(entry, 'backgroundColor'));
    expect(g).toBeGreaterThan(r);
    expect(g).toBeGreaterThan(b);
    expect(await style(entry, 'color')).toBe('rgb(255, 255, 255)');

    await page.locator('[data-entry="settings"]').click();
    const back = page.locator('[data-action="back"]');
    // restrained: light fill with a visible border in the text color
    expect(rgb(await style(back, 'backgroundColor'))).toEqual([255, 244, 224]);
    expect(await style(back, 'borderTopColor')).toBe(await style(back, 'color'));
    expect(parseFloat(await style(back, 'borderTopWidth'))).toBeGreaterThanOrEqual(2);

    const reset = page.locator('[data-action="reset"]');
    await reset.scrollIntoViewIfNeeded();
    expect(rgb(await style(reset, 'backgroundColor'))).toEqual([179, 38, 30]);
    await reset.click();
    const confirm = page.locator('[data-action="reset-confirm"]');
    await expect(confirm).toBeVisible();
    expect(rgb(await style(confirm, 'backgroundColor'))).toEqual([179, 38, 30]);
    expect(
      rgb(await style(page.locator('[data-action="reset-cancel"]'), 'backgroundColor')),
    ).toEqual([255, 244, 224]);
  });
});
