import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import { GAITS } from './gaits.js';
import { blendGait, createLegModel } from './legs.js';
import { createMotion, stepMotion } from './motion.js';
import { FRAME } from '../../../../tests/support/sequence-helper.js';

const LF = 0;
const RF = 1;
const LH = 2;
const RH = 3;

function settled(gait, speed, turnRate = 0) {
  const m = createMotion();
  const state = { gait, speed, turnRate, jump: null, hop: null, refusal: null };
  for (let i = 0; i < 240; i++) stepMotion(m, FRAME, state);
  return { m, state };
}

describe('planted hooves', () => {
  it('a hoof on the ground keeps its place whatever the speed does', () => {
    const m = createMotion();
    const state = { gait: 'canter', speed: 6, turnRate: 0 };
    for (let i = 0; i < 120; i++) stepMotion(m, FRAME, state);
    let world = 0;
    const prev = [null, null, null, null];
    let checked = 0;
    for (let i = 0; i < 360; i++) {
      // the speed wobbles between 4.6 and 7.4 m/s
      state.speed = 6 + 1.4 * Math.sin(i * 0.07);
      stepMotion(m, FRAME, state);
      world += state.speed * FRAME;
      m.legs.forEach((L, k) => {
        const planted = L.y === 0;
        const z = world + L.dz;
        if (prev[k] && prev[k].planted && planted) {
          expect(Math.abs(z - prev[k].z)).toBeLessThan(1e-9);
          checked++;
        }
        prev[k] = { z, planted };
      });
    }
    expect(checked).toBeGreaterThan(300);
  });

  it('a hoof is already moving back when it touches down, at some of the ground speed', () => {
    // a hoof that stops dead at touch-down and sets off at once in stance turns the joints of the
    // leg by half a radian in one frame
    for (const [gait, speed] of [
      ['trot', 3.2],
      ['canter', 6],
    ]) {
      const { m, state } = settled(gait, speed);
      const prev = m.legs.map((L) => ({ dz: L.dz, v: 0, lifted: false }));
      let checked = 0;
      for (let i = 0; i < 360; i++) {
        stepMotion(m, FRAME, state);
        m.legs.forEach((L, k) => {
          const p = prev[k];
          if (p.lifted && L.y === 0) {
            expect(p.v, `${gait} leg ${k}`).toBeLessThan(-0.1 * speed * FRAME);
            checked++;
          }
          p.v = L.dz - p.dz;
          p.dz = L.dz;
          p.lifted = L.y > 0;
        });
      }
      expect(checked).toBeGreaterThan(6);
    }
  });

  it('the stride never asks more of the legs than they can reach', () => {
    for (const [gait, speeds] of [
      ['walk', [0.3, 1, 1.8]],
      ['trot', [2, 3.2, 4]],
      ['canter', [4.5, 6, 8, 10]],
    ]) {
      for (const speed of speeds) {
        const { m, state } = settled(gait, speed);
        let min = Infinity;
        let max = -Infinity;
        for (let i = 0; i < 240; i++) {
          stepMotion(m, FRAME, state);
          for (const L of m.legs) {
            min = Math.min(min, L.dz);
            max = Math.max(max, L.dz);
          }
        }
        // hoof offset within ± 0.5 m (+ the centre of the gait) of the neutral position
        expect(max - min, `${gait} at ${speed} m/s`).toBeLessThan(1.25);
      }
    }
  });

  it('a hoof never travels more than 0.9 m during one stance (margin to the full stretch of the leg)', () => {
    for (const [gait, speeds] of [
      ['walk', [1, 1.8]],
      ['trot', [2, 3.2, 4.5]],
      ['canter', [4.5, 6, 8, 10]],
    ]) {
      for (const speed of speeds) {
        const { m, state } = settled(gait, speed);
        const span = [0, 0, 0, 0];
        const start = m.legs.map((L) => L.dz);
        let worst = 0;
        for (let i = 0; i < 360; i++) {
          stepMotion(m, FRAME, state);
          m.legs.forEach((L, k) => {
            if (L.y > 0) {
              start[k] = null;
              return;
            }
            if (start[k] === null) start[k] = L.dz;
            span[k] = Math.abs(L.dz - start[k]);
            worst = Math.max(worst, span[k]);
          });
        }
        expect(worst, `${gait} at ${speed} m/s`).toBeLessThanOrEqual(0.9);
      }
    }
  });

  it('a faster gait shortens the stance instead of overstretching the legs', () => {
    const duty = (speed) => {
      const m = createLegModel();
      const w = { halt: 0, walk: 0, trot: 0, canter: 1, back: 0 };
      for (let i = 0; i < 100; i++) blendGait(m, w, speed, speed, FRAME);
      return m.duty;
    };
    expect(duty(9)).toBeLessThan(duty(6));
    expect(duty(6)).toBeLessThanOrEqual(GAITS.canter.duty(6) + 1e-9);
  });
});

describe('halt', () => {
  it('after a stop at the fence the legs finish their step, then square up one at a time', () => {
    const { m, state } = settled('canter', 6);
    state.gait = 'halt';
    state.speed = 0;
    let steppingAtOnce = 0;
    let stoppedAt = -1;
    for (let i = 0; i < 6 * 60; i++) {
      stepMotion(m, FRAME, state);
      const lifted = m.legs.filter((L) => L.y > 0.004).length;
      if (i > 45) {
        // after the legs in swing have landed, at most one leg is off the ground
        steppingAtOnce = Math.max(steppingAtOnce, lifted);
        if (lifted === 0 && stoppedAt < 0 && m.legs.every((L) => Math.abs(L.dz) < 0.06)) {
          stoppedAt = i;
        }
      }
    }
    expect(steppingAtOnce).toBeLessThanOrEqual(1);
    expect(stoppedAt).toBeGreaterThan(0);
    expect(stoppedAt).toBeLessThan(5 * 60);
    for (const L of m.legs) {
      expect(Math.abs(L.dz)).toBeLessThan(0.06);
      expect(L.y).toBe(0);
    }
  });

  it('a step in progress when the horse stops is lifted properly (no scraping over the ground)', () => {
    // stops at eight different phases of the trot: the legs that are in swing at that moment finish
    // their step, and the best of the eight stops shows the full lift of the trot
    let best = 0;
    for (let phase = 0; phase < 8; phase++) {
      const { m, state } = settled('trot', 3.2);
      for (let i = 0; i < phase * 4; i++) stepMotion(m, FRAME, state);
      state.gait = 'halt';
      state.speed = 0;
      let lifted = 0;
      for (let i = 0; i < 30; i++) {
        stepMotion(m, FRAME, state);
        lifted = Math.max(lifted, ...m.legs.map((L) => L.y));
      }
      best = Math.max(best, lifted);
    }
    expect(best).toBeGreaterThan(0.1);
  });

  it('a standing horse stays still: legs do not move, no footfalls', () => {
    const m = createMotion();
    const state = { gait: 'halt', speed: 0, turnRate: 0 };
    for (let i = 0; i < 600; i++) {
      const falls = stepMotion(m, FRAME, state);
      expect(falls).toHaveLength(0);
      for (const L of m.legs) {
        expect(L.dz).toBe(0);
        expect(L.y).toBe(0);
      }
    }
  });

  it('turning on the spot steps in place without walking away', () => {
    const { m, state } = settled('halt', 0, 1.4);
    let maxY = 0;
    let maxDz = 0;
    for (let i = 0; i < 240; i++) {
      stepMotion(m, FRAME, state);
      for (const L of m.legs) {
        maxY = Math.max(maxY, L.y);
        maxDz = Math.max(maxDz, Math.abs(L.dz));
      }
    }
    expect(maxY).toBeGreaterThan(0.04);
    expect(maxDz).toBeLessThan(0.12);
  });
});

describe('gait changes keep the footfall order', () => {
  it('trot → canter ends in the canter sequence of the lead after about two strides', () => {
    const m = createMotion();
    const state = { gait: 'trot', speed: 3.2, turnRate: -0.5 };
    for (let i = 0; i < 240; i++) stepMotion(m, FRAME, state);
    state.gait = 'canter';
    state.speed = 6;
    for (let i = 0; i < 3 * 60; i++) stepMotion(m, FRAME, state);
    const seq = [];
    for (let i = 0; i < 2 * 60; i++) seq.push(...stepMotion(m, FRAME, state));
    const i = seq.indexOf(RH);
    expect(seq[i]).toBe(RH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([LH, RF].sort());
    expect(seq[i + 3]).toBe(LF);
  });

  it('walk → trot → walk: diagonal pairs after the change, four beats after the way back', () => {
    const m = createMotion();
    const state = { gait: 'walk', speed: 1.5, turnRate: 0 };
    for (let i = 0; i < 240; i++) stepMotion(m, FRAME, state);
    state.gait = 'trot';
    state.speed = 3.2;
    for (let i = 0; i < 4 * 60; i++) stepMotion(m, FRAME, state);
    const times = new Map();
    for (let i = 0; i < 3 * 60; i++) {
      for (const leg of stepMotion(m, FRAME, state)) {
        times.set(leg, [...(times.get(leg) || []), i]);
      }
    }
    // LF and RH land together, RF and LH half a cycle later
    const near = (a, b) => times.get(a).some((t) => times.get(b).some((u) => Math.abs(t - u) <= 3));
    expect(near(LF, RH)).toBe(true);
    expect(near(RF, LH)).toBe(true);
    expect(near(LF, RF)).toBe(false);
    state.gait = 'walk';
    state.speed = 1.5;
    for (let i = 0; i < 4 * 60; i++) stepMotion(m, FRAME, state);
    const seq = [];
    for (let i = 0; i < 3 * 60; i++) seq.push(...stepMotion(m, FRAME, state));
    const start = seq.indexOf(LH);
    expect(seq.slice(start, start + 4)).toEqual([LH, LF, RH, RF]);
  });
});

describe('hoof scrape (idle gesture)', () => {
  it('lifts a foreleg and sets it down again, only at halt', () => {
    const m = createMotion({ rng: createRng(5) });
    const state = { gait: 'halt', speed: 0, turnRate: 0 };
    let scraped = 0;
    const legsUsed = new Set();
    for (let i = 0; i < 90 * 60; i++) {
      stepMotion(m, FRAME, state);
      const g = m.gesture.state;
      if (g.id === 'paw' && g.weight > 0.5) {
        scraped++;
        legsUsed.add(g.leg);
        // the other three legs stand
        expect(m.legs.filter((_, k) => k !== g.leg).every((L) => L.y === 0)).toBe(true);
      }
    }
    expect(scraped).toBeGreaterThan(30);
    expect([...legsUsed].every((k) => k === LF || k === RF)).toBe(true);
    // afterwards both forelegs are down and square again
    while (m.gesture.state.id) stepMotion(m, FRAME, state);
    for (let i = 0; i < 60; i++) stepMotion(m, FRAME, state);
    expect(m.legs.every((L) => L.y === 0 && Math.abs(L.dz) < 0.06)).toBe(true);
  });

  it('is taken back smoothly when the horse sets off', () => {
    const m = createMotion({ rng: createRng(5) });
    const state = { gait: 'halt', speed: 0, turnRate: 0 };
    let guard = 0;
    while (m.gesture.state.id !== 'paw' && guard++ < 200 * 60) stepMotion(m, FRAME, state);
    expect(m.gesture.state.id).toBe('paw');
    while (m.gesture.state.weight < 0.9) stepMotion(m, FRAME, state);
    const leg = m.gesture.state.leg;
    let prev = m.legs[leg].y;
    state.gait = 'walk';
    state.speed = 1;
    let maxStep = 0;
    for (let i = 0; i < 90; i++) {
      stepMotion(m, FRAME, state);
      maxStep = Math.max(maxStep, Math.abs(m.legs[leg].y - prev));
      prev = m.legs[leg].y;
    }
    expect(maxStep).toBeLessThan(0.07);
    expect(m.gesture.state.id).toBe(null);
  });

  it('never starts while the horse moves or grazes', () => {
    for (const state of [
      { gait: 'walk', speed: 1.2, turnRate: 0 },
      { gait: 'halt', speed: 0, turnRate: 0, graze: 1 },
    ]) {
      const m = createMotion({ rng: createRng(6) });
      let started = false;
      for (let i = 0; i < 120 * 60; i++) {
        stepMotion(m, FRAME, state);
        if (m.gesture.state.id) started = true;
      }
      expect(started).toBe(false);
    }
  });
});
