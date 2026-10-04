import { describe, expect, it } from 'vitest';
import { createMotion, stepMotion, turnBend, turnLean } from './motion.js';
import { GAITS } from './gaits.js';

const LF = 0;
const RF = 1;
const LH = 2;
const RH = 3;

function run(gait, speed, seconds, { dt = 1 / 240, turnRate = 0, warm = 3 } = {}) {
  const m = createMotion();
  const state = { gait, speed, turnRate, jump: null, hop: null, refusal: null };
  // settle (gait cross-fade finished)
  for (let t = 0; t < warm; t += dt) stepMotion(m, dt, state);
  const events = [];
  let time = 0;
  for (; time < seconds; time += dt) {
    const falls = stepMotion(m, dt, state);
    for (const leg of falls) events.push({ leg, t: time, phi: m.phi });
  }
  return { m, events };
}

const countPerLeg = (events) =>
  [0, 1, 2, 3].map((leg) => events.filter((e) => e.leg === leg).length);

describe('footfall', () => {
  it('never fires at halt', () => {
    const { events } = run('halt', 0, 5);
    expect(events).toHaveLength(0);
  });

  it('does not fire when turning on the spot at halt', () => {
    const { events } = run('halt', 0, 5, { turnRate: 1.2 });
    expect(events).toHaveLength(0);
  });

  for (const [gait, speed] of [
    ['walk', 1.6],
    ['trot', 3.2],
    ['canter', 6],
  ]) {
    it(`${gait}: exactly once per leg and cycle`, () => {
      const f = GAITS[gait].freq(speed);
      const cycles = 6;
      const { events } = run(gait, speed, cycles / f - 1e-3);
      for (const n of countPerLeg(events)) expect(Math.abs(n - cycles)).toBeLessThanOrEqual(1);
    });
  }

  it('walk: four-beat LH → LF → RH → RF, ¼ cycle apart', () => {
    const { events } = run('walk', 1.6, 3);
    const seq = events.slice(0, 8).map((e) => e.leg);
    const start = seq.indexOf(LH);
    const order = seq.slice(start, start + 4);
    expect(order).toEqual([LH, LF, RH, RF]);
    const f = GAITS.walk.freq(1.6);
    const ev = events.slice(start, start + 4);
    for (let i = 1; i < 4; i++) expect((ev[i].t - ev[i - 1].t) * f).toBeCloseTo(0.25, 1);
  });

  it('trot: diagonal pairs land together', () => {
    const { events } = run('trot', 3.2, 2);
    const byTime = new Map();
    for (const e of events) {
      const k = Math.round(e.t * 100);
      byTime.set(k, [...(byTime.get(k) || []), e.leg].sort());
    }
    const pairs = [...byTime.values()].filter((p) => p.length === 2);
    expect(pairs.length).toBeGreaterThan(3);
    for (const p of pairs) {
      expect([[LF, RH].sort().join(), [RF, LH].sort().join()]).toContain(p.join());
    }
  });

  it('rein-back: diagonal pairs land together, once per leg and cycle, and it is quiet at halt', () => {
    const v = -0.5;
    const f = GAITS.back.freq(0.5);
    const cycles = 4;
    const { events } = run('back', v, cycles / f - 1e-3);
    for (const n of countPerLeg(events)) expect(Math.abs(n - cycles)).toBeLessThanOrEqual(1);
    const byTime = new Map();
    for (const e of events) {
      const k = Math.round(e.t * 100);
      byTime.set(k, [...(byTime.get(k) || []), e.leg].sort());
    }
    const pairs = [...byTime.values()].filter((p) => p.length === 2);
    expect(pairs.length).toBeGreaterThan(3);
    for (const p of pairs) {
      expect([[LF, RH].sort().join(), [RF, LH].sort().join()]).toContain(p.join());
    }
  });

  it('rein-back: the horse blends back to halt with at most a step or two', () => {
    const m = createMotion();
    const st = { gait: 'back', speed: -0.5, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 2; t += 1 / 120) stepMotion(m, 1 / 120, st);
    expect(m.weights.back).toBeGreaterThan(0.99);
    st.gait = 'halt';
    st.speed = 0;
    let n = 0;
    for (let t = 0; t < 3; t += 1 / 120) n += stepMotion(m, 1 / 120, st).length;
    expect(m.weights.halt).toBeGreaterThan(0.99);
    expect(m.weights.back).toBeLessThan(0.01);
    expect(n).toBeLessThanOrEqual(2);
  });

  it('canter (left lead): RH → LH+RF → LF, then suspension', () => {
    const { m, events } = run('canter', 6, 2, { turnRate: -0.5 });
    expect(m.lead).toBe(1);
    const seq = events.map((e) => e.leg);
    const i = seq.indexOf(RH);
    expect(seq[i]).toBe(RH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([RF, LH].sort());
    expect(seq[i + 3]).toBe(LF);
  });

  it('right turn when striking off → right lead (LH → RH+LF → RF)', () => {
    const { m, events } = run('canter', 6, 2, { turnRate: 0.6 });
    expect(m.lead).toBe(-1);
    const seq = events.map((e) => e.leg);
    const i = seq.indexOf(LH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([RH, LF].sort());
    expect(seq[i + 3]).toBe(RF);
  });

  it('does not fire during a jump (landing has its own sound)', () => {
    const m = createMotion();
    const st = { gait: 'canter', speed: 6, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 2; t += 1 / 120) stepMotion(m, 1 / 120, st);
    st.jump = { phase: 'flight', progress: 0.5 };
    let n = 0;
    for (let t = 0; t < 0.6; t += 1 / 120) {
      if (t > 0.15) n += stepMotion(m, 1 / 120, st).length;
      else stepMotion(m, 1 / 120, st);
    }
    expect(n).toBe(0);
  });
});

describe('motion blending', () => {
  it('weights sum to 1 and follow the gait smoothly', () => {
    const m = createMotion();
    const st = { gait: 'trot', speed: 3, turnRate: 0, jump: null, hop: null, refusal: null };
    stepMotion(m, 1 / 60, st);
    const w = m.weights;
    expect(w.halt + w.walk + w.trot + w.canter).toBeCloseTo(1, 6);
    expect(w.trot).toBeGreaterThan(0);
    expect(w.trot).toBeLessThan(0.5);
    for (let i = 0; i < 120; i++) stepMotion(m, 1 / 60, st);
    expect(m.weights.trot).toBeGreaterThan(0.99);
  });

  it('hooves do not slide during stance (hoof velocity = −speed relative to the body)', () => {
    for (const [gait, v] of [
      ['walk', 1.4],
      ['trot', 3],
      ['canter', 6],
    ]) {
      const m = createMotion();
      const st = { gait, speed: v, turnRate: 0, jump: null, hop: null, refusal: null };
      for (let t = 0; t < 3; t += 1 / 240) stepMotion(m, 1 / 240, st);
      const dt = 1 / 240;
      let checked = 0;
      for (let i = 0; i < 240; i++) {
        const before = m.legs.map((l) => ({ ...l }));
        stepMotion(m, dt, st);
        m.legs.forEach((l, k) => {
          if (l.y === 0 && before[k].y === 0) {
            expect((l.dz - before[k].dz) / dt).toBeCloseTo(-v, 0);
            checked++;
          }
        });
      }
      expect(checked).toBeGreaterThan(50);
    }
  });

  it('rein-back: hooves do not slide during stance (they move forward relative to the body)', () => {
    const m = createMotion();
    const st = { gait: 'back', speed: -0.5, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 3; t += 1 / 240) stepMotion(m, 1 / 240, st);
    const dt = 1 / 240;
    let checked = 0;
    for (let i = 0; i < 480; i++) {
      const before = m.legs.map((l) => ({ ...l }));
      stepMotion(m, dt, st);
      m.legs.forEach((l, k) => {
        if (l.y === 0 && before[k].y === 0) {
          expect((l.dz - before[k].dz) / dt).toBeCloseTo(0.5, 0);
          checked++;
        }
      });
    }
    expect(checked).toBeGreaterThan(50);
  });

  it('jump: the pose weight follows the jump progress (in during the take-off, out during the landing)', () => {
    const m = createMotion();
    const st = { gait: 'canter', speed: 6, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 1; t += 1 / 60) stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBe(0);
    const weights = [];
    for (const [phase, frames] of [
      ['takeoff', 14],
      ['flight', 36],
      ['landing', 15],
    ]) {
      for (let i = 0; i < frames; i++) {
        st.jump = { phase, progress: (i + 0.5) / frames };
        stepMotion(m, 1 / 60, st);
        weights.push(m.jumpWeight);
      }
    }
    expect(weights[0]).toBeLessThan(0.1);
    expect(weights[20]).toBeGreaterThan(0.99);
    expect(weights[40]).toBeCloseTo(1, 6);
    expect(weights[weights.length - 1]).toBeLessThan(0.05);
    // never a snap: at most a few percent per frame, except for the slew limit of the quick take-off
    for (let i = 1; i < weights.length; i++) {
      expect(Math.abs(weights[i] - weights[i - 1])).toBeLessThan(0.24);
    }
    st.jump = null;
    for (let i = 0; i < 30; i++) stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBe(0);
    expect(m.jumpJ).toBe(0);
  });

  it('jump that is interrupted fades out instead of snapping', () => {
    const m = createMotion();
    const st = { gait: 'canter', speed: 6, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 1; t += 1 / 60) stepMotion(m, 1 / 60, st);
    st.jump = { phase: 'flight', progress: 0.5 };
    for (let i = 0; i < 30; i++) stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBeCloseTo(1, 6);
    st.jump = null;
    stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBeGreaterThan(0.8);
    for (let i = 0; i < 90; i++) stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBe(0);
  });

  it('refusal stop: weight rises and falls with progress', () => {
    const m = createMotion();
    const st = {
      gait: 'trot',
      speed: 2,
      turnRate: 0,
      jump: null,
      hop: null,
      refusal: { type: 'stop', progress: 0.4 },
    };
    for (let i = 0; i < 30; i++) stepMotion(m, 1 / 60, st);
    expect(m.stopWeight).toBeGreaterThan(0.9);
    st.refusal = { type: 'stop', progress: 1 };
    for (let i = 0; i < 30; i++) stepMotion(m, 1 / 60, st);
    expect(m.stopWeight).toBeLessThan(0.1);
  });
});

describe('turn lean and bend (agile steering, SRT-009)', () => {
  it('lean follows the centripetal acceleration and stays within 0.3 rad (about 17 degrees)', () => {
    for (const [v, w] of [
      [1.5, 2.16],
      [3.2, 1.76],
      [5.8, 1.37],
      [8, 1.16],
      [0, 2.7],
    ]) {
      expect(Math.abs(turnLean(v, w))).toBeLessThanOrEqual(0.3);
    }
  });

  it('lean is not saturated at the usual gaits, so it still grows with speed', () => {
    const walk = turnLean(1.5, 2.16);
    const trot = turnLean(3.2, 1.76);
    const canter = turnLean(5.8, 1.37);
    expect(trot).toBeGreaterThan(walk);
    expect(canter).toBeGreaterThan(trot);
    expect(canter).toBeLessThan(0.3);
  });

  it('leans into the turn: right turn (+) leans right (+), left turn leans left', () => {
    expect(turnLean(5, 1)).toBeGreaterThan(0);
    expect(turnLean(5, -1)).toBeLessThan(0);
    expect(turnLean(5, 0)).toBe(0);
  });

  it('bend stays within 0.35 rad and grows with the turn rate without saturating at canter', () => {
    expect(Math.abs(turnBend(2.7))).toBeLessThanOrEqual(0.35);
    expect(Math.abs(turnBend(-2.7))).toBeLessThanOrEqual(0.35);
    expect(Math.abs(turnBend(1.37))).toBeLessThan(0.3);
    expect(Math.abs(turnBend(1.76))).toBeGreaterThan(Math.abs(turnBend(1.37)));
  });

  it('bends towards the inside: right turn (+) bends to the right (-)', () => {
    expect(turnBend(1)).toBeLessThan(0);
    expect(turnBend(-1)).toBeGreaterThan(0);
  });
});
