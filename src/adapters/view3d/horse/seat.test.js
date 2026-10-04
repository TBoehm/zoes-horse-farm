import { describe, expect, it } from 'vitest';
import { riderSeat } from './seat.js';

const W = (g) => ({ halt: 0, walk: 0, trot: 0, canter: 0, back: 0, [g]: 1 });
const ctx = (g, extra = {}) => ({
  weights: W(g),
  phi: 0,
  jumpWeight: 0,
  jumpJ: 0,
  hopWeight: 0,
  stopWeight: 0,
  pitch: 0,
  ...extra,
});

describe('rider seat per gait', () => {
  it('halt/walk/back: upright sitting seat in the saddle', () => {
    for (const g of ['halt', 'walk', 'back']) {
      const s = riderSeat({ gait: g }, ctx(g));
      expect(s.lean).toBeLessThan(0.2);
      expect(s.rise).toBeCloseTo(0, 3);
    }
  });

  it('trot: rising trot – up and down once per stride', () => {
    const rises = [];
    for (let i = 0; i < 40; i++)
      rises.push(riderSeat({ gait: 'trot' }, ctx('trot', { phi: i / 40 })).rise);
    expect(Math.max(...rises)).toBeGreaterThan(0.08);
    expect(Math.min(...rises)).toBeLessThan(0.01);
    const ups = rises.filter((r, i) => r > 0.05 && rises[(i + 39) % 40] <= 0.05).length;
    expect(ups).toBe(1);
  });

  it('canter: light seat (out of the saddle, leaning forward)', () => {
    const s = riderSeat({ gait: 'canter' }, ctx('canter'));
    expect(s.lean).toBeGreaterThan(0.35);
    expect(s.lean).toBeLessThan(0.75);
    expect(s.rise).toBeGreaterThan(0.03);
  });

  it('jump flight: two-point seat folded forward, hands following the mouth', () => {
    const canter = riderSeat({ gait: 'canter' }, ctx('canter'));
    const s = riderSeat(
      { gait: 'canter', jump: { phase: 'flight', progress: 0.5 } },
      ctx('canter', { jumpWeight: 1, jumpJ: 1.5 }),
    );
    expect(s.lean).toBeGreaterThan(0.8);
    expect(s.handZ).toBeGreaterThan(canter.handZ + 0.1);
  });

  it('refusal stop: rider sits back', () => {
    const s = riderSeat({ gait: 'halt' }, ctx('halt', { stopWeight: 1 }));
    expect(s.lean).toBeLessThan(0.05);
  });

  it('works without horse context (derived from state.gait)', () => {
    const s = riderSeat({ gait: 'canter' });
    expect(s.lean).toBeGreaterThan(0.35);
  });
});
