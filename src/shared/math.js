// Small math/type helpers shared by every layer.

/** Limits `v` to the range [min, max]. */
export function clamp(v, min, max) {
  return v < min ? min : v > max ? max : v;
}

/** True for non-null, non-array objects. */
export function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}
