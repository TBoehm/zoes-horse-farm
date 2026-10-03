import { describe, expect, it } from 'vitest';
import { createNoiseBuffer } from './dsp.js';
import { mulberry32 } from './logic.js';
import * as sfx from './sfx.js';

// Recording fake of the WebAudio graph: remembers the filter type, its center frequency and the
// envelope of the gain node behind it, so that the test can see which sounds an effect schedules.
function param() {
  return {
    calls: [],
    setValueAtTime(v, t) {
      this.calls.push({ kind: 'set', v, t });
    },
    exponentialRampToValueAtTime(v, t) {
      this.calls.push({ kind: 'ramp', v, t });
    },
  };
}

function makeRecorder() {
  const nodes = [];
  const node = (kind) => {
    const n = {
      kind,
      next: null,
      gain: param(),
      frequency: param(),
      Q: param(),
      detune: param(),
      connect(to) {
        this.next = to;
        return to;
      },
      start() {},
      stop() {},
    };
    nodes.push(n);
    return n;
  };
  const ctx = {
    sampleRate: 8000,
    createGain: () => node('gain'),
    createOscillator: () => node('osc'),
    createBufferSource: () => node('source'),
    createBiquadFilter: () => node('filter'),
    createBuffer: (channels, length, rate) => ({
      duration: length / rate,
      getChannelData: () => new Float32Array(length),
    }),
  };
  const voice = { ctx, rng: mulberry32(1), state: {} };
  voice.noise = createNoiseBuffer(ctx);
  const out = node('out');
  nodes.length = 0;
  return { voice, out, nodes };
}

// Filtered noise bursts: { type, freq, peak, length } with the envelope of the gain behind the filter
function noiseBursts(nodes) {
  return nodes
    .filter((n) => n.kind === 'filter')
    .map((f) => {
      const env = f.next.gain.calls;
      const start = env[0].t;
      return {
        type: f.type,
        freq: f.frequency.calls[0].v,
        peak: Math.max(...env.map((c) => c.v)),
        length: env[env.length - 1].t - start,
      };
    });
}

function render(play, ...args) {
  const { voice, out, nodes } = makeRecorder();
  play(voice, out, 0, ...args);
  return noiseBursts(nodes);
}

// Small speakers (phones, tablets) hardly play anything below 350 Hz: every footfall and landing
// needs a short mid-range "clop" on top of the dull thud.
const isClop = (b) =>
  b.type === 'bandpass' &&
  b.freq >= 1000 &&
  b.freq <= 2500 &&
  b.length >= 0.015 &&
  b.length <= 0.03;

describe('mid-range clop for small speakers', () => {
  for (const gait of ['walk', 'trot', 'canter']) {
    it(`hoof (${gait}) has a clop between 1 and 2.5 kHz that is clearly audible`, () => {
      const clop = render(sfx.hoof, gait).filter(isClop);
      expect(clop.length).toBeGreaterThanOrEqual(1);
      expect(Math.max(...clop.map((b) => b.peak))).toBeGreaterThanOrEqual(0.7);
    });
  }

  it('landing has a clop for each of its two hoof impacts', () => {
    const clop = render(sfx.landing).filter(isClop);
    expect(clop.length).toBeGreaterThanOrEqual(2);
    expect(Math.max(...clop.map((b) => b.peak))).toBeGreaterThanOrEqual(1.5);
  });

  it('the louder gaits are not quieter in the mid-range', () => {
    const peak = (gait) =>
      Math.max(
        ...render(sfx.hoof, gait)
          .filter(isClop)
          .map((b) => b.peak),
      );
    expect(peak('canter')).toBeGreaterThanOrEqual(peak('trot'));
    expect(peak('trot')).toBeGreaterThanOrEqual(peak('walk'));
  });
});

describe('effect names', () => {
  it('exports only the voices that are used', () => {
    expect(Object.keys(sfx).sort()).toEqual(
      ['finishSignal', 'hoof', 'landing', 'railDown', 'startSignal', 'takeoff'].sort(),
    );
  });
});
