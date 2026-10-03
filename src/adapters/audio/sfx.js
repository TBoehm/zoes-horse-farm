// Effects: pure synthesis. Each function schedules its sounds from time `t` onto the output `out`.

import { jitter, noiseBurst, tone } from './dsp.js';
import { midiToFreq } from './logic.js';

// Sand surface: dull thud (sine with pitch drop) + soft noise, barely any click.
const GAITS = {
  walk: { vol: 0.62, freq: 105, noiseFreq: 520, noiseDecay: 0.07 },
  trot: { vol: 0.8, freq: 122, noiseFreq: 700, noiseDecay: 0.06 },
  canter: { vol: 0.98, freq: 138, noiseFreq: 860, noiseDecay: 0.085 },
};

export const GAIT_NAMES = Object.keys(GAITS);

function thud(v, out, t, { freq, peak, decay = 0.1, sand = 1 }) {
  tone(v, out, t, { freq, freqEnd: freq * 0.42, glide: 0.07, decay, peak });
  noiseBurst(v, out, t, {
    filter: 'lowpass',
    freq: 380,
    q: 0.7,
    decay: decay * 0.9,
    peak: peak * 0.5 * sand,
  });
}

export function hoof(v, out, t, gait) {
  const g = GAITS[gait];
  if (!g) return;
  v.state.hoof = (v.state.hoof ?? 0) + 1;
  const side = v.state.hoof % 2 === 0 ? 1 : 1.06; // left/right hoof slightly different
  const vol = g.vol * jitter(v, 0.1);
  const freq = g.freq * side * jitter(v, 0.04);
  tone(v, out, t, { freq, freqEnd: freq * 0.45, glide: 0.06, decay: 0.07, peak: 0.55 * vol });
  noiseBurst(v, out, t, {
    freq: g.noiseFreq * jitter(v, 0.08),
    q: 0.8,
    attack: 0.004,
    decay: g.noiseDecay,
    peak: 0.34 * vol,
  });
  noiseBurst(v, out, t, { filter: 'lowpass', freq: 260, decay: 0.06, peak: 0.3 * vol });
  noiseBurst(v, out, t, {
    filter: 'highpass',
    freq: 2600,
    attack: 0.001,
    decay: 0.012,
    peak: 0.035 * vol,
  });
}

export function takeoff(v, out, t) {
  thud(v, out, t, { freq: 150, peak: 0.6, decay: 0.1 });
  thud(v, out, t + 0.085, { freq: 175, peak: 0.85, decay: 0.12 });
  // Snort: burst of air, filter falls
  noiseBurst(v, out, t + 0.05, {
    freq: 1900,
    freqEnd: 700,
    q: 1.2,
    attack: 0.05,
    decay: 0.28,
    peak: 0.4,
  });
  noiseBurst(v, out, t + 0.06, { freq: 3200, q: 3, attack: 0.04, decay: 0.2, peak: 0.07 });
}

export function landing(v, out, t) {
  thud(v, out, t, { freq: 112, peak: 0.9, decay: 0.15 });
  thud(v, out, t + 0.07, { freq: 100, peak: 0.8, decay: 0.16 });
  noiseBurst(v, out, t, { freq: 700, q: 0.8, decay: 0.13, peak: 0.28 });
  noiseBurst(v, out, t + 0.03, { freq: 1200, q: 0.7, attack: 0.03, decay: 0.2, peak: 0.08 });
}

function woodClack(v, out, t, freq, peak) {
  tone(v, out, t, { freq, freqEnd: freq * 0.92, glide: 0.05, attack: 0.001, decay: 0.07, peak });
  tone(v, out, t, { freq: freq * 2.7, attack: 0.001, decay: 0.035, peak: peak * 0.45 });
  noiseBurst(v, out, t, { freq: freq * 3, q: 2.5, attack: 0.001, decay: 0.025, peak: peak * 0.6 });
}

export function railDown(v, out, t) {
  // Wood on wood: hits get denser and quieter, then impact and bouncing on sand
  const hits = [
    [0, 780, 0.8],
    [0.085, 640, 0.6],
    [0.14, 910, 0.44],
    [0.18, 700, 0.36],
    [0.21, 820, 0.24],
    [0.235, 740, 0.16],
  ];
  for (const [dt, f, p] of hits) woodClack(v, out, t + dt, f * jitter(v, 0.05), p);
  thud(v, out, t + 0.3, { freq: 95, peak: 0.85, decay: 0.16, sand: 1.1 });
  thud(v, out, t + 0.39, { freq: 110, peak: 0.3, decay: 0.08 });
  woodClack(v, out, t + 0.385, 600, 0.3);
  woodClack(v, out, t + 0.43, 680, 0.12);
}

// Bell with inharmonic partials (Risset), plus a short strike click
const BELL = [
  [0.56, 1.0, 1.0],
  [0.92, 0.67, 0.9],
  [1.19, 1.0, 0.65],
  [1.71, 1.8, 0.55],
  [2.0, 2.67, 0.33],
  [2.74, 1.67, 0.35],
  [3.0, 1.46, 0.25],
  [3.76, 1.33, 0.2],
  [4.07, 1.33, 0.15],
];

export function startSignal(v, out, t) {
  const f0 = 440;
  const total = BELL.reduce((s, b) => s + b[1], 0);
  for (const [ratio, amp, life] of BELL) {
    tone(v, out, t, {
      freq: f0 * ratio,
      attack: 0.002,
      decay: 2.2 * life,
      peak: (1.08 * amp) / total,
    });
  }
  noiseBurst(v, out, t, { filter: 'highpass', freq: 3000, attack: 0.001, decay: 0.03, peak: 0.1 });
}

function chime(v, out, t, midi, peak, decay) {
  const f = midiToFreq(midi);
  tone(v, out, t, { type: 'triangle', freq: f, attack: 0.003, decay, peak });
  tone(v, out, t, { freq: f * 2.76, attack: 0.002, decay: decay * 0.3, peak: peak * 0.25 });
}

export function finishSignal(v, out, t) {
  // Ascending run in C major, then a bright final chord
  const run = [72, 76, 79, 76, 79, 84];
  run.forEach((midi, i) => chime(v, out, t + i * 0.09, midi, 0.32, 0.3));
  const end = t + run.length * 0.09 + 0.04;
  for (const midi of [72, 76, 79, 84, 88]) chime(v, out, end, midi, 0.2, 0.9);
}
