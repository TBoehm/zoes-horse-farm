// Badges (concept rule 49): pure check logic, immutable, no DOM.
// The takeoff assist has no influence on awarding (rule 42).

const JUMPS_FOR_JUMP_MOUSE = 100;
const RIDES_FOR_BUSY = 10;
export const COURSE_COUNT = 5;
/** Course ids as save-file keys: '1'..'5'. */
export const COURSE_IDS = Object.freeze(
  Array.from({ length: COURSE_COUNT }, (_, i) => String(i + 1)),
);

/** Order as in rule 49. `award`: 'instant' = after a counted jump, 'rideEnd' = at ride end. */
export const BADGES = Object.freeze(
  [
    ['firstJump', 'instant'],
    ['jumpMouse', 'instant'],
    ['clean', 'rideEnd'],
    ['oxerPro', 'rideEnd'],
    ['comboPro', 'rideEnd'],
    ['allOpen', 'rideEnd'],
    ['starRider', 'rideEnd'],
    ['busy', 'rideEnd'],
  ].map(([id, award]) =>
    Object.freeze({
      id,
      award,
      nameKey: `badge.${id}.name`,
      conditionKey: `badge.${id}.condition`,
    }),
  ),
);

export const BADGE_IDS = Object.freeze(BADGES.map((b) => b.id));

function hasBadge(progress, id) {
  return Object.hasOwn(progress.badges ?? {}, id);
}

function allCoursesThreeStars(progress) {
  const courses = progress.courses ?? {};
  return COURSE_IDS.every((id) => courses[id]?.stars === 3);
}

// Only the real courses count, not stray entries of a damaged or newer save.
function anyCourseThreeStars(progress) {
  const courses = progress.courses ?? {};
  return COURSE_IDS.some((id) => courses[id]?.stars === 3);
}

/** Awards all still-missing badges from `conditions` (id → bool) with date `nowIso`. */
function award(progress, conditions, nowIso) {
  const awarded = BADGES.filter((b) => conditions[b.id] && !hasBadge(progress, b.id)).map(
    (b) => b.id,
  );
  if (awarded.length === 0) return { progress, awarded };
  const badges = { ...progress.badges };
  for (const id of awarded) badges[id] = nowIso;
  return { progress: { ...progress, badges }, awarded };
}

/**
 * Instant badges (first jump, jump mouse). Call after every counted jump, i.e. after `addJump`.
 * Conditions are "at least", so older saves catch up.
 * @returns {{ progress: object, awarded: string[] }}
 */
export function checkInstantBadges(progress, nowIso) {
  const jumps = progress.jumps ?? 0;
  return award(
    progress,
    { firstJump: jumps >= 1, jumpMouse: jumps >= JUMPS_FOR_JUMP_MOUSE },
    nowIso,
  );
}

/**
 * Badges at ride end. Call only for FINISHED rides and AFTER `applyFinishedRide`; aborted rides
 * do not call it (the caller ensures this, rule 40).
 * Oxer pro and combination pro come exclusively from the ride result, never from stored data.
 * A stored course with 3 stars also counts as clean.
 * @returns {{ progress: object, awarded: string[] }}
 */
export function checkRideEndBadges(progress, result, nowIso) {
  return award(
    progress,
    {
      clean: result?.faults?.total === 0 || anyCourseThreeStars(progress),
      oxerPro: result?.cleanOxer === true,
      comboPro: result?.cleanCombination === true,
      allOpen: (progress.unlocked ?? 1) >= COURSE_COUNT,
      starRider: allCoursesThreeStars(progress),
      busy: (progress.finishedRides ?? 0) >= RIDES_FOR_BUSY,
    },
    nowIso,
  );
}
