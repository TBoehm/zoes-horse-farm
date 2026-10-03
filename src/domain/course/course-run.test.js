import { describe, expect, it } from 'vitest';
import { createCourseRun, MISSING_HINT_MS } from './course-run.js';
import { COURSES } from './courses.js';

const el = (id, kind, x, z) => ({
  id,
  kind,
  height: 0.7,
  spread: kind === 'oxer' ? 0.8 : 0,
  x,
  z,
  rot: 0,
});

// Test course: 1 vertical, 2 oxer, 3 combination (vertical / oxer), all jump direction +z
function testCourse(overrides = {}) {
  return {
    id: 9,
    allowedTimeS: 30,
    start: { a: [-3, -25], b: [3, -25], dir: [0, 1] },
    finish: { a: [7, -20], b: [13, -20], dir: [0, -1] },
    obstacles: [
      { number: 1, elements: [el('v1', 'vertical', 0, -10)], directed: true },
      { number: 2, elements: [el('o2', 'oxer', 0, 5)], directed: true },
      {
        number: 3,
        elements: [el('k3a', 'vertical', 0, 15), el('k3b', 'oxer', 0, 22.3)],
        directed: true,
      },
    ],
    ...overrides,
  };
}

const START_PREV = { x: 0, z: -26 };
const START_NEXT = { x: 0, z: -24 };
const FINISH_PREV = { x: 10, z: -19 };
const FINISH_NEXT = { x: 10, z: -21 };

function crossStart(run, t = 1000) {
  return run.onLineCross(START_PREV, START_NEXT, t);
}
function crossFinish(run, t) {
  return run.onLineCross(FINISH_PREV, FINISH_NEXT, t);
}
/** Crosses a line at its middle in its direction. */
function crossLine(run, line, t) {
  const mx = (line.a[0] + line.b[0]) / 2;
  const mz = (line.a[1] + line.b[1]) / 2;
  const [dx, dz] = line.dir;
  return run.onLineCross({ x: mx - dx, z: mz - dz }, { x: mx + dx, z: mz + dz }, t);
}
function riding(course = testCourse(), t = 1000) {
  const run = createCourseRun(course);
  crossStart(run, t);
  return run;
}
/** Jumps obstacles 1 and 2 cleanly. */
function toCombination(run) {
  run.onLanded('v1', 1, false);
  run.onLanded('o2', 1, false);
  return run;
}
function cleanRide(run) {
  toCombination(run);
  run.onLanded('k3a', 1, false);
  run.onLanded('k3b', 1, false);
  return run;
}
// Horse states around part b (at z = 22.3)
const horseApproachingB = { x: 0, z: 17, heading: 0 };
const horseNearBTurning = { x: 3, z: 19, heading: Math.PI / 2 };
const horseAwayFromB = { x: 8, z: 8, heading: Math.PI };

describe('Pre-start (rule 26)', () => {
  it('begins in pre-start without time and faults, obstacle 1 highlighted', () => {
    const run = createCourseRun(testCourse());
    expect(run.phase).toBe('prestart');
    expect(run.timeMs).toBe(0);
    expect(run.faults).toEqual({ knockdowns: 0, refusals: 0, time: 0, total: 0 });
    expect(run.highlight).toEqual({ elementId: 'v1', number: 1 });
    expect(run.current).toEqual({ obstacleIndex: 0, part: 0, elementId: 'v1' });
    expect(run.nextLabel).toBe(1);
    expect(run.finishMarked).toBe(false);
    expect(run.missingHint).toBeNull();
    expect(run.result).toBeNull();
  });

  it('highlights part a when a combination is the first obstacle', () => {
    const course = testCourse();
    course.obstacles = [{ ...course.obstacles[2], number: 1 }];
    expect(createCourseRun(course).highlight).toEqual({ elementId: 'k3a', number: 1 });
  });

  it('time does not run in pre-start', () => {
    const run = createCourseRun(testCourse());
    run.update({ x: 0, z: -30, heading: 0 }, 50000);
    expect(run.timeMs).toBe(0);
    expect(run.faults.time).toBe(0);
  });

  it('no refusal in pre-start, not even at obstacle 1', () => {
    const run = createCourseRun(testCourse());
    expect(run.rules.canRefuse('v1', 1)).toBe(false);
    expect(run.rules.canRefuse('o2', 1)).toBe(false);
  });

  it('jumps in pre-start do not count; rebuild a fallen pole after about 3 s', () => {
    const run = createCourseRun(testCourse());
    expect(run.onLanded('v1', 1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    expect(run.onLanded('v1', 1, false)).toEqual({ scored: false, rebuildAfterS: null });
    expect(run.faults.total).toBe(0);
    expect(run.current.elementId).toBe('v1');
  });

  it('refusal reports in pre-start are ignored', () => {
    const run = createCourseRun(testCourse());
    run.onRefusal('v1', 1);
    expect(run.faults.refusals).toBe(0);
  });

  it('the finish line does nothing in pre-start', () => {
    const run = createCourseRun(testCourse());
    expect(crossFinish(run, 500)).toBeNull();
    expect(run.phase).toBe('prestart');
    expect(run.missingHint).toBeNull();
  });
});

describe('Start and finish line (rules 26, 27)', () => {
  it('start line in riding direction starts the ride and the time', () => {
    const run = createCourseRun(testCourse());
    expect(crossStart(run, 2000)).toBe('start');
    expect(run.phase).toBe('riding');
    expect(run.timeMs).toBe(0);
    run.update({ x: 0, z: -20, heading: 0 }, 3234);
    expect(run.timeMs).toBe(1234);
  });

  it('start line against the riding direction does not count', () => {
    const run = createCourseRun(testCourse());
    expect(run.onLineCross(START_NEXT, START_PREV, 1000)).toBeNull();
    expect(run.phase).toBe('prestart');
  });

  it('passing beside the line does not count', () => {
    const run = createCourseRun(testCourse());
    expect(run.onLineCross({ x: 4, z: -26 }, { x: 4, z: -24 }, 1000)).toBeNull();
    expect(run.phase).toBe('prestart');
  });

  it('movement without crossing does not count', () => {
    const run = createCourseRun(testCourse());
    expect(run.onLineCross({ x: 0, z: -27 }, { x: 0, z: -25.5 }, 1000)).toBeNull();
    expect(run.phase).toBe('prestart');
  });

  it('start line during the ride does nothing', () => {
    const run = riding(testCourse(), 1000);
    expect(crossStart(run, 5000)).toBeNull();
    run.update({ x: 0, z: -20, heading: 0 }, 6000);
    expect(run.timeMs).toBe(5000);
  });

  it('finish line against the riding direction does nothing', () => {
    const run = cleanRide(riding());
    expect(run.onLineCross(FINISH_NEXT, FINISH_PREV, 9000)).toBeNull();
    expect(run.phase).toBe('riding');
  });

  it('finish line after all obstacles ends the ride and stops the time', () => {
    const run = cleanRide(riding(testCourse(), 1000));
    expect(crossFinish(run, 26271)).toBe('finish');
    expect(run.phase).toBe('finished');
    expect(run.timeMs).toBe(25271);
    run.update({ x: 10, z: -25, heading: Math.PI }, 90000);
    expect(run.timeMs).toBe(25271);
    expect(run.result.timeCs).toBe(2527);
  });
});

describe('Order, highlighting, jump direction (rules 27–29)', () => {
  it('a scored jump over the current obstacle advances to the next', () => {
    const run = riding();
    expect(run.onLanded('v1', 1, false)).toEqual({ scored: true, rebuildAfterS: null });
    expect(run.highlight).toEqual({ elementId: 'o2', number: 2 });
    expect(run.nextLabel).toBe(2);
  });

  it('wrong obstacle: no scoring, correct one stays highlighted, rebuild after 3 s', () => {
    const run = riding();
    expect(run.onLanded('o2', 1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    expect(run.onLanded('o2', 1, false)).toEqual({ scored: false, rebuildAfterS: null });
    expect(run.highlight).toEqual({ elementId: 'v1', number: 1 });
    expect(run.faults.total).toBe(0);
  });

  it('correct obstacle against the jump direction counts as a wrong obstacle', () => {
    const run = riding();
    expect(run.onLanded('v1', -1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    expect(run.current.elementId).toBe('v1');
    expect(run.faults.total).toBe(0);
  });

  it('unknown elements are ignored', () => {
    const run = riding();
    expect(run.onLanded('zzz', 1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    expect(run.current.elementId).toBe('v1');
  });

  it('refusal only at the current obstacle, in jump direction, during the ride', () => {
    const run = riding();
    expect(run.rules.canRefuse('v1', 1)).toBe(true);
    expect(run.rules.canRefuse('v1', -1)).toBe(false);
    expect(run.rules.canRefuse('o2', 1)).toBe(false);
    expect(run.rules.canRefuse('k3b', 1)).toBe(false);
  });

  it('after the last obstacle: no highlight, finish marked, label "finish"', () => {
    const run = cleanRide(riding());
    expect(run.current).toBeNull();
    expect(run.highlight).toBeNull();
    expect(run.finishMarked).toBe(true);
    expect(run.nextLabel).toBe('finish');
    expect(run.rules.canRefuse('k3b', 1)).toBe(false);
  });
});

describe('Fault points (rule 32)', () => {
  it('knockdown at the current obstacle: 4 faults, counts as jumped, pole stays down', () => {
    const run = riding();
    expect(run.onLanded('v1', 1, true)).toEqual({ scored: true, rebuildAfterS: null });
    expect(run.faults).toEqual({ knockdowns: 1, refusals: 0, time: 0, total: 4 });
    expect(run.current.elementId).toBe('o2');
    expect(run.drainRebuilds()).toEqual([]);
  });

  it('every refusal counts 4, also the second and further ones; no elimination', () => {
    const run = riding();
    run.onRefusal('v1', 1);
    run.onRefusal('v1', 1);
    run.onRefusal('v1', 1);
    expect(run.faults).toEqual({ knockdowns: 0, refusals: 3, time: 0, total: 12 });
    expect(run.phase).toBe('riding');
    expect(run.current.elementId).toBe('v1');
    // a jump shortly after the refusal is scored normally
    expect(run.onLanded('v1', 1, false).scored).toBe(true);
  });

  it('refusal reports at other obstacles are ignored', () => {
    const run = riding();
    run.onRefusal('o2', 1);
    run.onRefusal('v1', -1);
    expect(run.faults.total).toBe(0);
  });

  it('a pole down from a scored knockdown is not rebuilt after another jump', () => {
    const run = riding();
    run.onLanded('v1', 1, true);
    expect(run.onLanded('v1', -1, true)).toEqual({ scored: false, rebuildAfterS: null });
  });

  it('Zeitfehler laufen während des Ritts mit und gehen in die Summe ein', () => {
    const run = riding(testCourse(), 0);
    run.onLanded('v1', 1, true);
    run.update({ x: 0, z: 0, heading: 0 }, 30000);
    expect(run.faults.time).toBe(0);
    run.update({ x: 0, z: 0, heading: 0 }, 30010);
    expect(run.faults).toEqual({ knockdowns: 1, refusals: 0, time: 1, total: 5 });
  });
});

describe('Ziel vor allen Hindernissen (Regel 30)', () => {
  it('Ritt läuft weiter, Hinweis nennt das fehlende Hindernis', () => {
    const run = riding();
    run.onLanded('v1', 1, false);
    expect(crossFinish(run, 8000)).toBe('missing');
    expect(run.phase).toBe('riding');
    expect(run.missingHint).toBe(2);
  });

  it('Hinweis verschwindet nach einigen Sekunden', () => {
    const run = riding();
    crossFinish(run, 8000);
    run.update({ x: 10, z: -22, heading: Math.PI }, 8000 + MISSING_HINT_MS - 1);
    expect(run.missingHint).toBe(1);
    run.update({ x: 10, z: -22, heading: Math.PI }, 8000 + MISSING_HINT_MS);
    expect(run.missingHint).toBeNull();
  });

  it('Hinweis verschwindet nach dem nächsten gewerteten Sprung', () => {
    const run = riding();
    crossFinish(run, 8000);
    run.onLanded('v1', 1, false);
    expect(run.missingHint).toBeNull();
  });

  it('nennt bei der Kombination deren Nummer', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, false);
    crossFinish(run, 9000);
    expect(run.missingHint).toBe(3);
  });
});

describe('Zweifach-Kombination (Regel 31)', () => {
  it('b ist erst nach a an der Reihe; b allein gilt wie falsches Hindernis', () => {
    const run = toCombination(riding());
    expect(run.highlight).toEqual({ elementId: 'k3a', number: 3 });
    expect(run.rules.canRefuse('k3b', 1)).toBe(false);
    expect(run.onLanded('k3b', 1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    expect(run.faults.total).toBe(0);
    expect(run.current).toEqual({ obstacleIndex: 2, part: 0, elementId: 'k3a' });
  });

  it('nach a ist b an der Reihe (gleiche Nummer)', () => {
    const run = toCombination(riding());
    expect(run.onLanded('k3a', 1, false).scored).toBe(true);
    expect(run.current).toEqual({ obstacleIndex: 2, part: 1, elementId: 'k3b' });
    expect(run.highlight).toEqual({ elementId: 'k3b', number: 3 });
    expect(run.nextLabel).toBe(3);
    expect(run.rules.canRefuse('k3b', 1)).toBe(true);
    expect(run.rules.canRefuse('k3a', 1)).toBe(false);
  });

  it('Abwurf an a: 4 Fehler, weiter mit b', () => {
    const run = toCombination(riding());
    expect(run.onLanded('k3a', 1, true)).toEqual({ scored: true, rebuildAfterS: null });
    expect(run.current.elementId).toBe('k3b');
    expect(run.faults.knockdowns).toBe(1);
  });

  it('Verweigerung an b: 4 Fehler, zurück auf a, a und b sofort wieder aufbauen', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, true);
    run.onRefusal('k3b', 1);
    expect(run.faults).toEqual({ knockdowns: 1, refusals: 1, time: 0, total: 8 });
    expect(run.current).toEqual({ obstacleIndex: 2, part: 0, elementId: 'k3a' });
    expect(run.drainRebuilds().sort()).toEqual(['k3a', 'k3b']);
    expect(run.drainRebuilds()).toEqual([]);
  });

  it('Verweigerung an a: 4 Fehler, bleibt auf a, beide aufbauen', () => {
    const run = toCombination(riding());
    run.onRefusal('k3a', 1);
    expect(run.faults.refusals).toBe(1);
    expect(run.current.elementId).toBe('k3a');
    expect(run.drainRebuilds().sort()).toEqual(['k3a', 'k3b']);
  });

  it('Abwürfe an a und b zählen je einzeln aus allen Anläufen', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, true);
    run.onRefusal('k3b', 1);
    run.drainRebuilds();
    run.onLanded('k3a', 1, true);
    run.onLanded('k3b', 1, true);
    expect(run.faults).toEqual({ knockdowns: 3, refusals: 1, time: 0, total: 16 });
    expect(run.current).toBeNull();
  });

  it('Abwenden nach a: zurück auf a ohne Fehler, a und b aufbauen', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, true);
    run.update(horseAwayFromB, 9000);
    expect(run.current).toEqual({ obstacleIndex: 2, part: 0, elementId: 'k3a' });
    expect(run.faults).toEqual({ knockdowns: 1, refusals: 0, time: 0, total: 4 });
    expect(run.drainRebuilds().sort()).toEqual(['k3a', 'k3b']);
  });

  it('kein Abwenden, solange das Pferd b anreitet oder näher als der Anreitabstand ist', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, false);
    run.update(horseApproachingB, 9000);
    expect(run.current.elementId).toBe('k3b');
    run.update(horseNearBTurning, 9100);
    expect(run.current.elementId).toBe('k3b');
    // weit weg, aber auf b zu: nicht im Anreitabstand → gilt als abgewendet
    run.update({ x: 0, z: 5, heading: 0 }, 9200);
    expect(run.current.elementId).toBe('k3a');
  });

  it('nach dem Abwenden wird a erneut gewertet', () => {
    const run = toCombination(riding());
    run.onLanded('k3a', 1, false);
    run.update(horseAwayFromB, 9000);
    expect(run.onLanded('k3a', 1, false).scored).toBe(true);
    expect(run.current.elementId).toBe('k3b');
  });
});

describe('Ergebnis', () => {
  it('liefert Zeit, aufgeschlüsselte Fehler und Sterne', () => {
    const run = riding(testCourse(), 0);
    run.onLanded('v1', 1, true);
    run.onLanded('o2', 1, false);
    run.onLanded('k3a', 1, false);
    run.onLanded('k3b', 1, false);
    crossFinish(run, 34010);
    expect(run.result).toEqual({
      courseId: 9,
      timeCs: 3401,
      faults: { knockdowns: 1, refusals: 0, timeFaults: 2, total: 6 },
      stars: 1,
      cleanOxer: true,
      cleanCombination: true,
    });
    expect(run.faults).toEqual({ knockdowns: 1, refusals: 0, time: 2, total: 6 });
  });

  it('fehlerfreier Ritt: 3 Sterne', () => {
    const run = cleanRide(riding(testCourse(), 0));
    crossFinish(run, 20000);
    expect(run.result.stars).toBe(3);
    expect(run.result.faults.total).toBe(0);
  });

  it('nach dem Ziel wird nichts mehr gewertet', () => {
    const run = cleanRide(riding(testCourse(), 0));
    crossFinish(run, 20000);
    expect(run.onLanded('v1', 1, true)).toEqual({ scored: false, rebuildAfterS: 3 });
    run.onRefusal('v1', 1);
    expect(run.result.faults.total).toBe(0);
    expect(crossFinish(run, 25000)).toBeNull();
  });

  describe('cleanOxer (Regel 49)', () => {
    const finish = (run) => {
      crossFinish(run, 20000);
      return run.result.cleanOxer;
    };

    it('wahr bei einem gewerteten Oxer ohne Verweigerung und ohne Abwurf', () => {
      const run = riding();
      run.onLanded('v1', 1, true);
      run.onLanded('o2', 1, false);
      run.onLanded('k3a', 1, false);
      run.onLanded('k3b', 1, true);
      expect(finish(run)).toBe(true);
    });

    it('falsch, wenn am Oxer verweigert wurde (auch wenn danach sauber)', () => {
      const run = riding();
      run.onLanded('v1', 1, false);
      run.onRefusal('o2', 1);
      run.onLanded('o2', 1, false);
      run.onLanded('k3a', 1, false);
      run.onLanded('k3b', 1, true);
      expect(finish(run)).toBe(false);
    });

    it('ein Oxer als Teil der Kombination zählt mit', () => {
      const run = riding();
      run.onLanded('v1', 1, false);
      run.onLanded('o2', 1, true);
      run.onLanded('k3a', 1, true);
      run.onLanded('k3b', 1, false);
      expect(finish(run)).toBe(true);
    });

    it('ungewertete Sprünge über einen Oxer zählen nicht', () => {
      const run = riding();
      run.onLanded('o2', 1, false);
      run.onLanded('v1', 1, false);
      run.onLanded('o2', 1, true);
      run.onLanded('k3a', 1, false);
      run.onRefusal('k3b', 1);
      run.onLanded('k3a', 1, false);
      run.onLanded('k3b', 1, false);
      expect(finish(run)).toBe(false);
    });

    it('falsch ohne Oxer im Parcours', () => {
      const run = createCourseRun(COURSES[0]);
      crossLine(run, COURSES[0].start, 0);
      for (const o of COURSES[0].obstacles) run.onLanded(o.elements[0].id, 1, false);
      crossLine(run, COURSES[0].finish, 30000);
      expect(run.phase).toBe('finished');
      expect(run.result.cleanOxer).toBe(false);
      expect(run.result.cleanCombination).toBe(false);
    });
  });

  describe('cleanCombination (Regel 49)', () => {
    const finish = (run) => {
      crossFinish(run, 20000);
      return run.result.cleanCombination;
    };

    it('wahr bei a und b ohne Verweigerung und Abwurf', () => {
      expect(finish(cleanRide(riding()))).toBe(true);
    });

    it('wahr nach fehlerfreiem Abwenden und sauberem neuen Anlauf', () => {
      const run = toCombination(riding());
      run.onLanded('k3a', 1, false);
      run.update(horseAwayFromB, 9000);
      run.onLanded('k3a', 1, false);
      run.onLanded('k3b', 1, false);
      expect(finish(run)).toBe(true);
    });

    it('falsch nach einer Verweigerung an der Kombination', () => {
      const run = toCombination(riding());
      run.onLanded('k3a', 1, false);
      run.onRefusal('k3b', 1);
      run.onLanded('k3a', 1, false);
      run.onLanded('k3b', 1, false);
      expect(finish(run)).toBe(false);
    });

    it('falsch nach einem Abwurf an a', () => {
      const run = toCombination(riding());
      run.onLanded('k3a', 1, true);
      run.onLanded('k3b', 1, false);
      expect(finish(run)).toBe(false);
    });
  });
});

describe('Parcours aus COURSES', () => {
  it.each(COURSES.map((c) => [c.id, c]))('Parcours %i lässt sich fehlerfrei beenden', (_id, c) => {
    const run = createCourseRun(c);
    expect(crossLine(run, c.start, 0)).toBe('start');
    for (const o of c.obstacles) {
      for (const e of o.elements) expect(run.onLanded(e.id, 1, false).scored).toBe(true);
    }
    expect(crossLine(run, c.finish, 30000)).toBe('finish');
    expect(run.phase).toBe('finished');
    expect(run.result.courseId).toBe(c.id);
    expect(run.result.stars).toBe(3);
    expect(run.result.cleanCombination).toBe(c.id === 5);
    expect(run.result.cleanOxer).toBe(c.id >= 3);
  });
});
