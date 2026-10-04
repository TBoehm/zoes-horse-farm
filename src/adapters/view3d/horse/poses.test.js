import { describe, expect, it } from 'vitest';
import { JUMP_KEYS, POSE_KEYS, POSE_SIZE, jumpParam, samplePoses } from './poses.js';

describe('jump poses', () => {
  it('Phase + progress → J ∈ [0, 3]', () => {
    expect(jumpParam(null)).toBe(0);
    expect(jumpParam({ phase: 'takeoff', progress: 0.5 })).toBe(0.5);
    expect(jumpParam({ phase: 'flight', progress: 0.25 })).toBe(1.25);
    expect(jumpParam({ phase: 'landing', progress: 1 })).toBe(3);
    expect(jumpParam({ phase: 'landing', progress: 7 })).toBe(3);
  });

  it('hits the key poses at the knots', () => {
    JUMP_KEYS.forEach((k, i) => {
      const p = samplePoses(JUMP_KEYS, i * 0.5);
      for (let j = 0; j < POSE_SIZE; j++) expect(p[j]).toBeCloseTo(k[j], 6);
    });
  });

  it('take-off nose up, flight bascule, landing nose down', () => {
    const pitch = POSE_KEYS.indexOf('pitch');
    const bend = POSE_KEYS.indexOf('bend');
    expect(samplePoses(JUMP_KEYS, 0.9)[pitch]).toBeLessThan(-0.2);
    expect(samplePoses(JUMP_KEYS, 1.5)[bend]).toBeGreaterThan(0.1);
    expect(samplePoses(JUMP_KEYS, 2.0)[pitch]).toBeGreaterThan(0.2);
  });
});
