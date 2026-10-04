import { describe, expect, it } from 'vitest';
import { createSeatFilter, crestRelease, riderSeat, riderSeatParts } from './seat.js';

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

const jumpAt = (J) => riderSeat({ gait: 'canter' }, ctx('canter', { jumpWeight: 1, jumpJ: J }));

describe('crest release', () => {
  it('is zero on the approach and after the landing, full in flight', () => {
    expect(crestRelease(0.2)).toBeCloseTo(0, 6);
    expect(crestRelease(1.5)).toBeGreaterThan(0.99);
    expect(crestRelease(3)).toBeCloseTo(0, 6);
    expect(crestRelease(1.5, 0)).toBe(0);
  });

  it('hands slide forward and down along the neck in flight and come back on landing', () => {
    const approach = jumpAt(0.05);
    const flight = jumpAt(1.5);
    const landed = jumpAt(2.95);
    expect(flight.handZ).toBeGreaterThan(approach.handZ + 0.2);
    expect(flight.handY).toBeLessThan(approach.handY - 0.05);
    expect(landed.handZ).toBeCloseTo(approach.handZ, 2);
    // the contact is taken up again before the torso is fully upright
    const touchdown = jumpAt(2.4);
    expect(touchdown.handZ).toBeLessThan(flight.handZ - 0.05);
    expect(touchdown.lean).toBeGreaterThan(landed.lean);
  });

  it('the seat absorbs the touchdown (sinks a little)', () => {
    expect(jumpAt(2.2).rise).toBeLessThan(jumpAt(1.5).rise - 0.03);
  });

  it('is a continuous function of the jump parameter', () => {
    let prev = jumpAt(0);
    for (let J = 0.01; J <= 3; J += 0.01) {
      const s = jumpAt(J);
      for (const k of ['rise', 'forward', 'lean', 'handZ', 'handY']) {
        expect(Math.abs(s[k] - prev[k]), `${k} at J=${J}`).toBeLessThan(0.03);
      }
      prev = s;
    }
  });
});

describe('seat filter', () => {
  const step = (filter, c, n = 1, dt = 1 / 60) => {
    const out = {};
    for (let i = 0; i < n; i++) filter.step(dt, { gait: 'canter' }, c, out);
    return out;
  };

  it('starts at the target (no swing in from zero)', () => {
    const f = createSeatFilter();
    const c = ctx('canter');
    const first = step(f, c);
    expect(first.lean).toBeCloseTo(riderSeat({ gait: 'canter' }, c).lean, 6);
  });

  it('settles on the unfiltered seat', () => {
    const f = createSeatFilter();
    const c = ctx('trot', { phi: 0.3 });
    step(f, ctx('halt'), 5);
    const out = step(f, c, 120);
    const raw = riderSeat({ gait: 'trot' }, c);
    for (const k of ['rise', 'forward', 'lean', 'handX', 'handY', 'handZ', 'footForward']) {
      expect(out[k]).toBeCloseTo(raw[k], 3);
    }
  });

  it('turns an abrupt stop into a quick but smooth move', () => {
    const f = createSeatFilter();
    step(f, ctx('canter'), 10);
    const stopped = ctx('canter', { stopWeight: 1 });
    const raw = riderSeat({ gait: 'canter' }, stopped);
    let prev = step(f, ctx('canter')).lean;
    let maxStep = 0;
    for (let i = 0; i < 40; i++) {
      const lean = step(f, stopped).lean;
      maxStep = Math.max(maxStep, Math.abs(lean - prev));
      prev = lean;
    }
    const jump = Math.abs(riderSeat({ gait: 'canter' }, ctx('canter')).lean - raw.lean);
    expect(maxStep).toBeLessThan(jump * 0.2);
    expect(prev).toBeCloseTo(raw.lean, 2); // reached within 0.7 s
  });

  it('keeps the posting beat exactly in step with the stride (no lag)', () => {
    const f = createSeatFilter();
    step(f, ctx('trot'), 120);
    for (const phi of [0, 0.25, 0.5, 0.75]) {
      const c = ctx('trot', { phi });
      expect(step(f, c).rise).toBeCloseTo(riderSeat({ gait: 'trot' }, c).rise, 3);
    }
  });

  it('balances against the horse pitch immediately', () => {
    const f = createSeatFilter();
    step(f, ctx('canter'), 60);
    const level = step(f, ctx('canter')).lean;
    const nose = step(f, ctx('canter', { pitch: 0.3 })).lean;
    expect(level - nose).toBeCloseTo(0.15, 3);
  });
});

describe('seat parts', () => {
  it('posting goes to the immediate part, the pose to the calm part', () => {
    const base = {};
    const osc = {};
    riderSeatParts({ gait: 'trot' }, ctx('trot', { phi: 0.5 }), base, osc);
    expect(osc.rise).toBeGreaterThan(0.09);
    expect(base.rise).toBeCloseTo(0, 6);
    expect(base.lean).toBeCloseTo(0.25, 6);
  });

  it('the rhythmic part fades out in the two-point seat', () => {
    const base = {};
    const osc = {};
    riderSeatParts(
      { gait: 'canter' },
      ctx('trot', { phi: 0.5, jumpWeight: 1, jumpJ: 1.5 }),
      base,
      osc,
    );
    expect(Math.abs(osc.rise)).toBeLessThan(0.005);
  });
});
