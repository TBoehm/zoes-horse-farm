// Badge overview (rule 50): all badges merged with the earned dates from the progress.
import { BADGES } from '../domain/progress/badges.js';

const keysOf = ({ id, nameKey, conditionKey }) => ({ id, nameKey, conditionKey });

/** @returns {{ id: string, nameKey: string, conditionKey: string, earnedAt: string|null }[]} */
export function listBadges(store) {
  const earned = store.get('progress').badges ?? {};
  return BADGES.map((badge) => ({ ...keysOf(badge), earnedAt: earned[badge.id] ?? null }));
}

export function badgeSummary(store) {
  const list = listBadges(store);
  return { earned: list.filter((b) => b.earnedAt !== null).length, total: list.length };
}

/** Text keys of one badge (toasts, results), or null for an unknown id. */
export function describeBadge(id) {
  const badge = BADGES.find((b) => b.id === id);
  return badge ? keysOf(badge) : null;
}
