// Pferdename (Regel 43): getrimmt, 1–16 Zeichen; ohne eigenen Namen gilt der Vorgabe-Name der Sprache.
export const NAME_MAX = 16;

/** Liefert den bereinigten Namen oder null, wenn er ungültig ist. */
export function cleanName(input) {
  if (typeof input !== 'string') return null;
  const name = input.trim();
  const length = [...name].length;
  return length >= 1 && length <= NAME_MAX ? name : null;
}

export function displayName(horse, t) {
  return horse?.name ?? t('horse.defaultName');
}
