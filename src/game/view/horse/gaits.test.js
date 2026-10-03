import { describe, expect, it } from 'vitest';
import { GAITS, MAX_STANCE_TRAVEL, legPhase, legSample, offsetsFor } from './gaits.js';

describe('Gangarten: Takt und Schrittlänge (Reitlehre-Kennwerte)', () => {
  it('Schritt ≈ 55/min, Trab ≈ 80/min, Galopp ≈ 100/min bei typischem Tempo', () => {
    expect(GAITS.walk.freq(1.6) * 60).toBeCloseTo(55, -1);
    expect(GAITS.trot.freq(3.2) * 60).toBeCloseTo(80, -1);
    expect(GAITS.canter.freq(6) * 60).toBeCloseTo(100, -1);
  });

  it('Galoppsprung ≈ 3,7 m bei 6 m/s; Tempo steigt vor allem über die Schrittlänge', () => {
    const stride = (v) => v / GAITS.canter.freq(v);
    expect(stride(6)).toBeGreaterThan(3.4);
    expect(stride(6)).toBeLessThan(3.9);
    const fRatio = GAITS.canter.freq(8) / GAITS.canter.freq(5);
    const sRatio = stride(8) / stride(5);
    expect(sRatio).toBeGreaterThan(fRatio);
  });

  it('Duty Factor: Schritt ≈ 0,6 (keine Schwebe), Trab 0,35–0,45, Galopp 0,3–0,4', () => {
    expect(GAITS.walk.duty(1.6)).toBeGreaterThan(0.5);
    for (const v of [2, 3, 4]) {
      expect(GAITS.trot.duty(v)).toBeGreaterThanOrEqual(0.35);
      expect(GAITS.trot.duty(v)).toBeLessThanOrEqual(0.45);
    }
    for (const v of [4.5, 6, 8]) {
      expect(GAITS.canter.duty(v)).toBeGreaterThanOrEqual(0.3);
      expect(GAITS.canter.duty(v)).toBeLessThanOrEqual(0.4);
    }
  });

  it('Rechtsgalopp ist der gespiegelte Linksgalopp', () => {
    const l = offsetsFor('canter', 1);
    const r = offsetsFor('canter', -1);
    expect([r[0], r[1], r[2], r[3]]).toEqual([l[1], l[0], l[3], l[2]]);
  });
});

describe('Huf-Bahn', () => {
  it('Stützphase am Boden, Schwungphase angehoben', () => {
    const f = GAITS.trot.freq(3);
    const d = GAITS.trot.duty(3);
    const stance = legSample('trot', 0, d / 2, 3, f);
    expect(stance.stance).toBe(true);
    expect(stance.y).toBe(0);
    const swing = legSample('trot', 0, d + (1 - d) / 2, 3, f);
    expect(swing.stance).toBe(false);
    expect(swing.y).toBeGreaterThan(0.1);
    expect(swing.flex).toBeGreaterThan(0.5);
  });

  it('Bahn ist an den Phasengrenzen stetig', () => {
    const v = 6;
    const f = GAITS.canter.freq(v);
    const d = GAITS.canter.duty(v);
    const off = offsetsFor('canter', 1)[0];
    for (const p of [d, 1]) {
      const a = legSample('canter', 0, (off + p - 1e-6) % 1, v, f);
      const b = legSample('canter', 0, (off + p + 1e-6) % 1, v, f);
      expect(a.dz).toBeCloseTo(b.dz, 3);
      expect(a.y).toBeCloseTo(b.y, 3);
    }
  });

  it('Hufweg je Stützphase ist begrenzt (kein Überstrecken)', () => {
    const f = GAITS.canter.freq(8);
    const d = GAITS.canter.duty(8);
    const a = legSample('canter', 3, 0, 8, f);
    const b = legSample('canter', 3, d - 1e-6, 8, f);
    expect(a.dz - b.dz).toBeLessThanOrEqual(MAX_STANCE_TRAVEL + 1e-6);
  });

  it('legPhase bleibt in [0, 1)', () => {
    expect(legPhase(0.1, 0.75)).toBeCloseTo(0.35);
    expect(legPhase(0.9, 0.25)).toBeCloseTo(0.65);
  });
});
