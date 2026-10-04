// Demo for src/adapters/audio (development only, not shipped).
import { createAudio } from '../src/adapters/audio/index.js';

const audio = createAudio({ musicVolume: 0.5, musicMuted: false, sfxVolume: 0.5, sfxMuted: false });
audio.installUnlock(window);
window.audio = audio;

const $ = (id) => document.getElementById(id);

for (const b of document.querySelectorAll('[data-sfx]')) {
  b.addEventListener('click', () => audio.sfx[b.dataset.sfx]());
}
for (const b of document.querySelectorAll('[data-hoof]')) {
  b.addEventListener('click', () => audio.sfx.hoof(b.dataset.hoof));
}

// Hoof strikes as points in time (seconds) within one cycle
const GAITS = {
  // Four-beat, 1.8 Hz: four evenly spaced strikes per cycle of 4 / 1.8 s
  walk: { period: 4 / 1.8, beats: [0, 1, 2, 3].map((i) => (i * 1) / 1.8) },
  // Two-beat: diagonal pairs, evenly spaced
  trot: { period: 2 / 2.7, beats: [0, 1 / 2.7] },
  // Three-beat with a suspension phase at the end of the cycle
  canter: { period: 0.62, beats: [0, 0.17, 0.34] },
};

let gait = 'halt';
let cycleStart = 0;
let nextIndex = 0;

function resetCycle() {
  cycleStart = performance.now() / 1000;
  nextIndex = 0;
}

setInterval(() => {
  const g = GAITS[gait];
  if (!g) return;
  const now = performance.now() / 1000;
  while (now >= cycleStart + g.beats[nextIndex]) {
    audio.sfx.hoof(gait);
    nextIndex++;
    if (nextIndex >= g.beats.length) {
      cycleStart += g.period;
      nextIndex = 0;
    }
  }
}, 8);

$('gait').addEventListener('change', (e) => {
  gait = e.target.value;
  resetCycle();
});

$('musicWanted').addEventListener('change', (e) => audio.setMusicWanted(e.target.checked));
$('hidden').addEventListener('change', (e) => audio.setHidden(e.target.checked));
$('paused').addEventListener('change', (e) => audio.setPaused(e.target.checked));

const readVolumes = () => ({
  musicVolume: Number($('musicVolume').value),
  musicMuted: $('musicMuted').checked,
  sfxVolume: Number($('sfxVolume').value),
  sfxMuted: $('sfxMuted').checked,
});
for (const id of ['musicVolume', 'musicMuted', 'sfxVolume', 'sfxMuted']) {
  $(id).addEventListener('input', () => audio.setVolumes(readVolumes()));
}

function renderStatus() {
  const s = audio.getState();
  $('status').textContent =
    `unlocked: ${s.unlocked}  running: ${s.running}  music playing: ${s.musicPlaying}\n` +
    `background: ${s.hidden}  paused: ${s.paused}  failed: ${s.failed}`;
}
setInterval(renderStatus, 250);
renderStatus();
