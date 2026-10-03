// Auszeichnungen (Konzept Regel 49): reine Prüf-Logik, unveränderlich, ohne DOM.
// Die Absprung-Hilfe hat keinen Einfluss auf die Vergabe (Regel 42).

export const JUMPS_FOR_JUMP_MOUSE = 100;
export const RIDES_FOR_BUSY = 10;
export const COURSE_COUNT = 5;

/** Reihenfolge wie in Regel 49. `award`: 'instant' = nach gezähltem Sprung, 'rideEnd' = bei Rittende. */
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
  for (let i = 1; i <= COURSE_COUNT; i++) {
    if (courses[String(i)]?.stars !== 3) return false;
  }
  return true;
}

function anyCourseThreeStars(progress) {
  return Object.values(progress.courses ?? {}).some((c) => c?.stars === 3);
}

/** Vergibt alle noch fehlenden Auszeichnungen aus `conditions` (id → bool) mit Datum `nowIso`. */
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
 * Sofort-Auszeichnungen (Erster Sprung, Springmaus). Nach jedem gezählten Sprung aufrufen, also
 * nach `addJump`. Bedingungen gelten als „mindestens", alte Spielstände werden so nachgeholt.
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
 * Auszeichnungen bei Rittende. Nur für BEENDETE Ritte und NACH `applyFinishedRide` aufrufen;
 * abgebrochene Ritte rufen sie nicht auf (das regelt der Aufrufer, Regel 40).
 * Oxer-Profi und Kombi-Könner kommen ausschließlich aus dem Ritt-Ergebnis, nie aus gespeicherten
 * Daten. Fehlerfrei zählt auch ein gespeicherter Parcours mit 3 Sternen.
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
