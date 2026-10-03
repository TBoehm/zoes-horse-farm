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

/** Wie der Aufrufer: Ritt einwerten, dann Rittende-Auszeichnungen prüfen. */
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
  it('enthält die 8 Auszeichnungen in Reihenfolge von Regel 49', () => {
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

  it('Vergabezeitpunkt und i18n-Schlüssel stimmen', () => {
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

describe('Sofort-Auszeichnungen', () => {
  it('Erster Sprung nach dem ersten gezählten Sprung, nicht davor', () => {
    expect(checkInstantBadges(fresh(), NOW).awarded).toEqual([]);
    const r = checkInstantBadges(addJump(fresh()), NOW);
    expect(r.awarded).toEqual(['firstJump']);
    expect(r.progress.badges).toEqual({ firstJump: NOW });
  });

  it('Springmaus bei genau 100, nicht bei 99', () => {
    const p99 = { ...fresh(), jumps: 99, badges: { firstJump: NOW } };
    expect(checkInstantBadges(p99, LATER).awarded).toEqual([]);
    const r = checkInstantBadges(addJump(p99), LATER);
    expect(r.awarded).toEqual(['jumpMouse']);
    expect(r.progress.badges).toEqual({ firstJump: NOW, jumpMouse: LATER });
  });

  it('ist einmalig: zweite Erfüllung vergibt nichts und behält das Datum', () => {
    const first = checkInstantBadges({ ...fresh(), jumps: 1 }, NOW);
    const second = checkInstantBadges(addJump(first.progress), LATER);
    expect(second.awarded).toEqual([]);
    expect(second.progress.badges.firstJump).toBe(NOW);
    expect(second.progress.badges).toEqual(first.progress.badges);
  });

  it('holt beide aus altem Spielstand mit 150 Sprüngen beim nächsten Sprung nach', () => {
    const old = { ...fresh(), jumps: 150 };
    const r = checkInstantBadges(addJump(old), NOW);
    expect(r.awarded).toEqual(['firstJump', 'jumpMouse']);
  });

  it('vergibt keine Rittende-Auszeichnungen', () => {
    const p = { ...fresh(), jumps: 500, finishedRides: 50, unlocked: 5 };
    expect(checkInstantBadges(p, NOW).awarded).toEqual(['firstJump', 'jumpMouse']);
  });

  it('verändert die Eingabe nicht', () => {
    const p = { ...fresh(), jumps: 5 };
    const r = checkInstantBadges(p, NOW);
    expect(p.badges).toEqual({});
    expect(r.progress).not.toBe(p);
  });

  it('gibt ohne Vergabe dasselbe Objekt zurück', () => {
    const p = fresh();
    expect(checkInstantBadges(p, NOW).progress).toBe(p);
  });
});

describe('Rittende-Auszeichnungen', () => {
  it('Fehlerfrei: Ritt mit 0 Fehlern', () => {
    const r = finishRide(fresh(), result({ total: 0 }));
    expect(r.awarded).toContain('clean');
    expect(r.progress.badges.clean).toBe(NOW);
  });

  it('Fehlerfrei: nicht bei Fehlern ohne 3-Sterne-Parcours', () => {
    expect(finishRide(fresh(), result({ total: 1 })).awarded).not.toContain('clean');
  });

  it('Fehlerfrei: gespeicherter Parcours mit 3 Sternen genügt (Nachholen)', () => {
    const old = withCourses([null, 3]);
    const r = finishRide(old, result({ courseId: 3, total: 6, stars: 1 }));
    expect(r.awarded).toEqual(['clean']);
  });

  it('Oxer-Profi nur aus dem Ritt-Ergebnis', () => {
    expect(finishRide(fresh(), result({ cleanOxer: true })).awarded).toEqual(['oxerPro']);
    expect(finishRide(fresh(), result({ cleanOxer: false })).awarded).toEqual([]);
  });

  it('Kombi-Könner nur aus dem Ritt-Ergebnis', () => {
    expect(finishRide(fresh(), result({ cleanCombination: true })).awarded).toEqual(['comboPro']);
    expect(finishRide(fresh(), result({ cleanCombination: false })).awarded).toEqual([]);
  });

  it('Oxer-Profi und Kombi-Könner werden nicht aus gespeicherten Daten abgeleitet', () => {
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

  it('Alles offen bei unlocked >= 5, nicht bei 4', () => {
    const p3 = { ...fresh(), unlocked: 3 };
    expect(finishRide(p3, result({ courseId: 3 })).awarded).not.toContain('allOpen');
    // Ritt auf Parcours 4 schaltet 5 frei und vergibt im selben Aufruf
    const r = finishRide({ ...fresh(), unlocked: 4 }, result({ courseId: 4 }));
    expect(r.awarded).toContain('allOpen');
  });

  it('Sternenreiter: alle 5 Parcours mit 3 Sternen, nicht bei 4', () => {
    const four = withCourses([3, 3, 3, 3, null]);
    expect(finishRide(four, result({ courseId: 1, total: 3 })).awarded).not.toContain('starRider');
    const r = finishRide(four, result({ courseId: 5, total: 0 }));
    expect(r.awarded).toContain('starRider');
  });

  it('Sternenreiter: ein 2-Sterne-Parcours verhindert die Vergabe', () => {
    const p = withCourses([3, 3, 2, 3, 3]);
    expect(finishRide(p, result({ courseId: 2, total: 5 })).awarded).not.toContain('starRider');
  });

  it('Fleißig bei 10 beendeten Ritten, nicht bei 9', () => {
    const nine = { ...fresh(), finishedRides: 8 };
    expect(finishRide(nine, result()).awarded).not.toContain('busy');
    const ten = { ...fresh(), finishedRides: 9 };
    expect(finishRide(ten, result()).awarded).toContain('busy');
  });

  it('holt Fleißig aus altem Spielstand mit 10 Ritten beim nächsten Ritt nach', () => {
    const old = { ...fresh(), finishedRides: 10 };
    expect(finishRide(old, result()).awarded).toEqual(['busy']);
  });

  it('ist einmalig: zweite Erfüllung vergibt nichts', () => {
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

  it('vergibt mehrere gleichzeitig in Regel-49-Reihenfolge', () => {
    const p = { ...withCourses([3, 3, 3, 3, 3]), unlocked: 5, finishedRides: 20 };
    const r = finishRide(p, result({ courseId: 5, total: 0, cleanOxer: true }));
    expect(r.awarded).toEqual(['clean', 'oxerPro', 'allOpen', 'starRider', 'busy']);
  });

  it('wirkt auf das frisch eingewertete Ergebnis (Reihenfolge: erst applyFinishedRide)', () => {
    // 10. Ritt: finishedRides wird erst durch applyFinishedRide 10
    let p = { ...fresh(), finishedRides: 9 };
    p = applyFinishedRide(p, result()).progress;
    expect(checkRideEndBadges(p, result(), NOW).awarded).toEqual(['busy']);
  });

  it('verändert die Eingabe nicht', () => {
    const p = { ...fresh(), finishedRides: 10 };
    checkRideEndBadges(p, result(), NOW);
    expect(p.badges).toEqual({});
  });

  it('vergibt Sofort-Auszeichnungen nicht', () => {
    const p = { ...fresh(), jumps: 500 };
    expect(finishRide(p, result()).awarded).toEqual([]);
  });
});
