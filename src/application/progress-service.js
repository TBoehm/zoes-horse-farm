// Progress use cases on top of the store port: count a jump, finish a ride, delete progress.
// The rules live in domain/progress; this service only sequences them and persists the result.
import {
  addJump,
  applyFinishedRide,
  resetProgress as resetProgressData,
} from '../domain/progress/progress.js';
import { checkInstantBadges, checkRideEndBadges } from '../domain/progress/badges.js';

/**
 * Counts one jump over an obstacle (rule 40), saves it at once and awards instant badges (rule 49).
 * @returns {string[]} ids of the badges awarded by this jump
 */
export function recordJump(store, clock) {
  let awarded = [];
  store.update('progress', (progress) => {
    const checked = checkInstantBadges(addJump(progress), clock.nowIso());
    awarded = checked.awarded;
    return checked.progress;
  });
  return awarded;
}

/**
 * Scores a FINISHED ride (never pass aborted rides, rule 40) and awards end-of-ride badges.
 * @returns {{ isNewBest: boolean, unlockedCourse: number|null, awarded: string[] }}
 */
export function finishRide(store, clock, result) {
  let outcome = null;
  store.update('progress', (progress) => {
    const applied = applyFinishedRide(progress, result);
    const badges = checkRideEndBadges(applied.progress, result, clock.nowIso());
    outcome = {
      isNewBest: applied.isNewBest,
      unlockedCourse: applied.unlockedCourse,
      awarded: badges.awarded,
    };
    return badges.progress;
  });
  return outcome;
}

/** "Delete progress" (rule 48): resets only the progress fields, nothing else in the save game. */
export function resetProgress(store) {
  store.update('progress', (progress) => resetProgressData(progress));
}
