import { describe, expect, it } from 'vitest';
import { COMBI_DISTANCE, TUNING } from './tuning.js';
import { approachInfo, toLocal } from './geometry.js';
import { speedBand, zoneForElement } from './jump.js';
import { createRidingSim } from './riding-sim.js';
import { createRng } from './rng.js';
import {
  DEG,
  brakeToHalt,
  countingRng,
  drive,
  insideBlock,
  makeElement,
  makeSim,
  obstaclesOf,
  ofType,
  placeBefore,
  pressAt,
  turnInPlace,
} from './test-utils.js';

const S = TUNING.speeds;
const TROT = { throttle: 0 };
const CANTER = { gallop: true };

/** Space in der Zonen-Mitte für das aktuelle Tempo. */
const atCenter = (sim, a) =>
  sim.zoneFor(a.elementId, a.dir, sim.horse.speed).far * 0.5 +
  sim.zoneFor(a.elementId, a.dir, sim.horse.speed).near * 0.5;

const untilQuiet = (s) => !s.horse.jump && !s.horse.refusal && s.horse.z > 4;

describe('Vertrag', () => {
  it('liefert horse, rails, approach, zoneFor, rebuild, rebuildAll', () => {
    const v = makeElement('vertical', 0.6, { id: 'v' });
    const o = makeElement('oxer', 0.7, { id: 'o', x: 10 });
    const sim = makeSim([v, o]);
    sim.reset({ x: 0, z: -10, heading: 0 });
    expect(Object.keys(sim.horse).sort()).toEqual(
      [
        'x',
        'z',
        'heading',
        'speed',
        'gait',
        'gallop',
        'y',
        'jump',
        'hop',
        'refusal',
        'turnRate',
      ].sort(),
    );
    expect(sim.horse.gait).toBe('halt');
    expect(sim.horse.gallop).toBe(false);
    expect(sim.rails).toBeInstanceOf(Map);
    expect(sim.rails.get('v')).toEqual([true]);
    expect(sim.rails.get('o')).toEqual([true, true]);
    expect(sim.approach).toMatchObject({ elementId: 'v', dir: 1 });
    expect(sim.approach.distance).toBeCloseTo(10, 9);
    const z = sim.zoneFor('v', 1, 5.8);
    expect(Object.keys(z).sort()).toEqual(['far', 'lastPoint', 'near', 'reach']);
    expect(sim.zoneFor('nope', 1, 5)).toBeNull();
    expect(sim.step(1 / 60, {})).toEqual([]);
  });

  it('approach: null bei abgewandtem Kurs, dir −1 von der anderen Seite', () => {
    const v = makeElement('vertical', 0.6, { id: 'v' });
    const sim = makeSim([v]);
    sim.reset({ x: 0, z: -5, heading: Math.PI });
    expect(sim.approach).toBeNull();
    sim.reset({ x: 0, z: 5, heading: Math.PI });
    expect(sim.approach).toMatchObject({ elementId: 'v', dir: -1 });
    sim.reset({ x: 0, z: -20, heading: 0 });
    expect(sim.approach).toBeNull();
  });
});

describe('Springbarkeit je Gangart (Regel 16)', () => {
  it('Schritt am Kreuz: Space bewirkt nichts, am letzten Absprungpunkt Verweigerung', () => {
    const c = makeElement('cross', 0.45);
    const sim = makeSim([c]);
    placeBefore(sim, c, 4, { speed: 1.5 });
    const { events } = drive(sim, pressAt({}, 1.2), { maxT: 6 });
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'hop')).toHaveLength(0);
    expect(ofType(events, 'refusal')).toEqual([
      { type: 'refusal', elementId: c.id, dir: 1, reason: 'gait' },
    ]);
  });

  it('Trab am Kreuz: Sprung mit Space in der Zone, ohne Abwurf', () => {
    const c = makeElement('cross', 0.45);
    const sim = makeSim([c]);
    placeBefore(sim, c, 6, { speed: S.trotMedium });
    const { events } = drive(sim, pressAt(TROT, atCenter), { until: untilQuiet });
    expect(ofType(events, 'takeoff')).toEqual([
      { type: 'takeoff', elementId: c.id, dir: 1, self: false, risk: 0 },
    ]);
    expect(ofType(events, 'landed')).toEqual([
      { type: 'landed', elementId: c.id, dir: 1, knocked: false },
    ]);
  });

  it('Trab am Steilsprung: Space bewirkt nichts, Verweigerung (stehen bleiben)', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    const { events } = drive(sim, pressAt(TROT, atCenter), { maxT: 5 });
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'hop')).toHaveLength(0);
    expect(ofType(events, 'refusal')[0]).toMatchObject({ reason: 'gait' });
    expect(sim.horse.gait).toBe('halt');
  });

  it('Galopp: Steilsprung und Oxer werden gesprungen', () => {
    for (const el of [makeElement('vertical', 0.8), makeElement('oxer', 0.85)]) {
      const sim = makeSim([el]);
      placeBefore(sim, el, 8, { speed: S.canterMedium, gallop: true });
      const { events } = drive(sim, pressAt(CANTER, atCenter), { until: untilQuiet });
      expect(ofType(events, 'landed')).toEqual([
        { type: 'landed', elementId: el.id, dir: 1, knocked: false },
      ]);
    }
  });

  it('beide Richtungen sind springbar', () => {
    const o = makeElement('oxer', 0.7, { spread: 0.6 });
    const sim = makeSim([o]);
    placeBefore(sim, o, 8, { dir: -1, speed: S.canterMedium, gallop: true });
    const { events } = drive(sim, pressAt(CANTER, atCenter), {
      until: (s) => !s.horse.jump && s.horse.z < -4,
    });
    expect(ofType(events, 'landed')).toEqual([
      { type: 'landed', elementId: o.id, dir: -1, knocked: false },
    ]);
  });
});

describe('Anreitwinkel (Regel 17)', () => {
  it('über 30°: auch mit Space kein Sprung, Verweigerung mit Vorbeilaufen, danach Trab', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { angle: 35 * DEG, speed: S.canterMedium, gallop: true });
    let inside = false;
    const { events } = drive(sim, pressAt(CANTER, atCenter), {
      maxT: 6,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
      },
    });
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'hop')).toHaveLength(0);
    expect(ofType(events, 'refusal')).toEqual([
      { type: 'refusal', elementId: v.id, dir: 1, reason: 'angle' },
    ]);
    expect(ofType(events, 'gallopEnded')).toEqual([{ type: 'gallopEnded', reason: 'refusal' }]);
    expect(inside).toBe(false);
    expect(sim.horse.refusal).toBeNull();
    expect(sim.horse.gait).toBe('trot');
    expect(sim.horse.gallop).toBe(false);
    // ist am Hindernis vorbei
    const p = toLocal(v, sim.horse.x, sim.horse.z);
    expect(p.along).toBeGreaterThan(0);
  });

  it('runout setzt horse.refusal mit type runout', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 4, { angle: 40 * DEG, speed: S.canterMedium, gallop: true });
    let seen = null;
    drive(sim, CANTER, {
      maxT: 3,
      onStep: (s) => {
        if (s.horse.refusal && !seen) seen = { ...s.horse.refusal };
      },
    });
    expect(seen).toMatchObject({ type: 'runout' });
  });

  it('bis 30° wird gesprungen, jenseits der Toleranz mit Risiko', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { angle: 25 * DEG, speed: S.canterMedium, gallop: true });
    const { events } = drive(sim, pressAt(CANTER, atCenter), { maxT: 4 });
    const take = ofType(events, 'takeoff');
    expect(take).toHaveLength(1);
    expect(take[0].risk).toBeGreaterThan(0);
    expect(ofType(events, 'landed')).toHaveLength(1);
  });
});

/** Ein Sprung mit Space bei gegebenem Abstand/Tempo/Winkel; liefert knocked. */
function jumpOnce(el, seed, { speed, distance, angle = 0 }) {
  const sim = makeSim([el], { seed });
  const gallop = !(el.kind === 'cross' && speed <= S.trotMax);
  placeBefore(sim, el, distance + 0.5, { speed, gallop, angle });
  const input = gallop ? { gallop: true } : {};
  const { events } = drive(sim, pressAt(input, distance), { maxT: 4 });
  const take = ofType(events, 'takeoff');
  const landed = ofType(events, 'landed');
  if (take.length !== 1 || landed.length !== 1) throw new Error('kein Sprung');
  return { knocked: landed[0].knocked, risk: take[0].risk, events };
}

function knockRate(el, opts, seeds = 300) {
  let n = 0;
  for (let s = 1; s <= seeds; s++) if (jumpOnce(el, s, opts).knocked) n++;
  return n / seeds;
}

describe('Sicherer Kern und Abwurfrisiko (Regeln 15, 18, 19)', () => {
  it('sicherer Kern: 100 % ohne Abwurf über viele Seeds, ohne Zufallszug', () => {
    const cases = [
      [makeElement('cross', 0.45), 3.2],
      [makeElement('vertical', 0.6), 5.8],
      [makeElement('oxer', 0.85), 6.2],
    ];
    for (const [el, speed] of cases) {
      const z = zoneForElement(el, speed, TUNING);
      for (let seed = 1; seed <= 200; seed++) {
        const rng = countingRng(seed);
        const sim = makeSim([el], { rng });
        const gallop = el.kind !== 'cross';
        placeBefore(sim, el, z.far + 1.5, { speed, gallop });
        // Space wird im ersten Frame mit Abstand ≤ target gedrückt (bis zu v · dt dichter)
        const target = z.near + speed / 60 + ((z.far - z.near - speed / 60) * seed) / 201;
        const { events } = drive(sim, pressAt(gallop ? CANTER : TROT, target), { maxT: 4 });
        expect(ofType(events, 'takeoff')[0].risk).toBe(0);
        expect(ofType(events, 'landed')[0].knocked).toBe(false);
        expect(ofType(events, 'railDown')).toHaveLength(0);
        expect(rng.calls).toBe(0);
      }
    }
  });

  it('Abwurfrate steigt mit der Distanz-Abweichung (zu früh)', () => {
    const v = makeElement('vertical', 0.6);
    const z = zoneForElement(v, 5.8, TUNING);
    const rates = [0.4, 1.0, 1.8].map((dd) => knockRate(v, { speed: 5.8, distance: z.far + dd }));
    expect(rates[0]).toBeGreaterThan(0);
    expect(rates[1]).toBeGreaterThan(rates[0]);
    expect(rates[2]).toBeGreaterThan(rates[1]);
  });

  it('Abwurfrate steigt mit der Tempo-Abweichung', () => {
    const v = makeElement('vertical', 0.8);
    const band = speedBand(v, TUNING);
    const at = (speed) => ({ speed, distance: zoneForElement(v, speed, TUNING).center });
    const inBand = knockRate(v, at(band.min + 0.2));
    const slight = knockRate(v, at(band.max + 0.3));
    const strong = knockRate(v, at(S.canterMax));
    expect(inBand).toBe(0);
    expect(slight).toBeGreaterThan(0);
    expect(strong).toBeGreaterThan(slight);
  });

  it('Abwurfrate steigt mit dem Winkel', () => {
    const v = makeElement('vertical', 0.6);
    const center = zoneForElement(v, 5.8, TUNING).center;
    const r = [5, 18, 29].map((a) =>
      knockRate(v, { speed: 5.8, distance: center, angle: a * DEG }),
    );
    expect(r[0]).toBe(0);
    expect(r[1]).toBeGreaterThan(0);
    expect(r[2]).toBeGreaterThan(r[1]);
  });

  it('85-cm-Oxer: höhere Abwurfrate als ein Kreuz bei gleicher Abweichung', () => {
    const c = makeElement('cross', 0.45);
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const opts = (el) => ({ speed: 6.5, distance: zoneForElement(el, 6.5, TUNING).far + 0.6 });
    const rc = knockRate(c, opts(c), 400);
    const ro = knockRate(o, opts(o), 400);
    expect(rc).toBeGreaterThan(0);
    expect(ro).toBeGreaterThan(rc * 1.5);
  });

  it('zu dicht (zwischen Zone und letztem Absprungpunkt) erhöht das Risiko', () => {
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const z = zoneForElement(o, 5.8, TUNING);
    const { risk } = jumpOnce(o, 1, { speed: 5.8, distance: (z.near + z.lastPoint) / 2 });
    expect(risk).toBeGreaterThan(0);
  });
});

describe('Abwurf und Stangen (Regel 23)', () => {
  it('Abwurf: railDown beim Überqueren, rails aktualisiert, landed knocked', () => {
    const c = makeElement('cross', 0.45, { id: 'c' });
    const sim = makeSim([c], { rng: () => 0 });
    const z = zoneForElement(c, S.trotMax, TUNING);
    placeBefore(sim, c, z.reach - 0.05, { speed: S.trotMax });
    let railAt = null;
    let takeoffSeen = false;
    const { events } = drive(sim, () => ({ jump: !takeoffSeen }), {
      maxT: 4,
      onStep: (s, ev) => {
        if (ofType(ev, 'takeoff').length) takeoffSeen = true;
        if (ofType(ev, 'railDown').length) railAt = toLocal(c, s.horse.x, s.horse.z).along;
      },
    });
    expect(ofType(events, 'railDown')).toEqual([{ type: 'railDown', elementId: 'c', rail: 0 }]);
    expect(railAt).toBeGreaterThanOrEqual(0);
    expect(railAt).toBeLessThan(0.2);
    expect(sim.rails.get('c')).toEqual([false]);
    expect(ofType(events, 'landed')).toEqual([
      { type: 'landed', elementId: 'c', dir: 1, knocked: true },
    ]);

    // liegende Stange fällt nicht erneut
    placeBefore(sim, c, z.reach - 0.05, { speed: S.trotMax });
    takeoffSeen = false;
    const again = drive(sim, () => ({ jump: !takeoffSeen }), {
      maxT: 4,
      onStep: (s, ev) => {
        if (ofType(ev, 'takeoff').length) takeoffSeen = true;
      },
    });
    expect(ofType(again.events, 'railDown')).toHaveLength(0);
    expect(ofType(again.events, 'landed')[0].knocked).toBe(false);

    sim.rebuild('c');
    expect(sim.rails.get('c')).toEqual([true]);
  });

  it('Oxer: zu dicht wirft die zuerst überquerte Stange ab, je nach Richtung', () => {
    for (const dir of [1, -1]) {
      const o = makeElement('oxer', 0.85, { id: 'o', spread: 0.7 });
      const sim = makeSim([o], { rng: () => 0 });
      const z = zoneForElement(o, 5.8, TUNING);
      placeBefore(sim, o, z.near + 0.3, { dir, speed: 5.8, gallop: true });
      const { events } = drive(sim, pressAt(CANTER, (z.near + z.lastPoint) / 2), { maxT: 4 });
      expect(ofType(events, 'railDown')).toEqual([
        { type: 'railDown', elementId: 'o', rail: dir > 0 ? 0 : 1 },
      ]);
      expect(sim.rails.get('o')).toEqual(dir > 0 ? [false, true] : [true, false]);
      sim.rebuildAll();
      expect(sim.rails.get('o')).toEqual([true, true]);
    }
  });
});

describe('Sprungablauf (Regel 24)', () => {
  it('Phasen takeoff → flight → landing, Höhe über dem Hindernis, Lenkung gesperrt', () => {
    const v = makeElement('vertical', 0.8);
    const sim = makeSim([v]);
    placeBefore(sim, v, 5, { speed: 6, gallop: true });
    const phases = [];
    let maxY = 0;
    let headingAtTakeoff = null;
    drive(sim, pressAt({ gallop: true, steer: 1 }, atCenter), {
      maxT: 3,
      onStep: (s, ev) => {
        if (ofType(ev, 'takeoff').length) headingAtTakeoff = s.horse.heading;
        if (s.horse.jump) {
          const last = phases[phases.length - 1];
          if (last !== s.horse.jump.phase) phases.push(s.horse.jump.phase);
          expect(s.horse.jump.progress).toBeGreaterThanOrEqual(0);
          expect(s.horse.jump.progress).toBeLessThanOrEqual(1);
          expect(s.horse.jump.elementId).toBe(v.id);
          expect(s.horse.heading).toBe(headingAtTakeoff);
          expect(s.horse.speed).toBe(6);
          maxY = Math.max(maxY, s.horse.y);
        }
      },
    });
    expect(phases).toEqual(['takeoff', 'flight', 'landing']);
    expect(maxY).toBeGreaterThan(v.height);
    expect(sim.horse.jump).toBeNull();
    expect(sim.horse.y).toBe(0);
  });
});

describe('Selbstsprung und Verweigerung (Regeln 20, 22)', () => {
  it('ohne Space: Selbstsprung bei passender Gangart, Winkel und Tempo, mit Risiko', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    const { events } = drive(sim, CANTER, { until: untilQuiet });
    const take = ofType(events, 'takeoff');
    expect(take).toHaveLength(1);
    expect(take[0].self).toBe(true);
    expect(take[0].risk).toBeGreaterThanOrEqual(0.3);
    expect(ofType(events, 'landed')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
  });

  it('Verweigerung erst am letzten Absprungpunkt', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { speed: S.trotMedium });
    let prevDistance = Infinity;
    let at = null;
    drive(sim, TROT, {
      maxT: 4,
      onStep: (s, ev) => {
        const info = approachInfo(v, s.horse, TUNING.approachDistance);
        if (ofType(ev, 'refusal').length) at = { prev: prevDistance, now: info.distance };
        if (!at && info) prevDistance = info.distance;
      },
    });
    const lp = zoneForElement(v, S.trotMedium, TUNING).lastPoint;
    expect(at).not.toBeNull();
    expect(at.prev).toBeGreaterThan(lp);
    expect(at.now).toBeLessThanOrEqual(lp + 1e-9);
  });

  it('vorher angaloppieren: keine Verweigerung', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 10, { speed: S.trotMedium });
    sim.step(1 / 60, {});
    const { events } = drive(
      sim,
      (s) => ({ gallop: (sim.approach?.distance ?? 0) < 8 || s.horse.gallop }),
      { until: untilQuiet },
    );
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'landed')).toHaveLength(1);
  });

  it('vorher abwenden: keine Verweigerung', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { speed: S.trotMedium });
    // ab 6 m vor dem Hindernis rechts abwenden, bis der Kurs deutlich daneben liegt
    let turning = false;
    const { events } = drive(
      sim,
      (s) => {
        if (s.approach && s.approach.distance < 6) turning = true;
        if (s.horse.heading < -1.2) turning = false;
        return { steer: turning ? 1 : 0 };
      },
      { maxT: 6 },
    );
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toHaveLength(0);
  });

  it('Gangart: bleibt vor dem Hindernis stehen, Halt, Galopp aus', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMax });
    let seenStop = false;
    let inside = false;
    const { events } = drive(sim, TROT, {
      maxT: 4,
      onStep: (s) => {
        if (s.horse.refusal?.type === 'stop') seenStop = true;
        if (insideBlock(s, v)) inside = true;
      },
    });
    expect(seenStop).toBe(true);
    expect(inside).toBe(false);
    expect(ofType(events, 'refusal')[0].reason).toBe('gait');
    expect(ofType(events, 'gallopEnded')).toEqual([{ type: 'gallopEnded', reason: 'refusal' }]);
    expect(sim.horse.speed).toBe(0);
    expect(sim.horse.gait).toBe('halt');
    expect(sim.horse.refusal).toBeNull();
    const p = toLocal(v, sim.horse.x, sim.horse.z);
    expect(p.along).toBeLessThan(0);
    expect(p.along).toBeGreaterThan(-1);
  });

  it('zu wenig Tempo: Verweigerung speed (stehen bleiben)', () => {
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const sim = makeSim([o]);
    placeBefore(sim, o, 8, { speed: S.canterMin, gallop: true });
    const { events } = drive(sim, CANTER, { maxT: 4 });
    expect(ofType(events, 'refusal')[0]).toMatchObject({ reason: 'speed' });
    expect(sim.horse.gait).toBe('halt');
    expect(sim.horse.gallop).toBe(false);

    const c = makeElement('cross', 0.45);
    const sim2 = makeSim([c]);
    placeBefore(sim2, c, 6, { speed: 2.0 });
    const r2 = drive(sim2, TROT, { maxT: 5 });
    expect(sim2.horse.gait).toBe('halt');
    expect(ofType(r2.events, 'refusal')[0]).toMatchObject({ reason: 'speed' });
  });

  it('Shift gehalten: nach der Verweigerung erst nach neuem Drücken Galopp', () => {
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const sim = makeSim([o]);
    placeBefore(sim, o, 6, { speed: S.canterMin, gallop: true });
    drive(sim, CANTER, { maxT: 4 });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });

  it('Gangart und Winkel zusammen: Verhalten Gangart (stehen bleiben)', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium, angle: 40 * DEG });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'refusal')).toEqual([
      { type: 'refusal', elementId: v.id, dir: 1, reason: 'gait' },
    ]);
    expect(sim.horse.gait).toBe('halt');
  });

  it('Volte neben dem Hindernis: keine Verweigerung, kein Ausweichen', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    sim.reset({ x: 5, z: -2, heading: 0, speed: S.trotMedium });
    const { events } = drive(sim, { steer: -0.6 }, { maxT: 20 });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toHaveLength(0);
  });

  it('Kurs am Hindernis vorbei (außerhalb der Ständer): keine Verweigerung', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    sim.reset({ x: 3.5, z: -8, heading: 0, speed: S.trotMedium });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(sim.horse.z).toBeGreaterThan(2);
  });
});

describe('Sperre nach Verweigerung (Regel 22)', () => {
  it('erneutes Anreiten innerhalb des Anreitabstands: Ausweichen statt Verweigerung', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    turnInPlace(sim, Math.PI);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.z < -7, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, 0);
    // Galopp neu drücken und im Galopp ohne Space anreiten
    sim.step(1 / 60, { gallop: false });
    let inside = false;
    let speedAtSwerve = null;
    const { events } = drive(sim, CANTER, {
      maxT: 6,
      onStep: (s, ev) => {
        if (insideBlock(s, v)) inside = true;
        if (ofType(ev, 'swerve').length) speedAtSwerve = s.horse.speed;
      },
    });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toEqual([{ type: 'swerve', elementId: v.id }]);
    expect(ofType(events, 'gallopEnded')).toHaveLength(0);
    expect(inside).toBe(false);
    expect(sim.horse.gallop).toBe(true);
    expect(sim.horse.gait).toBe('canter');
    expect(sim.horse.speed).toBe(speedAtSwerve);
    expect(toLocal(v, sim.horse.x, sim.horse.z).along).toBeGreaterThan(0);
  });

  it('in der Sperre springt das Pferd auf Space normal', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    turnInPlace(sim, Math.PI);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.z < -9, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, 0);
    sim.step(1 / 60, { gallop: false });
    const { events } = drive(sim, pressAt(CANTER, atCenter), { until: untilQuiet });
    const take = ofType(events, 'takeoff');
    expect(take).toHaveLength(1);
    expect(take[0].self).toBe(false);
    expect(ofType(events, 'landed')).toHaveLength(1);
  });

  it('nach Entfernen über den Anreitabstand hinaus ist wieder eine Verweigerung möglich', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    turnInPlace(sim, Math.PI);
    drive(
      sim,
      { throttle: 1 },
      { until: (s) => s.horse.z < -TUNING.approachDistance - 1, maxT: 20 },
    );
    brakeToHalt(sim);
    turnInPlace(sim, 0);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.speed >= S.trotMedium, maxT: 5 });
    const { events } = drive(sim, TROT, { maxT: 8 });
    expect(ofType(events, 'refusal')).toHaveLength(1);
    expect(ofType(events, 'swerve')).toHaveLength(0);
  });
});

describe('Hindernisse ohne Verweigerung (rules.canRefuse = false)', () => {
  it('ohne Space: seitlich ausweichen, Gangart, Tempo und Galopp bleiben', () => {
    const v = makeElement('vertical', 0.6, { id: 'v' });
    const calls = [];
    const sim = makeSim([v], {
      canRefuse: (id, dir) => {
        calls.push([id, dir]);
        return false;
      },
    });
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    let inside = false;
    const { events } = drive(sim, CANTER, {
      maxT: 4,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
        expect(s.horse.gait).toBe('canter');
        expect(s.horse.speed).toBe(5.8);
      },
    });
    expect(calls).toEqual([['v', 1]]);
    expect(ofType(events, 'swerve')).toEqual([{ type: 'swerve', elementId: 'v' }]);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'gallopEnded')).toHaveLength(0);
    expect(inside).toBe(false);
    expect(sim.horse.gallop).toBe(true);
    expect(toLocal(v, sim.horse.x, sim.horse.z).along).toBeGreaterThan(0);
  });

  it('auch mit unzulässiger Gangart: ausweichen ohne Stopp', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(sim.horse.speed).toBe(S.trotMedium);
    expect(sim.horse.gait).toBe('trot');
  });

  it('mit Space: normaler Sprung', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    const { events } = drive(sim, pressAt(CANTER, atCenter), { until: untilQuiet });
    expect(ofType(events, 'takeoff')[0]).toMatchObject({ self: false, risk: 0 });
    expect(ofType(events, 'landed')).toHaveLength(1);
  });
});

describe('Ständer (Regel 22)', () => {
  it('Körper trifft den Ständer: ausweichen statt durchlaufen', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    sim.reset({ x: 2.0, z: -8, heading: 0, speed: 5.8, gallop: true });
    let inside = false;
    const { events } = drive(sim, CANTER, {
      maxT: 4,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
      },
    });
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(inside).toBe(false);
    expect(sim.horse.z).toBeGreaterThan(1);
  });

  it('seitlich in das Hindernis: das Pferd läuft nie hindurch', () => {
    const v = makeElement('oxer', 0.7, { spread: 0.6 });
    const sim = makeSim([v]);
    sim.reset({ x: -10, z: 0, heading: Math.PI / 2, speed: S.trotMedium });
    let inside = false;
    const { events } = drive(sim, TROT, {
      maxT: 8,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
      },
    });
    expect(inside).toBe(false);
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(sim.horse.x).toBeGreaterThan(3);
  });

  it('im Schritt gegen das Hindernis: bleibt davor, ohne Ausweich-Manöver', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    sim.reset({ x: -10, z: 0, heading: Math.PI / 2, speed: 1.2 });
    let inside = false;
    drive(
      sim,
      {},
      {
        maxT: 10,
        onStep: (s) => {
          if (insideBlock(s, v)) inside = true;
        },
      },
    );
    expect(inside).toBe(false);
  });
});

describe('Hopser (Regel 21)', () => {
  it('Trab ohne Hindernis in Reichweite: Hopser ohne Zählen', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: S.trotMedium });
    const ev = sim.step(1 / 60, { jump: true });
    expect(ev).toEqual([{ type: 'hop' }]);
    let maxY = 0;
    const { events } = drive(
      sim,
      {},
      {
        maxT: 1,
        onStep: (s) => {
          maxY = Math.max(maxY, s.horse.y);
        },
      },
    );
    expect(maxY).toBeGreaterThan(0.1);
    expect(sim.horse.hop).toBeNull();
    expect(ofType(events, 'landed')).toHaveLength(0);
    expect(sim.horse.speed).toBeCloseTo(S.trotMedium, 9);
  });

  it('Galopp ohne Hindernis in Reichweite: Hopser', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 5.8, gallop: true });
    const ev = sim.step(1 / 60, { gallop: true, jump: true });
    expect(ev).toEqual([{ type: 'hop' }]);
    expect(sim.horse.hop).not.toBeNull();
  });

  it('Halt und Schritt: nichts', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0 });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 1.5 });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
  });

  it('Hindernis in Reichweite, Gangart unzulässig: kein Hopser', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 2, { speed: S.trotMedium });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
  });

  it('Hindernis in Reichweite, Winkel unzulässig: kein Hopser', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 2.5, { speed: 5.8, gallop: true, angle: 35 * DEG });
    expect(sim.step(1 / 60, { gallop: true, jump: true })).toEqual([]);
  });

  it('Hindernis noch außerhalb der Reichweite: Hopser', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 9, { speed: 5.8, gallop: true });
    expect(sim.step(1 / 60, { gallop: true, jump: true })).toEqual([{ type: 'hop' }]);
  });
});

describe('Zweifach-Kombination', () => {
  for (const kind of ['vertical', 'oxer']) {
    it(`${kind}: a und b im Galopp mit Space jeweils in der Zone, 2 gezählte Sprünge`, () => {
      const a = makeElement(kind, 0.8, { id: 'a', spread: 0.6 });
      const b = makeElement(kind, 0.8, { id: 'b', z: COMBI_DISTANCE, spread: 0.6 });
      const sim = makeSim([], {
        obstacles: [{ number: 5, elements: [a, b], directed: true }],
      });
      sim.reset({ x: 0, z: -20, heading: 0, speed: S.canterMedium, gallop: true });
      sim.step(1 / 60, { gallop: true });
      const pressedFor = new Set();
      const bApproachAfterLanding = [];
      const { events } = drive(
        sim,
        (s) => {
          const ap = s.approach;
          if (ap && !s.horse.jump && !pressedFor.has(ap.elementId)) {
            if (ap.distance <= atCenter(s, ap)) {
              pressedFor.add(ap.elementId);
              return { gallop: true, jump: true };
            }
          }
          return { gallop: true };
        },
        {
          until: (s) => s.horse.z > COMBI_DISTANCE + 4,
          onStep: (s, ev) => {
            if (ofType(ev, 'landed').some((e) => e.elementId === 'a')) {
              bApproachAfterLanding.push({ ...s.approach });
            }
          },
        },
      );
      expect(ofType(events, 'landed')).toEqual([
        { type: 'landed', elementId: 'a', dir: 1, knocked: false },
        { type: 'landed', elementId: 'b', dir: 1, knocked: false },
      ]);
      expect(ofType(events, 'takeoff').map((e) => e.risk)).toEqual([0, 0]);
      const zoneB = zoneForElement(b, S.canterMedium, TUNING);
      expect(bApproachAfterLanding[0].elementId).toBe('b');
      expect(bApproachAfterLanding[0].distance).toBeGreaterThan(zoneB.far);
    });
  }
});

describe('Determinismus', () => {
  it('gleicher Seed, gleiche Eingaben → gleiche Events', () => {
    const run = (seed) => {
      const v = makeElement('vertical', 0.8, { id: 'v' });
      const sim = makeSim([v], { seed });
      placeBefore(sim, v, 8, { speed: 7.5, gallop: true, angle: 20 * DEG });
      return drive(sim, pressAt(CANTER, 4), { maxT: 3 }).events;
    };
    const runs = [1, 2, 3, 4, 5].map(run);
    expect(run(1)).toEqual(runs[0]);
    expect(run(4)).toEqual(runs[3]);
  });

  it('Standardwerte: ohne rules, rng und tuning lauffähig', () => {
    const v = makeElement('vertical', 0.6);
    const sim = createRidingSim({ obstacles: obstaclesOf(v) });
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    const { events } = drive(sim, CANTER, { maxT: 3 });
    expect(ofType(events, 'landed')).toHaveLength(1);
  });
});

describe('Robustheit', () => {
  it('zufälliges Reiten durch eine Aufstellung: nie im Hindernis, nie außerhalb des Platzes', () => {
    const els = [
      makeElement('cross', 0.45, { id: 'k', x: -10, z: -15 }),
      makeElement('vertical', 0.6, { id: 's', x: 10, z: -15, rot: Math.PI / 2 }),
      makeElement('oxer', 0.85, { id: 'o', x: -10, z: 15, rot: 0.4 }),
    ];
    const ca = makeElement('vertical', 0.7, { id: 'ca', x: 8, z: 10 });
    const cb = makeElement('oxer', 0.8, { id: 'cb', x: 8, z: 10 + COMBI_DISTANCE, spread: 0.6 });
    const all = [...els, ca, cb];
    const maxX = 20 - TUNING.horse.radius + 1e-9;
    const maxZ = 35 - TUNING.horse.radius + 1e-9;
    for (const seed of [1, 2, 3]) {
      const sim = makeSim([], {
        seed,
        obstacles: [
          ...els.map((e) => ({ number: null, elements: [e], directed: false })),
          { number: null, elements: [ca, cb], directed: false },
        ],
      });
      sim.reset({ x: 0, z: 0, heading: 0 });
      const rng = createRng(seed * 101);
      let input = {};
      let takeoffs = 0;
      let landed = 0;
      drive(
        sim,
        (_s, t) => {
          if (Math.round(t * 60) % 45 === 0) {
            input = {
              steer: rng() < 0.5 ? 0 : rng() * 2 - 1,
              throttle: rng() * 2 - 0.7,
              gallop: rng() < 0.6,
            };
          }
          return { ...input, jump: rng() < 0.03 };
        },
        {
          maxT: 120,
          onStep: (s, ev) => {
            takeoffs += ofType(ev, 'takeoff').length;
            landed += ofType(ev, 'landed').length;
            expect(Math.abs(s.horse.x)).toBeLessThanOrEqual(maxX);
            expect(Math.abs(s.horse.z)).toBeLessThanOrEqual(maxZ);
            for (const el of all) {
              if (s.horse.jump && s.horse.jump.elementId === el.id) continue;
              expect(insideBlock(s, el)).toBe(false);
            }
            expect(Number.isFinite(s.horse.heading)).toBe(true);
            expect(s.horse.speed).toBeGreaterThanOrEqual(0);
            expect(s.horse.speed).toBeLessThanOrEqual(S.canterMax);
          },
        },
      );
      expect(landed).toBeGreaterThanOrEqual(takeoffs - 1);
      expect(landed).toBeLessThanOrEqual(takeoffs);
    }
  });
});
