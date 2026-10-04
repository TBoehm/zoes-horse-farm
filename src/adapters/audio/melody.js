// Menu melody as data: 16 bars in C major, 100 bpm, eighth-note grid (8 steps per bar).
// Melody, bass, light arpeggio accompaniment and a quiet brush tick on beats 2 and 4.

export const BPM = 100;
export const STEPS_PER_BAR = 8;
export const STEP_SECONDS = 60 / BPM / 2;

const SEMITONES = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

// 'C5' -> 72 (C4 = 60), with '#' and 'b'
export function parseNote(name) {
  const m = /^([A-G])([#b]?)(-?\d)$/.exec(name);
  if (!m) throw new Error(`Invalid note: ${name}`);
  const accidental = m[2] === '#' ? 1 : m[2] === 'b' ? -1 : 0;
  return (Number(m[3]) + 1) * 12 + SEMITONES[m[1]] + accidental;
}

// 'E5:2 G5:2 -:4' -> [{ start, steps, midi|null }]; "-" is a rest
export function parseBar(text) {
  const notes = [];
  let start = 0;
  for (const token of text.trim().split(/\s+/)) {
    const [name, len] = token.split(':');
    const steps = Number(len);
    notes.push({ start, steps, midi: name === '-' ? null : parseNote(name) });
    start += steps;
  }
  return notes;
}

// bass: root and fifth (MIDI); arp: two chord tones for the accompaniment
const CHORDS = {
  C: { bass: [48, 43], arp: [64, 67] },
  Am: { bass: [45, 52], arp: [60, 64] },
  F: { bass: [41, 48], arp: [57, 60] },
  G: { bass: [43, 50], arp: [59, 62] },
  Dm: { bass: [50, 45], arp: [57, 62] },
};

// Per bar: melody and chord(s); two chords = one per half bar
export const BARS = [
  { melody: 'E5:2 G5:2 E5:1 D5:1 C5:2', chords: 'C' },
  { melody: 'A4:2 C5:2 E5:3 D5:1', chords: 'Am' },
  { melody: 'C5:2 A4:2 F4:2 A4:2', chords: 'F' },
  { melody: 'D5:3 B4:1 G4:2 -:2', chords: 'G' },
  { melody: 'E5:2 G5:2 E5:1 D5:1 C5:2', chords: 'C' },
  { melody: 'E5:2 D5:2 C5:2 A4:2', chords: 'Am' },
  { melody: 'D5:2 F5:2 D5:2 A4:2', chords: 'Dm' },
  { melody: 'D5:2 B4:2 G4:4', chords: 'G' },
  { melody: 'A5:2 C6:2 A5:2 F5:2', chords: 'F' },
  { melody: 'E5:2 G5:2 C6:4', chords: 'C' },
  { melody: 'A5:2 F5:2 D5:2 F5:2', chords: 'Dm' },
  { melody: 'G5:2 F5:2 D5:4', chords: 'G' },
  { melody: 'E5:2 G5:2 E5:1 D5:1 C5:2', chords: 'C' },
  { melody: 'A4:2 C5:2 E5:2 D5:2', chords: 'Am' },
  { melody: 'F5:2 E5:2 D5:2 B4:2', chords: 'F G' },
  { melody: 'C5:6 -:2', chords: 'C' },
];

export const LOOP_STEPS = BARS.length * STEPS_PER_BAR;

// Result: an array per step with events { voice, midi, steps }
export function buildLoop() {
  const steps = Array.from({ length: LOOP_STEPS }, () => []);
  BARS.forEach((bar, b) => {
    const base = b * STEPS_PER_BAR;
    for (const n of parseBar(bar.melody)) {
      if (n.midi !== null) {
        steps[base + n.start].push({ voice: 'melody', midi: n.midi, steps: n.steps });
      }
    }
    const names = bar.chords.split(' ');
    const first = CHORDS[names[0]];
    const second = CHORDS[names[1] ?? names[0]];
    const isLast = b === BARS.length - 1;

    steps[base].push({ voice: 'bass', midi: first.bass[0], steps: isLast ? 6 : 3 });
    if (!isLast) {
      const midi = second === first ? second.bass[1] : second.bass[0];
      steps[base + 4].push({ voice: 'bass', midi, steps: 3 });
      for (const [i, chord] of [
        [1, first],
        [3, first],
        [5, second],
        [7, second],
      ]) {
        steps[base + i].push({ voice: 'arp', midi: chord.arp[i % 4 === 1 ? 0 : 1], steps: 2 });
      }
    }
    steps[base + 2].push({ voice: 'tick', midi: 0, steps: 1 });
    steps[base + 6].push({ voice: 'tick', midi: 0, steps: 1 });
  });
  return steps;
}
