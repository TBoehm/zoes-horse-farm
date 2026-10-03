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

/** Space in the zone center for the current speed. */
const atCenter = (sim, a) =>
  sim.zoneFor(a.elementId, a.dir, sim.horse.speed).far * 0.5 +
  sim.zoneFor(a.elementId, a.dir, sim.horse.speed).near * 0.5;

const untilQuiet = (s) => !s.horse.jump && !s.horse.refusal && s.horse.z > 4;

describe('Contract', () => {
  it('provides horse, rails, approach, zoneFor, rebuild, rebuildAll', () => {
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

  it('approach: null when facing away, dir −1 from the other side', () => {
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

describe('Jumpability by gait (rule 16)', () => {
  it('walk at a cross: Space does nothing, refusal at the last takeoff point', () => {
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

  it('trot at a cross: jump with Space in the zone, no knockdown', () => {
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

  it('trot at a vertical: Space does nothing, refusal (stops)', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    const { events } = drive(sim, pressAt(TROT, atCenter), { maxT: 5 });
    expect(ofType(events, 'takeoff')).toHaveLength(0);
    expect(ofType(events, 'hop')).toHaveLength(0);
    expect(ofType(events, 'refusal')[0]).toMatchObject({ reason: 'gait' });
    expect(sim.horse.gait).toBe('halt');
  });

  it('canter: vertical and oxer are jumped', () => {
    for (const el of [makeElement('vertical', 0.8), makeElement('oxer', 0.85)]) {
      const sim = makeSim([el]);
      placeBefore(sim, el, 8, { speed: S.canterMedium, gallop: true });
      const { events } = drive(sim, pressAt(CANTER, atCenter), { until: untilQuiet });
      expect(ofType(events, 'landed')).toEqual([
        { type: 'landed', elementId: el.id, dir: 1, knocked: false },
      ]);
    }
  });

  it('both directions are jumpable', () => {
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

describe('Approach angle (rule 17)', () => {
  it('over 30°: no jump even with Space, refusal with run-out, then trot', () => {
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
    // is past the obstacle
    const p = toLocal(v, sim.horse.x, sim.horse.z);
    expect(p.along).toBeGreaterThan(0);
  });

  it('runout sets horse.refusal with type runout', () => {
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

  it('up to 30° it is jumped, beyond the tolerance with risk', () => {
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

/** One jump with Space at the given distance/speed/angle; returns knocked. */
function jumpOnce(el, seed, { speed, distance, angle = 0 }) {
  const sim = makeSim([el], { seed });
  const gallop = !(el.kind === 'cross' && speed <= S.trotMax);
  placeBefore(sim, el, distance + 0.5, { speed, gallop, angle });
  const input = gallop ? { gallop: true } : {};
  const { events } = drive(sim, pressAt(input, distance), { maxT: 4 });
  const take = ofType(events, 'takeoff');
  const landed = ofType(events, 'landed');
  if (take.length !== 1 || landed.length !== 1) throw new Error('no jump');
  return { knocked: landed[0].knocked, risk: take[0].risk, events };
}

function knockRate(el, opts, seeds = 300) {
  let n = 0;
  for (let s = 1; s <= seeds; s++) if (jumpOnce(el, s, opts).knocked) n++;
  return n / seeds;
}

describe('Safe core and knockdown risk (rules 15, 18, 19)', () => {
  it('safe core: 100 % without knockdown over many seeds, without a random draw', () => {
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
        // Space is pressed in the first frame with distance ≤ target (up to v · dt closer)
        const target = z.near + speed / 60 + ((z.far - z.near - speed / 60) * seed) / 201;
        const { events } = drive(sim, pressAt(gallop ? CANTER : TROT, target), { maxT: 4 });
        expect(ofType(events, 'takeoff')[0].risk).toBe(0);
        expect(ofType(events, 'landed')[0].knocked).toBe(false);
        expect(ofType(events, 'railDown')).toHaveLength(0);
        expect(rng.calls).toBe(0);
      }
    }
  });

  it('knockdown rate rises with the distance deviation (too early)', () => {
    const v = makeElement('vertical', 0.6);
    const z = zoneForElement(v, 5.8, TUNING);
    const rates = [0.4, 1.0, 1.8].map((dd) => knockRate(v, { speed: 5.8, distance: z.far + dd }));
    expect(rates[0]).toBeGreaterThan(0);
    expect(rates[1]).toBeGreaterThan(rates[0]);
    expect(rates[2]).toBeGreaterThan(rates[1]);
  });

  it('knockdown rate rises with the speed deviation', () => {
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

  it('knockdown rate rises with the angle', () => {
    const v = makeElement('vertical', 0.6);
    const center = zoneForElement(v, 5.8, TUNING).center;
    const r = [5, 18, 29].map((a) =>
      knockRate(v, { speed: 5.8, distance: center, angle: a * DEG }),
    );
    expect(r[0]).toBe(0);
    expect(r[1]).toBeGreaterThan(0);
    expect(r[2]).toBeGreaterThan(r[1]);
  });

  it('85 cm oxer: higher knockdown rate than a cross at the same deviation', () => {
    const c = makeElement('cross', 0.45);
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const opts = (el) => ({ speed: 6.5, distance: zoneForElement(el, 6.5, TUNING).far + 0.6 });
    const rc = knockRate(c, opts(c), 400);
    const ro = knockRate(o, opts(o), 400);
    expect(rc).toBeGreaterThan(0);
    expect(ro).toBeGreaterThan(rc * 1.5);
  });

  it('too close (between zone and last takeoff point) increases the risk', () => {
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const z = zoneForElement(o, 5.8, TUNING);
    const { risk } = jumpOnce(o, 1, { speed: 5.8, distance: (z.near + z.lastPoint) / 2 });
    expect(risk).toBeGreaterThan(0);
  });
});

describe('Knockdown and poles (rule 23)', () => {
  it('knockdown: railDown when crossing, rails updated, landed knocked', () => {
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
    expect(ofType(events, 'railDown')).toEqual([
      { type: 'railDown', elementId: 'c', rail: 0, dir: 1 },
    ]);
    expect(railAt).toBeGreaterThanOrEqual(0);
    expect(railAt).toBeLessThan(0.2);
    expect(sim.rails.get('c')).toEqual([false]);
    expect(ofType(events, 'landed')).toEqual([
      { type: 'landed', elementId: 'c', dir: 1, knocked: true },
    ]);

    // a pole already down does not fall again
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

  it('oxer: too close knocks the pole crossed first, depending on direction', () => {
    for (const dir of [1, -1]) {
      const o = makeElement('oxer', 0.85, { id: 'o', spread: 0.7 });
      const sim = makeSim([o], { rng: () => 0 });
      const z = zoneForElement(o, 5.8, TUNING);
      placeBefore(sim, o, z.near + 0.3, { dir, speed: 5.8, gallop: true });
      const { events } = drive(sim, pressAt(CANTER, (z.near + z.lastPoint) / 2), { maxT: 4 });
      expect(ofType(events, 'railDown')).toEqual([
        { type: 'railDown', elementId: 'o', rail: dir > 0 ? 0 : 1, dir },
      ]);
      expect(sim.rails.get('o')).toEqual(dir > 0 ? [false, true] : [true, false]);
      sim.rebuildAll();
      expect(sim.rails.get('o')).toEqual([true, true]);
    }
  });
});

describe('Jump sequence (rule 24)', () => {
  it('phases takeoff → flight → landing, height above the obstacle, steering locked', () => {
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

describe('Self jump and refusal (rules 20, 22)', () => {
  it('without Space: self jump with matching gait, angle and speed, with risk', () => {
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

  it('refusal only at the last takeoff point', () => {
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

  it('striking off into canter beforehand: no refusal', () => {
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

  it('turning away beforehand: no refusal', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 8, { speed: S.trotMedium });
    // from 6 m before the obstacle turn right until the course clearly misses it
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

  it('gait: stops before the obstacle, halt, gallop off', () => {
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

  it('too little speed: refusal speed (stops)', () => {
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

  it('Shift held: after the refusal canter only after a fresh key press', () => {
    const o = makeElement('oxer', 0.85, { spread: 0.7 });
    const sim = makeSim([o]);
    placeBefore(sim, o, 6, { speed: S.canterMin, gallop: true });
    drive(sim, CANTER, { maxT: 4 });
    expect(sim.horse.gallop).toBe(false);
    sim.step(1 / 60, { gallop: false });
    sim.step(1 / 60, { gallop: true });
    expect(sim.horse.gallop).toBe(true);
  });

  it('gait and angle together: gait behaviour (stops)', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium, angle: 40 * DEG });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'refusal')).toEqual([
      { type: 'refusal', elementId: v.id, dir: 1, reason: 'gait' },
    ]);
    expect(sim.horse.gait).toBe('halt');
  });

  it('volte next to the obstacle: no refusal, no evasion', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    sim.reset({ x: 5, z: -2, heading: 0, speed: S.trotMedium });
    const { events } = drive(sim, { steer: -0.6 }, { maxT: 20 });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toHaveLength(0);
  });

  it('course past the obstacle (outside the stands): no refusal', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    sim.reset({ x: 3.5, z: -8, heading: 0, speed: S.trotMedium });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(sim.horse.z).toBeGreaterThan(2);
  });
});

describe('Lock after refusal (rule 22)', () => {
  it('approaching again within the approach distance: evasion instead of refusal', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    turnInPlace(sim, Math.PI);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.z < -7, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, 0);
    // press gallop again and approach at canter without Space
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

  it('while locked the horse jumps normally on Space', () => {
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

  it('after moving beyond the approach distance a refusal is possible again', () => {
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

describe('Obstacles without refusal (rules.canRefuse = false)', () => {
  it('without Space: evade sideways, gait, speed and gallop are kept', () => {
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

  it('even with a disallowed gait: evade without stopping', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    const { events } = drive(sim, TROT, { maxT: 4 });
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(sim.horse.speed).toBe(S.trotMedium);
    expect(sim.horse.gait).toBe('trot');
  });

  it('with Space: normal jump', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    const { events } = drive(sim, pressAt(CANTER, atCenter), { until: untilQuiet });
    expect(ofType(events, 'takeoff')[0]).toMatchObject({ self: false, risk: 0 });
    expect(ofType(events, 'landed')).toHaveLength(1);
  });
});

describe('Stands (rule 22)', () => {
  it('body hits the stand: evade instead of running through', () => {
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

  it('sideways into the obstacle: the horse never runs through', () => {
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

  it('walking against the stand: evades like at trot, the horse never treads on the spot', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    sim.reset({ x: -10, z: 0, heading: Math.PI / 2, speed: 1.2 });
    let inside = false;
    const walk = (s) => ({ throttle: s.horse.speed < 1.2 ? 1 : 0 });
    const { events } = drive(sim, walk, {
      maxT: 16,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
        expect(s.horse.gait).not.toBe('trot');
      },
    });
    expect(inside).toBe(false);
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    // it got past the obstacle instead of standing against it
    expect(sim.horse.x).toBeGreaterThan(3);
  });

  it('after a refusal stop, walking into the locked element evades instead of pinning', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    expect(sim.horse.speed).toBe(0);
    const walk = (s) => ({ throttle: s.horse.speed < 1.2 ? 1 : 0 });
    let inside = false;
    const { events } = drive(sim, walk, {
      maxT: 12,
      onStep: (s) => {
        if (insideBlock(s, v)) inside = true;
      },
    });
    expect(inside).toBe(false);
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toHaveLength(1);
    expect(toLocal(v, sim.horse.x, sim.horse.z).along).toBeGreaterThan(1);
  });

  it('standing at the obstacle (halt) does not trigger an evasion', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v], { canRefuse: () => false });
    placeBefore(sim, v, 0.2, { speed: 0 });
    const { events } = drive(sim, {}, { maxT: 2 });
    expect(ofType(events, 'swerve')).toHaveLength(0);
  });
});

describe('Values from tuning.js', () => {
  it('keeps the formerly hard-coded values in the tuning table', () => {
    expect(TUNING.refusal.stopDecelMin).toBe(4);
    expect(TUNING.refusal.driftSide).toBeCloseTo(Math.sin(10 * DEG), 12);
    expect(TUNING.jump.railChoice.firstProbability).toBe(0.5);
  });

  it('the refusal stop decelerates at least with refusal.stopDecelMin', () => {
    const stopAlong = (stopDecelMin) => {
      const v = makeElement('vertical', 0.6);
      const sim = makeSim([v], {
        tuning: { ...TUNING, refusal: { ...TUNING.refusal, stopDecelMin } },
      });
      placeBefore(sim, v, 6, { speed: 1.2 });
      drive(sim, { throttle: 0 }, { maxT: 8, until: (s) => s.horse.speed === 0 });
      return toLocal(v, sim.horse.x, sim.horse.z).along;
    };
    // a harder floor brakes earlier, so the horse stands further in front of the obstacle
    expect(stopAlong(40)).toBeLessThan(stopAlong(4));
  });

  it('refusal.driftSide decides when the course direction picks the evasion side', () => {
    const sideAfterEvading = (driftSide) => {
      const v = makeElement('vertical', 0.6);
      const sim = makeSim([v], {
        canRefuse: () => false,
        tuning: { ...TUNING, refusal: { ...TUNING.refusal, driftSide } },
      });
      // course drifts toward +t (15°) but meets the obstacle left of the center (-1 m)
      placeBefore(sim, v, 8, { speed: S.trotMedium, angle: 15 * DEG, crossing: -1 });
      drive(sim, TROT, { maxT: 5 });
      return Math.sign(toLocal(v, sim.horse.x, sim.horse.z).across);
    };
    expect(sideAfterEvading(TUNING.refusal.driftSide)).toBe(1);
    expect(sideAfterEvading(1)).toBe(-1);
  });

  it('jump.railChoice.firstProbability picks the rail in the middle of the oxer zone', () => {
    const speed = 4.8; // below the target range: risk > 0 although the horse is in the zone
    const railFor = (firstProbability, draw) => {
      const o = makeElement('oxer', 0.85, { id: 'o', spread: 0.7 });
      let calls = 0;
      // first draw: the knockdown (always), second draw: the rail choice
      const rng = () => (calls++ === 0 ? 0 : draw);
      const sim = makeSim([o], {
        rng,
        tuning: { ...TUNING, jump: { ...TUNING.jump, railChoice: { firstProbability } } },
      });
      const z = zoneForElement(o, speed, TUNING);
      const aim = (z.near + z.far) / 2;
      placeBefore(sim, o, aim + 0.5, { speed, gallop: true });
      const { events } = drive(sim, pressAt(CANTER, aim), { maxT: 4 });
      return ofType(events, 'railDown')[0]?.rail;
    };
    expect(railFor(0.5, 0.4)).toBe(0);
    expect(railFor(0.5, 0.6)).toBe(1);
    expect(railFor(0.9, 0.6)).toBe(0);
    expect(railFor(0.1, 0.6)).toBe(1);
  });
});

describe('Refusal stop and lock details', () => {
  it('the refusal stop leaves the horse a visible distance in front of the pole', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: 1.2 });
    drive(sim, TROT, { maxT: 8, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    const distance = -toLocal(v, sim.horse.x, sim.horse.z).along;
    expect(distance).toBeGreaterThanOrEqual(0.35);
    expect(distance).toBeLessThan(1);
  });

  it('the lock is released by the same distance measure as the approach', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 6, { speed: S.trotMedium });
    drive(sim, TROT, { maxT: 3, until: (s) => s.horse.refusal === null && s.horse.speed === 0 });
    // back off 5 m, then ride sideways far beyond 12 m from the center, but along the
    // approach axis always closer than the approach distance
    turnInPlace(sim, Math.PI);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.z < -5, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, Math.PI / 2);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.x > 13, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, -Math.PI / 2);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.x < 0.2, maxT: 20 });
    brakeToHalt(sim);
    turnInPlace(sim, 0);
    drive(sim, { throttle: 1 }, { until: (s) => s.horse.speed >= S.trotMedium, maxT: 5 });
    const { events } = drive(sim, TROT, { maxT: 8 });
    expect(ofType(events, 'refusal')).toHaveLength(0);
    expect(ofType(events, 'swerve')).toHaveLength(1);
  });
});

describe('Space buffer while landing', () => {
  const center = (el) => {
    const z = zoneForElement(el, 5.8, TUNING);
    return (z.near + z.far) / 2;
  };

  /** Jumps `a` at canter and returns the z of the horse at landing (probe run). */
  function landingZ(a) {
    const probe = makeSim([a]);
    placeBefore(probe, a, center(a), { speed: 5.8, gallop: true });
    drive(probe, pressAt(CANTER, center(a)), {
      maxT: 4,
      until: (s, ev) => ofType(ev, 'landed').length > 0,
    });
    return probe.horse.z;
  }

  /** Space for `a` in its zone, then once more shortly before landing. */
  function pressTwice(a) {
    let first = false;
    let second = false;
    return (s) => {
      const jump = s.horse.jump;
      if (!first) {
        if (!jump && s.approach?.elementId === 'a' && s.approach.distance <= center(a)) {
          first = true;
          return { ...CANTER, jump: true };
        }
      } else if (!second && jump?.phase === 'landing' && jump.progress > 0.5) {
        second = true;
        return { ...CANTER, jump: true };
      }
      return CANTER;
    };
  }

  const pair = () => {
    const a = makeElement('vertical', 0.7, { id: 'a' });
    const b0 = makeElement('vertical', 0.7, { id: 'b' });
    // b lies so that it is in the middle of its takeoff zone at the moment of landing
    const b = { ...b0, z: landingZ(a) + center(b0) };
    return { a, b };
  };

  it('a press shortly before landing is carried over and jumps the next obstacle', () => {
    const { a, b } = pair();
    const sim = makeSim([a, b]);
    placeBefore(sim, a, center(a), { speed: 5.8, gallop: true });
    const { events } = drive(sim, pressTwice(a), { maxT: 3 });
    const takes = ofType(events, 'takeoff');
    expect(takes.map((e) => e.elementId)).toEqual(['a', 'b']);
    expect(takes[1].self).toBe(false);
  });

  it('a carried press expires without a hop when no obstacle comes within reach', () => {
    const { a } = pair();
    const sim = makeSim([a]);
    placeBefore(sim, a, center(a), { speed: 5.8, gallop: true });
    const { events } = drive(sim, pressTwice(a), { maxT: 3 });
    expect(ofType(events, 'takeoff')).toHaveLength(1);
    expect(ofType(events, 'hop')).toHaveLength(0);
  });

  it('a press far before landing is not carried over', () => {
    const { a, b } = pair();
    const sim = makeSim([a, b]);
    placeBefore(sim, a, center(a), { speed: 5.8, gallop: true });
    let first = false;
    let second = false;
    const { events } = drive(
      sim,
      (s) => {
        const jump = s.horse.jump;
        if (!first && !jump && s.approach?.elementId === 'a' && s.approach.distance <= center(a)) {
          first = true;
          return { ...CANTER, jump: true };
        }
        if (first && !second && jump?.phase === 'takeoff') {
          second = true;
          return { ...CANTER, jump: true };
        }
        return CANTER;
      },
      { maxT: 3 },
    );
    // no buffered jump: b is only jumped by itself (self jump) at its last takeoff point
    const takes = ofType(events, 'takeoff');
    expect(takes[1].self).toBe(true);
  });
});

describe('Hop (rule 21)', () => {
  it('trot with no obstacle in reach: hop without counting', () => {
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

  it('canter with no obstacle in reach: hop', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 5.8, gallop: true });
    const ev = sim.step(1 / 60, { gallop: true, jump: true });
    expect(ev).toEqual([{ type: 'hop' }]);
    expect(sim.horse.hop).not.toBeNull();
  });

  it('halt and walk: nothing', () => {
    const sim = makeSim([]);
    sim.reset({ x: 0, z: 0, heading: 0 });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
    sim.reset({ x: 0, z: 0, heading: 0, speed: 1.5 });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
  });

  it('obstacle in reach, gait not allowed: no hop', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 2, { speed: S.trotMedium });
    expect(sim.step(1 / 60, { jump: true })).toEqual([]);
  });

  it('obstacle in reach, angle not allowed: no hop', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 2.5, { speed: 5.8, gallop: true, angle: 35 * DEG });
    expect(sim.step(1 / 60, { gallop: true, jump: true })).toEqual([]);
  });

  it('obstacle still out of reach: hop', () => {
    const v = makeElement('vertical', 0.6);
    const sim = makeSim([v]);
    placeBefore(sim, v, 9, { speed: 5.8, gallop: true });
    expect(sim.step(1 / 60, { gallop: true, jump: true })).toEqual([{ type: 'hop' }]);
  });
});

describe('Double combination', () => {
  for (const kind of ['vertical', 'oxer']) {
    it(`${kind}: a and b at canter with Space each in the zone, 2 counted jumps`, () => {
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

describe('Determinism', () => {
  it('same seed, same inputs → same events', () => {
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

  it('defaults: runs without rules, rng and tuning', () => {
    const v = makeElement('vertical', 0.6);
    const sim = createRidingSim({ obstacles: obstaclesOf(v) });
    placeBefore(sim, v, 8, { speed: 5.8, gallop: true });
    const { events } = drive(sim, CANTER, { maxT: 3 });
    expect(ofType(events, 'landed')).toHaveLength(1);
  });
});

describe('Robustness', () => {
  it('random riding through a setup: never inside an obstacle, never outside the arena', () => {
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
