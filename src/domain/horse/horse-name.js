// Horse name (rule 43): trimmed, 1–16 characters; without a custom name the language default applies.
export const NAME_MAX_LENGTH = 16;

/** Returns the cleaned name, or null if it is invalid. */
export function cleanName(input) {
  if (typeof input !== 'string') return null;
  const name = input.trim();
  const length = [...name].length;
  return length >= 1 && length <= NAME_MAX_LENGTH ? name : null;
}
