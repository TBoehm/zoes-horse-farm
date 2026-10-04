// Ride state machine for a course (concept rules 22, 26–33, 49; contract "Parcours" in
// docs/specs/springreiten-trainer/architecture.md). Pure: time comes in as timeMs.
import { TUNING } from '../sim/tuning.js';
import { approachInfo } from '../sim/geometry.js';
import {
  KNOCKDOWN_FAULTS,
  REFUSAL_FAULTS,
  starsFor,
  timeFaults,
  toCentiseconds,
} from './scoring.js';

/**
 * Does the segment prev→next cross the line in direction line.dir?
 * Returns true only when changing from the back side (−dir) to the front side.
 */
function crossesLine(line, prev, next) {
  const [ax, az] = line.a;
  const ex = line.b[0] - ax;
  const ez = line.b[1] - az;
  // Normal of the line, oriented so that it points in the riding direction
  let nx = -ez;
  let nz = ex;
  if (nx * line.dir[0] + nz * line.dir[1] < 0) {
    nx = -nx;
    nz = -nz;
  }
  const s0 = (prev.x - ax) * nx + (prev.z - az) * nz;
  const s1 = (next.x - ax) * nx + (next.z - az) * nz;
  if (!(s0 < 0 && s1 >= 0)) return false;
  const t = s0 / (s0 - s1);
  const px = prev.x + (next.x - prev.x) * t - ax;
  const pz = prev.z + (next.z - prev.z) * t - az;
  const u = (px * ex + pz * ez) / (ex * ex + ez * ez);
  return u >= 0 && u <= 1;
}

export function createCourseRun(course, { tuning = TUNING } = {}) {
  const obstacles = course.obstacles;
  const allowedMs = course.allowedTimeS * 1000;

  let phase = 'prestart';
  let index = 0; // obstacle whose turn it is (= obstacles.length → finish)
  let part = 0; // 0 = a or single jump, 1 = part b of a combination
  let startMs = 0;
  let nowMs = 0;
  let finalMs = 0;
  let knockdowns = 0;
  let refusals = 0;
  let missingHint = null;
  let hintSince = 0;
  let result = null;
  const rebuilds = new Set();
  const lying = new Set(); // elements with a scored knockdown whose poles stay down
  // per element: scored jump / scored knockdown / refused
  const scoredJumps = new Set();
  const scoredKnocks = new Set();
  const refused = new Set();

  function currentElement() {
    if (index >= obstacles.length) return null;
    return obstacles[index].elements[part];
  }

  function rideMs() {
    if (phase === 'prestart') return 0;
    if (phase === 'finished') return finalMs;
    return Math.max(0, nowMs - startMs);
  }

  function overMs(ms) {
    return toCentiseconds(ms) * 10 - allowedMs;
  }

  function timeFaultsNow() {
    return phase === 'prestart' ? 0 : timeFaults(overMs(rideMs()));
  }

  function faults() {
    const time = timeFaultsNow();
    const total = knockdowns * KNOCKDOWN_FAULTS + refusals * REFUSAL_FAULTS + time;
    return { knockdowns, refusals, timeFaults: time, total };
  }

  function isCurrent(elementId, dir) {
    const current = currentElement();
    return phase === 'riding' && current !== null && current.id === elementId && dir === 1;
  }

  /** Re-approach the combination: back to a, rebuild a and b immediately. */
  function restartCombination() {
    part = 0;
    for (const e of obstacles[index].elements) {
      rebuilds.add(e.id);
      lying.delete(e.id);
    }
  }

  function advance() {
    const obstacle = obstacles[index];
    if (part + 1 < obstacle.elements.length) {
      part += 1;
    } else {
      index += 1;
      part = 0;
    }
  }

  function cleanAt(ids) {
    return ids.every((id) => scoredJumps.has(id) && !scoredKnocks.has(id) && !refused.has(id));
  }

  function finish(timeMs) {
    nowMs = timeMs;
    finalMs = Math.max(0, timeMs - startMs);
    phase = 'finished';
    missingHint = null;
    const f = faults();
    const oxers = obstacles.flatMap((o) => o.elements.filter((e) => e.kind === 'oxer'));
    const combos = obstacles.filter((o) => o.elements.length > 1);
    result = {
      courseId: course.id,
      timeCs: toCentiseconds(finalMs),
      faults: { knockdowns, refusals, timeFaults: f.timeFaults, total: f.total },
      stars: starsFor(f.total),
      cleanOxer: oxers.some((e) => cleanAt([e.id])),
      cleanCombination: combos.some((o) => cleanAt(o.elements.map((e) => e.id))),
    };
  }

  const run = {
    get phase() {
      return phase;
    },
    get current() {
      const e = currentElement();
      return e ? { obstacleIndex: index, part, elementId: e.id } : null;
    },
    get highlight() {
      const e = currentElement();
      return e ? { elementId: e.id, number: obstacles[index].number } : null;
    },
    get finishMarked() {
      return currentElement() === null;
    },
    /** Number of the obstacle that is due, "3b" for part b of a combination, else 'finish'. */
    get nextLabel() {
      if (!currentElement()) return 'finish';
      const { number } = obstacles[index];
      return part === 1 ? `${number}b` : number;
    },
    get faults() {
      return faults();
    },
    get timeMs() {
      return rideMs();
    },
    /** True once the allowed time is exceeded (same truncated hundredths as the time faults). */
    get overTime() {
      return timeFaultsNow() > 0;
    },
    get missingHint() {
      return missingHint;
    },
    get result() {
      return result;
    },

    rules: {
      canRefuse(elementId, dir) {
        return isCurrent(elementId, dir);
      },
    },

    /**
     * Checks the start and finish lines; returns 'start' | 'finish' | 'missing' | null.
     * `backwards`: the horse moved backwards (rein-back) – a line crossed that way does not
     *   count.
     */
    onLineCross(prev, next, timeMs, { backwards = false } = {}) {
      if (backwards) return null;
      if (phase === 'prestart' && crossesLine(course.start, prev, next)) {
        phase = 'riding';
        startMs = timeMs;
        nowMs = timeMs;
        return 'start';
      }
      if (phase === 'riding' && crossesLine(course.finish, prev, next)) {
        if (currentElement() === null) {
          finish(timeMs);
          return 'finish';
        }
        missingHint = obstacles[index].number;
        hintSince = timeMs;
        return 'missing';
      }
      return null;
    },

    onLanded(elementId, dir, knocked) {
      if (isCurrent(elementId, dir)) {
        scoredJumps.add(elementId);
        if (knocked) {
          knockdowns += 1;
          scoredKnocks.add(elementId);
          lying.add(elementId);
        }
        missingHint = null;
        advance();
        return { scored: true, rebuildAfterS: null };
      }
      // unscored; poles of a scored knockdown stay down until the ride ends
      const rebuild = knocked && !(phase === 'riding' && lying.has(elementId));
      return { scored: false, rebuildAfterS: rebuild ? tuning.rebuildDelayS : null };
    },

    onRefusal(elementId, dir) {
      if (!isCurrent(elementId, dir)) return;
      refusals += 1;
      refused.add(elementId);
      if (obstacles[index].elements.length > 1) restartCombination();
    },

    update(horse, timeMs) {
      if (phase !== 'riding') return;
      nowMs = timeMs;
      if (missingHint !== null && timeMs - hintSince >= tuning.missingHintS * 1000)
        missingHint = null;
      // Turning away between a and b (rule 31)
      if (part === 1) {
        const b = currentElement();
        const info = approachInfo(b, horse, tuning.approachDistance);
        const far = Math.hypot(horse.x - b.x, horse.z - b.z) > tuning.approachDistance;
        if (!(info && info.approaching) && far) restartCombination();
      }
    },

    /** IDs of the elements that must be rebuilt immediately (once). */
    drainRebuilds() {
      const ids = [...rebuilds];
      rebuilds.clear();
      return ids;
    },
  };
  return run;
}
