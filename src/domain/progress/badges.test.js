import { describe, expect, it } from 'vitest';
import { BADGES, checkInstantBadges, checkRideEndBadges } from './badges.js';
import { addJump, applyFinishedRide, sanitizeProgress } from './progress.js';

const NOW = '2026-10-03T12:00:00.000Z';
const LATER = '2026-10-04T12:00:00.000Z';

const fresh = () => sanitizeProgress(undefined);

function result(over = {}) {
  const { total = 3, ...rest } = over;
  return {
    courseId: 1,
    timeCs: 6000,
    faults: { knockdowns: total, refusals: 0, timeFaults: 0, total },
    stars: total === 0 ? 3 : 2,
    cleanOxer: false,
    cleanCombination: false,
    ...rest,
  };
}

/** Like the caller: apply the ride, then check the ride-end badges. */
function finishRide(progress, res, now = NOW) {
  const applied = applyFinishedRide(progress, res);
  return checkRideEndBadges(applied.progress, res, now);
}

function withCourses(stars) {
  const courses = {};
  stars.forEach((s, i) => {
    if (s) courses[String(i + 1)] = { faults: s === 3 ? 0 : 2, timeCs: 5000, stars: s };
  });
  return { ...fresh(), courses };
}

describe('BADGES', () => {
  it('contains the 8 badges in the order of rule 49', () => {
    expect(BADGES.map((b) => b.id)).toEqual([
      'firstJump',
      'jumpMouse',
      'clean',
      'oxerPro',
      'comboPro',
      'allOpen',
      'starRider',
      'busy',
    ]);
  });

  it('award timing and i18n keys are correct', () => {
    for (const b of BADGES) {
      expect(b.nameKey).toBe(`badge.${b.id}.name`);
      expect(b.conditionKey).toBe(`badge.${b.id}.condition`);
    }
    expect(BADGES.filter((b) => b.award === 'instant').map((b) => b.id)).toEqual([
      'firstJump',
      'jumpMouse',
    ]);
    expect(BADGES.filter((b) => b.award === 'rideEnd')).toHaveLength(6);
  });
});

describe('Instant badges', () => {
  it('first jump is awarded after the first counted jump, not before', () => {
    expect(checkInstantBadges(fresh(), NOW).awarded).toEqual([]);
    const r = checkInstantBadges(addJump(fresh()), NOW);
    expect(r.awarded).toEqual(['firstJump']);
    expect(r.progress.badges).toEqual({ firstJump: NOW });
  });

  it('jump mouse at exactly 100, not at 99', () => {
    const p99 = { ...fresh(), jumps: 99, badges: { firstJump: NOW } };
    expect(checkInstantBadges(p99, LATER).awarded).toEqual([]);
    const r = checkInstantBadges(addJump(p99), LATER);
    expect(r.awarded).toEqual(['jumpMouse']);
    expect(r.progress.badges).toEqual({ firstJump: NOW, jumpMouse: LATER });
  });

  it('is awarded only once: second fulfilment awards nothing and keeps the date', () => {
    const first = checkInstantBadges({ ...fresh(), jumps: 1 }, NOW);
    const second = checkInstantBadges(addJump(first.progress), LATER);
    expect(second.awarded).toEqual([]);
    expect(second.progress.badges.firstJump).toBe(NOW);
    expect(second.progress.badges).toEqual(first.progress.badges);
  });

  it('catches up both from an old save with 150 jumps on the next jump', () => {
    const old = { ...fresh(), jumps: 150 };
    const r = checkInstantBadges(addJump(old), NOW);
    expect(r.awarded).toEqual(['firstJump', 'jumpMouse']);
  });

  it('does not award ride-end badges', () => {
    const p = { ...fresh(), jumps: 500, finishedRides: 50, unlocked: 5 };
    expect(checkInstantBadges(p, NOW).awarded).toEqual(['firstJump', 'jumpMouse']);
  });

  it('does not mutate the input', () => {
    const p = { ...fresh(), jumps: 5 };
    const r = checkInstantBadges(p, NOW);
    expect(p.badges).toEqual({});
    expect(r.progress).not.toBe(p);
  });

  it('returns the same object when nothing is awarded', () => {
    const p = fresh();
    expect(checkInstantBadges(p, NOW).progress).toBe(p);
  });
});

describe('Ride-end badges', () => {
  it('clean: ride with 0 faults', () => {
    const r = finishRide(fresh(), result({ total: 0 }));
    expect(r.awarded).toContain('clean');
    expect(r.progress.badges.clean).toBe(NOW);
  });

  it('clean: not awarded with faults and no 3-star course', () => {
    expect(finishRide(fresh(), result({ total: 1 })).awarded).not.toContain('clean');
  });

  it('clean: a stored 3-star course is enough (catch-up)', () => {
    const old = withCourses([null, 3]);
    const r = finishRide(old, result({ courseId: 3, total: 6, stars: 1 }));
    expect(r.awarded).toEqual(['clean']);
  });

  it('clean: a 3-star entry for a course outside 1..5 does not count', () => {
    const stray = { ...fresh(), courses: { 7: { faults: 0, timeCs: 5000, stars: 3 } } };
    expect(finishRide(stray, result({ total: 4, stars: 2 })).awarded).not.toContain('clean');
  });

  it('oxer pro only from the ride result', () => {
    expect(finishRide(fresh(), result({ cleanOxer: true })).awarded).toEqual(['oxerPro']);
    expect(finishRide(fresh(), result({ cleanOxer: false })).awarded).toEqual([]);
  });

  it('combination pro only from the ride result', () => {
    expect(finishRide(fresh(), result({ cleanCombination: true })).awarded).toEqual(['comboPro']);
    expect(finishRide(fresh(), result({ cleanCombination: false })).awarded).toEqual([]);
  });

  it('oxer pro and combination pro are not derived from stored data', () => {
    const old = {
      ...withCourses([3, 3, 3, 3, 3]),
      unlocked: 5,
      jumps: 900,
      finishedRides: 40,
    };
    const r = finishRide(old, result({ courseId: 5, total: 0 }));
    expect(r.awarded).not.toContain('oxerPro');
    expect(r.awarded).not.toContain('comboPro');
  });

  it('all open at unlocked >= 5, not at 4', () => {
    const p3 = { ...fresh(), unlocked: 3 };
    expect(finishRide(p3, result({ courseId: 3 })).awarded).not.toContain('allOpen');
    // Riding course 4 unlocks 5 and awards in the same call
    const r = finishRide({ ...fresh(), unlocked: 4 }, result({ courseId: 4 }));
    expect(r.awarded).toContain('allOpen');
  });

  it('star rider: all 5 courses with 3 stars, not with 4', () => {
    const four = withCourses([3, 3, 3, 3, null]);
    expect(finishRide(four, result({ courseId: 1, total: 3 })).awarded).not.toContain('starRider');
    const r = finishRide(four, result({ courseId: 5, total: 0 }));
    expect(r.awarded).toContain('starRider');
  });

  it('star rider: a 2-star course prevents awarding', () => {
    const p = withCourses([3, 3, 2, 3, 3]);
    expect(finishRide(p, result({ courseId: 2, total: 5 })).awarded).not.toContain('starRider');
  });

  it('busy at 10 finished rides, not at 9', () => {
    const nine = { ...fresh(), finishedRides: 8 };
    expect(finishRide(nine, result()).awarded).not.toContain('busy');
    const ten = { ...fresh(), finishedRides: 9 };
    expect(finishRide(ten, result()).awarded).toContain('busy');
  });

  it('catches up busy from an old save with 10 rides on the next ride', () => {
    const old = { ...fresh(), finishedRides: 10 };
    expect(finishRide(old, result()).awarded).toEqual(['busy']);
  });

  it('is awarded only once: second fulfilment awards nothing', () => {
    const first = finishRide(
      fresh(),
      result({ total: 0, cleanOxer: true, cleanCombination: true }),
    );
    expect(first.awarded).toEqual(['clean', 'oxerPro', 'comboPro']);
    const second = finishRide(
      first.progress,
      result({ total: 0, cleanOxer: true, cleanCombination: true }),
      LATER,
    );
    expect(second.awarded).toEqual([]);
    expect(second.progress.badges).toEqual(first.progress.badges);
  });

  it('awards several at once in rule-49 order', () => {
    const p = { ...withCourses([3, 3, 3, 3, 3]), unlocked: 5, finishedRides: 20 };
    const r = finishRide(p, result({ courseId: 5, total: 0, cleanOxer: true }));
    expect(r.awarded).toEqual(['clean', 'oxerPro', 'allOpen', 'starRider', 'busy']);
  });

  it('acts on the freshly applied result (order: applyFinishedRide first)', () => {
    // 10th ride: finishedRides only becomes 10 through applyFinishedRide
    let p = { ...fresh(), finishedRides: 9 };
    p = applyFinishedRide(p, result()).progress;
    expect(checkRideEndBadges(p, result(), NOW).awarded).toEqual(['busy']);
  });

  it('does not mutate the input', () => {
    const p = { ...fresh(), finishedRides: 10 };
    checkRideEndBadges(p, result(), NOW);
    expect(p.badges).toEqual({});
  });

  it('does not award instant badges', () => {
    const p = { ...fresh(), jumps: 500 };
    expect(finishRide(p, result()).awarded).toEqual([]);
  });
});
