import { describe, expect, it } from 'vitest';
import {
  BARS,
  BPM,
  LOOP_STEPS,
  STEPS_PER_BAR,
  STEP_SECONDS,
  buildLoop,
  parseBar,
  parseNote,
} from './melody.js';

describe('parseNote', () => {
  it('converts note names to MIDI', () => {
    expect(parseNote('C4')).toBe(60);
    expect(parseNote('A4')).toBe(69);
    expect(parseNote('C5')).toBe(72);
    expect(parseNote('F#4')).toBe(66);
    expect(parseNote('Bb3')).toBe(58);
  });

  it('rejects invalid input', () => {
    expect(() => parseNote('H4')).toThrow();
  });
});

describe('parseBar', () => {
  it('reads notes and rests with their start step', () => {
    expect(parseBar('E5:2 -:2 C5:4')).toEqual([
      { start: 0, steps: 2, midi: 76 },
      { start: 2, steps: 2, midi: null },
      { start: 4, steps: 4, midi: 72 },
    ]);
  });
});

describe('melody data', () => {
  it('has 16 bars of 8 steps each at 100 bpm', () => {
    expect(BARS).toHaveLength(16);
    expect(LOOP_STEPS).toBe(16 * STEPS_PER_BAR);
    expect(BPM).toBe(100);
    expect(STEP_SECONDS).toBeCloseTo(0.3);
  });

  it('every melody bar fills exactly 8 steps', () => {
    for (const [i, bar] of BARS.entries()) {
      const total = parseBar(bar.melody).reduce((sum, n) => sum + n.steps, 0);
      expect(total, `bar ${i + 1}`).toBe(STEPS_PER_BAR);
    }
  });

  it('melody notes lie in a singable range', () => {
    for (const bar of BARS) {
      for (const n of parseBar(bar.melody)) {
        if (n.midi !== null) {
          expect(n.midi).toBeGreaterThanOrEqual(65);
          expect(n.midi).toBeLessThanOrEqual(84);
        }
      }
    }
  });

  it('melody ends on the root note C', () => {
    const last = parseBar(BARS.at(-1).melody)[0];
    expect(last.midi % 12).toBe(0);
  });
});

describe('buildLoop', () => {
  const steps = buildLoop();
  const events = steps.flat();

  it('has one entry per step', () => {
    expect(steps).toHaveLength(LOOP_STEPS);
  });

  it('contains all voices with valid values', () => {
    const voices = new Set(events.map((e) => e.voice));
    expect([...voices].sort()).toEqual(['arp', 'bass', 'melody', 'tick']);
    for (const e of events) {
      expect(Number.isInteger(e.steps)).toBe(true);
      expect(e.steps).toBeGreaterThan(0);
      expect(Number.isFinite(e.midi)).toBe(true);
    }
  });

  it('every bar starts with bass and melody (except rests)', () => {
    for (let b = 0; b < BARS.length; b++) {
      const first = steps[b * STEPS_PER_BAR].map((e) => e.voice);
      expect(first).toContain('bass');
      expect(first).toContain('melody');
    }
  });

  it('bass is low, accompaniment below the melody', () => {
    for (const e of events) {
      if (e.voice === 'bass') expect(e.midi).toBeLessThan(55);
      if (e.voice === 'arp') expect(e.midi).toBeLessThan(70);
    }
  });
});
