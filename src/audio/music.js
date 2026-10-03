// Stimmen der Menü-Melodie. Jede Funktion plant eine Note ab Zeit `t` auf den Ausgang `out`.

import { jitter, noiseBurst, tone } from './dsp.js';
import { midiToFreq } from './logic.js';
import { STEP_SECONDS } from './melody.js';

const SILENT = 0.0001;

// Weiche Note: kurzer Anstieg, langsames Abklingen, Release nach der Notenlänge
function softNote(v, out, t, { type, freq, detune = 0, peak, length }) {
  const ctx = v.ctx;
  const g = ctx.createGain();
  const hold = t + Math.max(0.05, length * 0.9);
  g.gain.setValueAtTime(SILENT, t);
  g.gain.exponentialRampToValueAtTime(peak, t + 0.015);
  g.gain.exponentialRampToValueAtTime(peak * 0.5, hold);
  g.gain.exponentialRampToValueAtTime(SILENT, hold + 0.14);
  g.connect(out);
  const osc = ctx.createOscillator();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t);
  osc.detune.setValueAtTime(detune, t);
  osc.connect(g);
  osc.start(t);
  osc.stop(hold + 0.18);
}

export function playMusicEvent(v, out, ev, t) {
  const length = ev.steps * STEP_SECONDS;
  const freq = midiToFreq(ev.midi);
  switch (ev.voice) {
    case 'melody':
      for (const detune of [-4, 4]) {
        softNote(v, out, t, { type: 'triangle', freq, detune, peak: 0.1, length });
      }
      break;
    case 'bass':
      softNote(v, out, t, { type: 'sine', freq, peak: 0.3, length });
      softNote(v, out, t, { type: 'triangle', freq: freq * 2, peak: 0.04, length });
      break;
    case 'arp':
      tone(v, out, t, { type: 'sine', freq, attack: 0.006, decay: 0.38, peak: 0.07 });
      tone(v, out, t, {
        type: 'triangle',
        freq: freq * 2,
        attack: 0.004,
        decay: 0.15,
        peak: 0.012,
      });
      break;
    case 'tick':
      noiseBurst(v, out, t, {
        filter: 'highpass',
        freq: 5200 * jitter(v, 0.05),
        attack: 0.002,
        decay: 0.04,
        peak: 0.03,
      });
      break;
    default:
  }
}
