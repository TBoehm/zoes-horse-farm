// Gemeinsame Prüfungen für Smoke-Tests: Konsolenfehler und verbotene Dateien (Regel 2).
const ALLOWED_FILES = [/\/icon\.svg$/, /\/generated\/[\w-]+\.png$/];
const FORBIDDEN_EXT =
  /\.(png|jpe?g|gif|webp|avif|bmp|ico|svg|mp3|wav|ogg|m4a|aac|flac|webm|mp4|glb|gltf|obj|fbx|ktx2?|basis|hdr|exr|woff2?|ttf|otf|eot)(\?|$)/i;

export function watchPage(page) {
  const errors = [];
  const forbidden = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
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

export async function hasWebGL(page) {
  return page.evaluate(() => {
    const c = document.createElement('canvas');
    return Boolean(c.getContext('webgl2') || c.getContext('webgl'));
  });
}
