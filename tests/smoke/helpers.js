// Gemeinsame Prüfungen für Smoke-Tests: Konsolenfehler und verbotene Dateien (Regel 2).
const ALLOWED_FILES = [/\/icon\.svg$/, /\/generated\/[\w-]+\.png$/];
const FORBIDDEN_EXT =
  /\.(png|jpe?g|gif|webp|avif|bmp|ico|svg|mp3|wav|ogg|m4a|aac|flac|webm|mp4|glb|gltf|obj|fbx|ktx2?|basis|hdr|exr|woff2?|ttf|otf|eot)(\?|$)/i;

export function watchPage(page) {
  const errors = [];
  const forbidden = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' || /Fehlender Text/.test(msg.text())) errors.push(msg.text());
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

// Browser, in denen WebGL im Test vorhanden sein MUSS (sonst scheitert der Test statt zu überspringen)
const REQUIRE_WEBGL = (process.env.SMOKE_REQUIRE_WEBGL ?? 'chromium,msedge').split(',');

/** true = WebGL da; false = nicht da und für diesen Browser erlaubt (Test wird übersprungen). */
export async function webglOrSkip(page, test, browserName) {
  const ok = await hasWebGL(page);
  if (!ok && REQUIRE_WEBGL.includes(browserName)) {
    throw new Error(`WebGL fehlt in ${browserName}, ist dort aber Pflicht`);
  }
  test.skip(!ok, 'kein WebGL im Testbrowser');
  return ok;
}

export async function hasWebGL(page) {
  return page.evaluate(() => {
    const c = document.createElement('canvas');
    return Boolean(c.getContext('webgl2') || c.getContext('webgl'));
  });
}

/** Öffnet die App und überspringt ggf. die Namensfrage beim ersten Start. */
export async function openMenu(page) {
  await page.goto('./');
  const skip = page.locator('[data-action="skip"]');
  const menu = page.locator('[data-screen="menu"]');
  await skip.or(menu).first().waitFor();
  if (await skip.isVisible()) await skip.click();
  await menu.waitFor();
}
