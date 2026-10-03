// Kleine Bausteine der Synthese. `v` ist der Stimmen-Kontext { ctx, noise, rng, state }.

import { generateImpulse, mulberry32 } from './logic.js';

const SILENT = 0.0001;

export function createNoiseBuffer(ctx, rng = mulberry32(1337), seconds = 2) {
  const length = Math.floor(ctx.sampleRate * seconds);
  const buffer = ctx.createBuffer(1, length, ctx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < length; i++) data[i] = rng() * 2 - 1;
  return buffer;
}

export function createImpulseBuffer(ctx, options = {}) {
  const channels = generateImpulse({ sampleRate: ctx.sampleRate, ...options });
  const buffer = ctx.createBuffer(channels.length, channels[0].length, ctx.sampleRate);
  channels.forEach((data, c) => buffer.getChannelData(c).set(data));
  return buffer;
}

// Hüllkurve: Anstieg, optional Halten, exponentielles Ausklingen
function envelope(ctx, out, t, { attack, hold = 0, decay, peak }) {
  const g = ctx.createGain();
  const end = t + attack + hold + decay;
  g.gain.setValueAtTime(SILENT, t);
  g.gain.exponentialRampToValueAtTime(Math.max(peak, SILENT), t + attack);
  if (hold > 0) g.gain.setValueAtTime(Math.max(peak, SILENT), t + attack + hold);
  g.gain.exponentialRampToValueAtTime(SILENT, end);
  g.connect(out);
  return { gain: g, end };
}

export function tone(v, out, t, o) {
  const { type = 'sine', freq, freqEnd, glide = 0.1, attack = 0.003, hold = 0, decay, peak } = o;
  const osc = v.ctx.createOscillator();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t);
  if (freqEnd) osc.frequency.exponentialRampToValueAtTime(freqEnd, t + glide);
  if (o.detune) osc.detune.setValueAtTime(o.detune, t);
  const env = envelope(v.ctx, out, t, { attack, hold, decay, peak });
  osc.connect(env.gain);
  osc.start(t);
  osc.stop(env.end + 0.03);
  return osc;
}

export function noiseBurst(v, out, t, o) {
  const { filter = 'bandpass', freq, freqEnd, q = 1, attack = 0.003, hold = 0, decay, peak } = o;
  const src = v.ctx.createBufferSource();
  src.buffer = v.noise;
  src.loop = true;
  const f = v.ctx.createBiquadFilter();
  f.type = filter;
  f.frequency.setValueAtTime(freq, t);
  if (freqEnd) f.frequency.exponentialRampToValueAtTime(freqEnd, t + attack + hold + decay);
  f.Q.setValueAtTime(q, t);
  const env = envelope(v.ctx, out, t, { attack, hold, decay, peak });
  src.connect(f);
  f.connect(env.gain);
  src.start(t, v.rng() * (v.noise.duration - 0.1));
  src.stop(env.end + 0.03);
  return src;
}

// Zufallsabweichung um 1: jitter(0.05) -> 0,95..1,05
export function jitter(v, amount) {
  return 1 + (v.rng() * 2 - 1) * amount;
}
