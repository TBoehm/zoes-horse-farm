// Shared checks for smoke tests: console errors and forbidden files (rule 2).
const ALLOWED_FILES = [/\/icon\.svg$/, /\/generated\/[\w-]+\.png$/];
const FORBIDDEN_EXT =
  /\.(png|jpe?g|gif|webp|avif|bmp|ico|svg|mp3|wav|ogg|m4a|aac|flac|webm|mp4|glb|gltf|obj|fbx|ktx2?|basis|hdr|exr|woff2?|ttf|otf|eot)(\?|$)/i;

export function watchPage(page) {
  const errors = [];
  const forbidden = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' || /Missing text/.test(msg.text())) errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(String(err)));
  page.on('request', (req) => {
    const url = req.url();
    const type = req.resourceType();
    if (ALLOWED_FILES.some((re) => re.test(new URL(url).pathname))) return;
    if (['image', 'media', 'font'].includes(type) || FORBIDDEN_EXT.test(url)) {
      forbidden.push(`${type} ${url}`);
    }
  });
  return { errors, forbidden };
}

// Browsers in which WebGL MUST be available in the test (otherwise the test fails instead of skipping)
const REQUIRE_WEBGL = (process.env.SMOKE_REQUIRE_WEBGL ?? 'chromium,msedge').split(',');

/** true = WebGL available; false = not available and allowed for this browser (test is skipped). */
export async function webglOrSkip(page, test, browserName) {
  const ok = await hasWebGL(page);
  if (!ok && REQUIRE_WEBGL.includes(browserName)) {
    throw new Error(`WebGL is missing in ${browserName}, but required there`);
  }
  test.skip(!ok, 'no WebGL in the test browser');
  return ok;
}

export async function hasWebGL(page) {
  return page.evaluate(() => {
    const c = document.createElement('canvas');
    return Boolean(c.getContext('webgl2') || c.getContext('webgl'));
  });
}

/** Opens the app and skips the name prompt on first start if it appears. */
export async function openMenu(page) {
  await page.goto('./');
  const skip = page.locator('[data-action="skip"]');
  const menu = page.locator('[data-screen="menu"]');
  await skip.or(menu).first().waitFor();
  if (await skip.isVisible()) await skip.click();
  await menu.waitFor();
}
