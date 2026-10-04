// WCAG 2.x color math: relative luminance and contrast ratio of sRGB colors.

/** Parses '#rgb' or '#rrggbb' into [r, g, b] (0..255); null when it is not a hex color. */
export function parseHex(value) {
  if (typeof value !== 'string') return null;
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim());
  if (!m) return null;
  const hex = m[1].length === 3 ? [...m[1]].map((c) => c + c).join('') : m[1];
  return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16));
}

const channel = (v) => {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};

/** Relative luminance (0..1) of an [r, g, b] color. */
export function relativeLuminance([r, g, b]) {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** WCAG contrast ratio (1..21) between two hex colors. */
export function contrastRatio(a, b) {
  const rgbA = parseHex(a);
  const rgbB = parseHex(b);
  if (!rgbA || !rgbB) throw new Error(`Cannot parse color: ${!rgbA ? a : b}`);
  const la = relativeLuminance(rgbA);
  const lb = relativeLuminance(rgbB);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}
