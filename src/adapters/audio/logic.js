// Reine Logik des Klang-Moduls (ohne WebAudio): Lautstärke-Mapping, Musik-Zustand,
// Scheduler-Zeitberechnung, Zufall und Impulsantwort für den Hall.

export const DEFAULT_VOLUME = 0.5;

export function clamp01(value, fallback = DEFAULT_VOLUME) {
  if (typeof value !== 'number' || Number.isNaN(value)) return fallback;
  return Math.min(1, Math.max(0, value));
}

// API-Lautstärke 0..1 linear, intern quadratisch (perzeptiv): 0,5 -> 0,25 (-12 dB).
export function volumeToGain(volume) {
  const v = clamp01(volume);
  return v * v;
}

// Stumm behält die Lautstärke im Zustand und setzt nur den Kanal auf 0.
export function channelGain(volume, muted) {
  return muted ? 0 : volumeToGain(volume);
}

export function normalizeSettings(input = {}) {
  return {
    musicVolume: clamp01(input.musicVolume),
    musicMuted: input.musicMuted === true,
    sfxVolume: clamp01(input.sfxVolume),
    sfxMuted: input.sfxMuted === true,
  };
}

// Soll die Melodie gerade klingen? Bei Lautstärke 0 läuft sie weiter (kein Neustart beim Ziehen
// des Reglers), bei Stumm oder im Hintergrund nicht.
export function shouldMusicRun({ wanted, hidden, muted }) {
  return Boolean(wanted) && !hidden && !muted;
}

export function midiToFreq(midi) {
  return 440 * 2 ** ((midi - 69) / 12);
}

// Lookahead-Scheduler: liefert alle Schritte, die im Fenster [now, now + lookahead) beginnen.
// Ist der nächste Schritt weit in der Vergangenheit (Timer gedrosselt), wird neu aufgesetzt,
// statt einen Stau nachzuholen.
export function planSteps({
  now,
  nextTime,
  step,
  lookahead,
  stepDuration,
  loopSteps,
  maxLate = 0.25,
  restartDelay = 0.03,
}) {
  let time = nextTime;
  let index = step;
  if (time < now - maxLate) time = now + restartDelay;
  const events = [];
  while (time < now + lookahead) {
    events.push({ step: index, time: Math.max(time, now) });
    time += stepDuration;
    index = (index + 1) % loopSteps;
  }
  return { events, nextTime: time, step: index };
}

// Kleiner deterministischer Zufallsgenerator (mulberry32) für Rauschen und Hall.
export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Synthetische Raum-Impulsantwort: abklingendes Rauschen, leicht abgedunkelt.
export function generateImpulse({
  sampleRate,
  seconds = 1.8,
  decay = 3,
  channels = 2,
  rng = mulberry32(7),
}) {
  const length = Math.max(1, Math.floor(sampleRate * seconds));
  const attack = Math.max(1, Math.floor(sampleRate * 0.004));
  const data = [];
  for (let c = 0; c < channels; c++) {
    const out = new Float32Array(length);
    let low = 0;
    for (let i = 0; i < length; i++) {
      const fade = (1 - i / length) ** decay * Math.min(1, i / attack);
      low += 0.45 * (rng() * 2 - 1 - low);
      out[i] = low * fade;
    }
    data.push(out);
  }
  return data;
}
