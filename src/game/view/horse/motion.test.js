import { describe, expect, it } from 'vitest';
import { createMotion, stepMotion } from './motion.js';
import { GAITS } from './gaits.js';

const LF = 0;
const RF = 1;
const LH = 2;
const RH = 3;

function run(gait, speed, seconds, { dt = 1 / 240, turnRate = 0, warm = 3 } = {}) {
  const m = createMotion();
  const state = { gait, speed, turnRate, jump: null, hop: null, refusal: null };
  // einschwingen (Gangart-Überblendung abgeschlossen)
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

describe('Footfall (Hufaufsetzen)', () => {
  it('feuert im Halt nie', () => {
    const { events } = run('halt', 0, 5);
    expect(events).toHaveLength(0);
  });

  it('feuert im Halt auch beim Wenden auf der Stelle nicht', () => {
    const { events } = run('halt', 0, 5, { turnRate: 1.2 });
    expect(events).toHaveLength(0);
  });

  for (const [gait, speed] of [
    ['walk', 1.6],
    ['trot', 3.2],
    ['canter', 6],
  ]) {
    it(`${gait}: genau einmal je Bein und Zyklus`, () => {
      const f = GAITS[gait].freq(speed);
      const cycles = 6;
      const { events } = run(gait, speed, cycles / f - 1e-3);
      for (const n of countPerLeg(events)) expect(Math.abs(n - cycles)).toBeLessThanOrEqual(1);
    });
  }

  it('Schritt: Viertakt LH → LV → RH → RV, je ¼ Zyklus versetzt', () => {
    const { events } = run('walk', 1.6, 3);
    const seq = events.slice(0, 8).map((e) => e.leg);
    const start = seq.indexOf(LH);
    const order = seq.slice(start, start + 4);
    expect(order).toEqual([LH, LF, RH, RF]);
    const f = GAITS.walk.freq(1.6);
    const ev = events.slice(start, start + 4);
    for (let i = 1; i < 4; i++) expect((ev[i].t - ev[i - 1].t) * f).toBeCloseTo(0.25, 1);
  });

  it('Trab: diagonale Paare fußen gleichzeitig', () => {
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

  it('Galopp (Linksgalopp): RH → LH+RV → LV, danach Schwebephase', () => {
    const { m, events } = run('canter', 6, 2, { turnRate: -0.5 });
    expect(m.lead).toBe(1);
    const seq = events.map((e) => e.leg);
    const i = seq.indexOf(RH);
    expect(seq[i]).toBe(RH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([RF, LH].sort());
    expect(seq[i + 3]).toBe(LF);
  });

  it('Rechtskurve beim Angaloppieren → Rechtsgalopp (LH → RH+LV → RV)', () => {
    const { m, events } = run('canter', 6, 2, { turnRate: 0.6 });
    expect(m.lead).toBe(-1);
    const seq = events.map((e) => e.leg);
    const i = seq.indexOf(LH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([RH, LF].sort());
    expect(seq[i + 3]).toBe(RF);
  });

  it('feuert während eines Sprungs nicht (Landung hat eigenen Klang)', () => {
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

describe('Bewegungs-Überblendung', () => {
  it('Gewichte summieren sich zu 1 und folgen der Gangart weich', () => {
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

  it('Hufe gleiten in der Stützphase nicht (Hufweg = −Tempo relativ zum Körper)', () => {
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
          if (l.stance && before[k].stance && l.y === 0) {
            expect((l.dz - before[k].dz) / dt).toBeCloseTo(-v, 0);
            checked++;
          }
        });
      }
      expect(checked).toBeGreaterThan(50);
    }
  });

  it('Sprung: Gewicht steigt beim Absprung schnell auf ≈ 1', () => {
    const m = createMotion();
    const st = { gait: 'canter', speed: 6, turnRate: 0, jump: null, hop: null, refusal: null };
    for (let t = 0; t < 1; t += 1 / 60) stepMotion(m, 1 / 60, st);
    st.jump = { phase: 'takeoff', progress: 0.3 };
    for (let i = 0; i < 12; i++) stepMotion(m, 1 / 60, st);
    expect(m.jumpWeight).toBeGreaterThan(0.9);
    expect(m.jumpJ).toBeCloseTo(0.3, 5);
  });

  it('Verweigerung stop: Gewicht steigt und fällt mit progress', () => {
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
