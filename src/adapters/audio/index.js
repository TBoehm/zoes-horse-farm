// Audio module: pure WebAudio synthesis, no audio files. See createAudio below.

import { createImpulseBuffer, createNoiseBuffer } from './dsp.js';
import { channelGain, mulberry32, normalizeSettings, planSteps, shouldMusicRun } from './logic.js';
import { LOOP_STEPS, STEP_SECONDS, buildLoop } from './melody.js';
import { playMusicEvent } from './music.js';
import * as sfxVoices from './sfx.js';

export { channelGain, normalizeSettings, shouldMusicRun, volumeToGain } from './logic.js';

const MASTER_LEVEL = 2;
const LOOKAHEAD = 0.15;
const TICK_MS = 30;
const SMOOTH = 0.02; // Time constant for volume changes (no clicks)
const MUSIC_LEVEL = 0.8; // Mix the music slightly quieter than the effects
const MUSIC_FADE_IN = 0.25;
const MUSIC_FADE_OUT = 0.1;
const REVERB_SEND = 0.3;
const SUSPEND_DELAY_MS = 120;

const noop = () => {};

function holdParam(param, now) {
  if (typeof param.cancelAndHoldAtTime === 'function') param.cancelAndHoldAtTime(now);
  else param.cancelScheduledValues(now);
}

/**
 * @param {{ musicVolume?: number, musicMuted?: boolean, sfxVolume?: number, sfxMuted?: boolean }} settings
 * @param {{ AudioContext?: Function }} [deps] tests only: custom AudioContext constructor
 */
export function createAudio(settings = {}, deps = {}) {
  let s = normalizeSettings(settings);
  let ctx = null;
  let failed = false;
  let disposed = false;
  let wanted = false;
  let hidden = false;
  let paused = false;
  let resumePromise = null;
  let suspendTimer = null;
  let graph = null; // { master, musicGain, sfxGain, reverbIn, noise }
  let run = null; // running melody: { gain, timer, nextTime, step }
  let sfxSession = null; // Gain through which all effects run; pause/background cuts it
  const voice = { ctx: null, noise: null, rng: mulberry32(2024), state: {} };
  const loop = buildLoop();

  const guard = (fn) => {
    try {
      return fn();
    } catch {
      return undefined;
    }
  };

  function build() {
    const Ctor = deps.AudioContext ?? globalThis.AudioContext ?? globalThis.webkitAudioContext;
    if (typeof Ctor !== 'function') {
      failed = true;
      return;
    }
    try {
      ctx = new Ctor({ latencyHint: 'interactive' });
      const master = ctx.createGain();
      master.gain.value = hidden ? 0 : MASTER_LEVEL;
      const comp = ctx.createDynamicsCompressor();
      comp.threshold.value = -10;
      comp.knee.value = 8;
      comp.ratio.value = 10;
      comp.attack.value = 0.003;
      comp.release.value = 0.25;
      master.connect(comp);
      comp.connect(ctx.destination);

      const musicGain = ctx.createGain();
      musicGain.gain.value = channelGain(s.musicVolume, s.musicMuted);
      musicGain.connect(master);
      const sfxGain = ctx.createGain();
      sfxGain.gain.value = channelGain(s.sfxVolume, s.sfxMuted);
      sfxGain.connect(master);

      // Reverb for the music: send path without bass into a convolution reverb
      const reverbIn = ctx.createBiquadFilter();
      reverbIn.type = 'highpass';
      reverbIn.frequency.value = 250;
      const send = ctx.createGain();
      send.gain.value = REVERB_SEND;
      const convolver = ctx.createConvolver();
      convolver.buffer = createImpulseBuffer(ctx, { seconds: 1.8, decay: 3 });
      reverbIn.connect(send);
      send.connect(convolver);
      convolver.connect(musicGain);

      const noise = createNoiseBuffer(ctx);
      graph = { master, musicGain, sfxGain, reverbIn, noise };
      voice.ctx = ctx;
      voice.noise = noise;
    } catch {
      teardown();
      failed = true;
    }
  }

  function teardown() {
    clearTimeout(suspendTimer);
    if (run) {
      clearInterval(run.timer);
      run = null;
    }
    sfxSession = null;
    graph = null;
    voice.ctx = null;
    voice.noise = null;
    const old = ctx;
    ctx = null;
    if (old && typeof old.close === 'function') {
      try {
        const p = old.close();
        if (p && typeof p.catch === 'function') p.catch(noop);
      } catch {
        // already closed
      }
    }
  }

  function resumeContext() {
    if (!ctx || ctx.state === 'running') return;
    const p = ctx.resume();
    resumePromise = p && typeof p.then === 'function' ? p.catch(noop) : null;
  }

  function setChannelGains() {
    if (!ctx || !graph) return;
    const now = ctx.currentTime;
    graph.musicGain.gain.setTargetAtTime(channelGain(s.musicVolume, s.musicMuted), now, SMOOTH);
    graph.sfxGain.gain.setTargetAtTime(channelGain(s.sfxVolume, s.sfxMuted), now, SMOOTH);
  }

  // ---- Music ----

  function tick() {
    if (!run || !ctx) return;
    const plan = planSteps({
      now: ctx.currentTime,
      nextTime: run.nextTime,
      step: run.step,
      lookahead: LOOKAHEAD,
      stepDuration: STEP_SECONDS,
      loopSteps: LOOP_STEPS,
    });
    for (const e of plan.events) {
      for (const ev of loop[e.step]) playMusicEvent(voice, run.gain, ev, e.time);
    }
    run.nextTime = plan.nextTime;
    run.step = plan.step;
  }

  function startRun() {
    const now = ctx.currentTime;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0, now);
    gain.gain.setTargetAtTime(MUSIC_LEVEL, now + 0.02, MUSIC_FADE_IN);
    gain.connect(graph.musicGain);
    gain.connect(graph.reverbIn);
    run = { gain, nextTime: now + 0.08, step: 0, timer: setInterval(tick, TICK_MS) };
    tick();
  }

  function stopRun(fast) {
    const old = run;
    run = null;
    clearInterval(old.timer);
    const now = ctx.currentTime;
    holdParam(old.gain.gain, now);
    old.gain.gain.setTargetAtTime(0, now, fast ? 0.02 : MUSIC_FADE_OUT);
    setTimeout(() => guard(() => old.gain.disconnect()), fast ? 250 : 900);
  }

  function syncMusic() {
    if (!ctx || !graph || disposed) return;
    const want = shouldMusicRun({ wanted, hidden, muted: s.musicMuted });
    if (want && !run) startRun();
    else if (!want && run) stopRun(hidden);
  }

  // ---- Effects ----

  function dropSfxSession() {
    const old = sfxSession;
    sfxSession = null;
    if (!old || !ctx) return;
    old.gain.setTargetAtTime(0, ctx.currentTime, 0.008);
    setTimeout(() => guard(() => old.disconnect()), 150);
  }

  function canPlaySfx() {
    return (
      Boolean(ctx && graph) &&
      !disposed &&
      !paused &&
      !hidden &&
      ctx.state === 'running' &&
      !s.sfxMuted &&
      s.sfxVolume > 0
    );
  }

  function playSfx(fn, ...args) {
    if (!canPlaySfx()) return;
    guard(() => {
      if (!sfxSession) {
        sfxSession = ctx.createGain();
        sfxSession.connect(graph.sfxGain);
      }
      fn(voice, sfxSession, ctx.currentTime + 0.004, ...args);
    });
  }

  // ---- Public API ----

  function unlock() {
    if (disposed) return;
    guard(() => {
      if (!ctx && !failed) build();
      if (!ctx) return;
      if (hidden) applyHidden();
      else resumeContext();
      syncMusic();
    });
  }

  function installUnlock(target = globalThis) {
    if (!target || typeof target.addEventListener !== 'function') return noop;
    const events = ['pointerdown', 'keydown', 'touchend'];
    const remove = () => {
      for (const e of events) target.removeEventListener(e, handler, true);
    };
    function handler(e) {
      // Touch pointerdown and Escape do not count as user activation in the browser
      if (e.type === 'pointerdown' && e.pointerType && e.pointerType !== 'mouse') return;
      if (e.type === 'keydown' && e.key === 'Escape') return;
      unlock();
      Promise.resolve(resumePromise).then(() => {
        if (disposed || failed || hidden || (ctx && ctx.state === 'running')) remove();
      });
    }
    for (const e of events) target.addEventListener(e, handler, { capture: true, passive: true });
    return remove;
  }

  function setVolumes(next = {}) {
    if (disposed) return;
    s = normalizeSettings({ ...s, ...next });
    guard(() => {
      setChannelGains();
      syncMusic();
    });
  }

  function setMusicWanted(value) {
    wanted = Boolean(value);
    guard(syncMusic);
  }

  function applyHidden() {
    if (!ctx || !graph) return;
    clearTimeout(suspendTimer);
    graph.master.gain.setTargetAtTime(hidden ? 0 : MASTER_LEVEL, ctx.currentTime, 0.015);
    if (hidden) {
      suspendTimer = setTimeout(() => {
        if (hidden && ctx && ctx.state === 'running') {
          guard(() => ctx.suspend()?.catch?.(noop));
        }
      }, SUSPEND_DELAY_MS);
    } else {
      resumeContext();
    }
  }

  function setHidden(value) {
    if (disposed) return;
    const next = Boolean(value);
    if (next === hidden) return;
    hidden = next;
    guard(() => {
      if (hidden) dropSfxSession();
      applyHidden();
      syncMusic();
    });
  }

  function setPaused(value) {
    if (disposed) return;
    paused = Boolean(value);
    if (paused) guard(dropSfxSession);
  }

  function dispose() {
    if (disposed) return;
    guard(() => {
      if (run) stopRun(true);
    });
    disposed = true;
    guard(teardown);
  }

  function getState() {
    return {
      unlocked: Boolean(ctx),
      running: Boolean(ctx && ctx.state === 'running'),
      failed,
      hidden,
      paused,
      musicWanted: wanted,
      musicPlaying: Boolean(run),
    };
  }

  return {
    unlock,
    installUnlock,
    setVolumes,
    setMusicWanted,
    setHidden,
    setPaused,
    dispose,
    getState,
    sfx: {
      hoof: (gait) => playSfx(sfxVoices.hoof, gait),
      takeoff: () => playSfx(sfxVoices.takeoff),
      landing: () => playSfx(sfxVoices.landing),
      railDown: () => playSfx(sfxVoices.railDown),
      startSignal: () => playSfx(sfxVoices.startSignal),
      finishSignal: () => playSfx(sfxVoices.finishSignal),
    },
  };
}
