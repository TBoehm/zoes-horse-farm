import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAudio } from './index.js';

// Small fake: every create* method returns a node, unknown properties are AudioParams.
function makeParam(initial = 0) {
  return {
    value: initial,
    calls: [],
    setValueAtTime(v, t) {
      this.calls.push(['set', v, t]);
      this.value = v;
    },
    setTargetAtTime(v, t, tc) {
      this.calls.push(['target', v, t, tc]);
      this.value = v;
    },
    exponentialRampToValueAtTime(v, t) {
      this.calls.push(['exp', v, t]);
    },
    linearRampToValueAtTime(v, t) {
      this.calls.push(['lin', v, t]);
    },
    cancelScheduledValues() {},
  };
}

function makeNode(kind) {
  const params = {};
  const node = {
    kind,
    connected: [],
    started: false,
    disconnected: false,
    connect(to) {
      this.connected.push(to);
      return to;
    },
    disconnect() {
      this.disconnected = true;
    },
    start() {
      this.started = true;
    },
    stop() {},
  };
  return new Proxy(node, {
    get(target, prop) {
      if (prop in target) return target[prop];
      if (typeof prop === 'symbol' || prop === 'then') return undefined;
      return (params[prop] ??= makeParam());
    },
    set(target, prop, value) {
      target[prop] = value;
      return true;
    },
    has: (target, prop) => prop in target,
  });
}

let instances;

class FakeContext {
  constructor() {
    this.sampleRate = 8000;
    this.currentTime = 0;
    this.state = 'running';
    this.nodes = [];
    this.destination = makeNode('destination');
    this.resumeCalls = 0;
    this.suspendCalls = 0;
    this.closed = false;
    this.refuseResume = false;
    this.onstatechange = null;
    instances.push(this);
    for (const kind of [
      'Gain',
      'Oscillator',
      'BufferSource',
      'BiquadFilter',
      'Convolver',
      'DynamicsCompressor',
    ]) {
      this[`create${kind}`] = () => {
        const n = makeNode(kind);
        this.nodes.push(n);
        return n;
      };
    }
  }

  createBuffer(channels, length, rate) {
    const data = Array.from({ length: channels }, () => new Float32Array(length));
    return { duration: length / rate, getChannelData: (c) => data[c] };
  }

  resume() {
    this.resumeCalls++;
    if (!this.refuseResume) this.setState('running');
    return Promise.resolve();
  }

  // The browser changes the state and then fires `statechange`
  setState(state) {
    this.state = state;
    this.onstatechange?.();
  }

  suspend() {
    this.suspendCalls++;
    this.state = 'suspended';
    return Promise.resolve();
  }

  close() {
    this.closed = true;
    return Promise.resolve();
  }

  count(kind) {
    return this.nodes.filter((n) => n.kind === kind).length;
  }
}

const make = (settings) => createAudio(settings, { AudioContext: FakeContext });
const ctxOf = () => instances[0];

beforeEach(() => {
  instances = [];
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('before unlock', () => {
  it('creates no AudioContext and silently ignores all calls', () => {
    const audio = make();
    audio.sfx.hoof('walk');
    audio.sfx.takeoff();
    audio.sfx.landing();
    audio.sfx.railDown();
    audio.sfx.startSignal();
    audio.sfx.finishSignal();
    audio.setMusicWanted(true);
    audio.setVolumes({ musicVolume: 0.2 });
    audio.setHidden(true);
    audio.setHidden(false);
    audio.setPaused(true);
    audio.setPaused(false);
    expect(instances).toHaveLength(0);
    expect(audio.getState().unlocked).toBe(false);
  });

  it('remembers the music request and starts the melody after unlock', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    expect(instances).toHaveLength(1);
    expect(audio.getState().musicPlaying).toBe(true);
    vi.advanceTimersByTime(100);
    expect(ctxOf().count('Oscillator')).toBeGreaterThan(0);
  });

  it('unlock is repeatable and creates only one context', () => {
    const audio = make();
    audio.unlock();
    audio.unlock();
    expect(instances).toHaveLength(1);
  });
});

describe('missing or failing AudioContext', () => {
  it('has no effect at all without an AudioContext', () => {
    const audio = createAudio({}, { AudioContext: undefined });
    const globalCtx = globalThis.AudioContext;
    delete globalThis.AudioContext;
    try {
      audio.setMusicWanted(true);
      audio.unlock();
      audio.sfx.hoof('trot');
      audio.setVolumes({ sfxVolume: 1 });
      audio.dispose();
      expect(audio.getState().failed).toBe(true);
    } finally {
      if (globalCtx) globalThis.AudioContext = globalCtx;
    }
  });

  it('catches a throwing constructor', () => {
    class Broken {
      constructor() {
        throw new Error('not allowed');
      }
    }
    const audio = createAudio({}, { AudioContext: Broken });
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    audio.setMusicWanted(true);
    expect(() => audio.unlock()).not.toThrow();
    expect(() => audio.sfx.landing()).not.toThrow();
    expect(audio.getState().failed).toBe(true);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('effects', () => {
  it('produce sound after unlock and run through the effects channel', () => {
    const audio = make();
    audio.unlock();
    const before = ctxOf().count('Oscillator');
    audio.sfx.hoof('canter');
    expect(ctxOf().count('Oscillator')).toBeGreaterThan(before);
  });

  it('every effect and every gait creates nodes, an unknown gait does not', () => {
    const audio = make();
    audio.unlock();
    const calls = {
      walk: () => audio.sfx.hoof('walk'),
      trot: () => audio.sfx.hoof('trot'),
      canter: () => audio.sfx.hoof('canter'),
      takeoff: () => audio.sfx.takeoff(),
      landing: () => audio.sfx.landing(),
      railDown: () => audio.sfx.railDown(),
      startSignal: () => audio.sfx.startSignal(),
      finishSignal: () => audio.sfx.finishSignal(),
    };
    for (const [name, call] of Object.entries(calls)) {
      const before = ctxOf().nodes.length;
      call();
      expect(ctxOf().nodes.length, name).toBeGreaterThan(before);
    }
    const before = ctxOf().nodes.length;
    audio.sfx.hoof('halt');
    expect(ctxOf().nodes.length).toBe(before);
  });

  it('pause: new effects are ignored, running ones are cut', () => {
    const audio = make();
    audio.unlock();
    audio.sfx.hoof('trot');
    const session = ctxOf().nodes.find((n) => n.kind === 'Gain' && n.disconnected === false);
    expect(session).toBeDefined();
    audio.setPaused(true);
    const nodes = ctxOf().nodes.length;
    audio.sfx.takeoff();
    expect(ctxOf().nodes.length).toBe(nodes);
    vi.advanceTimersByTime(300);
    expect(ctxOf().nodes.some((n) => n.kind === 'Gain' && n.disconnected)).toBe(true);
    audio.setPaused(false);
    audio.sfx.hoof('walk');
    expect(ctxOf().nodes.length).toBeGreaterThan(nodes);
  });

  it('muted effects channel creates no nodes, music is untouched', () => {
    const audio = make({ sfxMuted: true });
    audio.setMusicWanted(true);
    audio.unlock();
    const nodes = ctxOf().nodes.length;
    audio.sfx.landing();
    expect(ctxOf().nodes.length).toBe(nodes);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('counts the effects that were really played per name, in getState', () => {
    const audio = make();
    expect(audio.getState().sfxCounts).toEqual({
      hoof: 0,
      takeoff: 0,
      landing: 0,
      railDown: 0,
      startSignal: 0,
      finishSignal: 0,
    });
    audio.unlock();
    audio.sfx.startSignal();
    audio.sfx.hoof('walk');
    audio.sfx.hoof('trot');
    audio.sfx.finishSignal();
    expect(audio.getState().sfxCounts).toMatchObject({ hoof: 2, startSignal: 1, finishSignal: 1 });
    // a copy: changing it does not change the audio service
    audio.getState().sfxCounts.hoof = 99;
    expect(audio.getState().sfxCounts.hoof).toBe(2);
  });

  it('does not count effects that are dropped (paused, hidden, muted, not running, locked)', () => {
    const audio = make();
    audio.sfx.landing(); // before unlock
    audio.unlock();
    audio.setPaused(true);
    audio.sfx.landing();
    audio.setPaused(false);
    audio.setHidden(true);
    audio.sfx.landing();
    audio.setHidden(false);
    audio.setVolumes({ sfxMuted: true });
    audio.sfx.landing();
    audio.setVolumes({ sfxMuted: false });
    ctxOf().state = 'suspended';
    audio.sfx.landing();
    expect(audio.getState().sfxCounts.landing).toBe(0);
    ctxOf().state = 'running';
    audio.sfx.landing();
    expect(audio.getState().sfxCounts.landing).toBe(1);
  });

  it('ignores effects while the context is not running', () => {
    const audio = make();
    audio.unlock();
    ctxOf().state = 'suspended';
    const nodes = ctxOf().nodes.length;
    audio.sfx.hoof('walk');
    expect(ctxOf().nodes.length).toBe(nodes);
  });
});

describe('volume and mute', () => {
  it('setVolumes takes effect immediately and muting keeps the volume', () => {
    const audio = make({ musicVolume: 0.5, sfxVolume: 0.5 });
    audio.unlock();
    const gains = ctxOf().nodes.filter((n) => n.kind === 'Gain');
    const music = gains[1];
    const sfx = gains[2];
    expect(music.gain.value).toBeCloseTo(0.25);
    audio.setVolumes({ musicVolume: 0.8 });
    expect(music.gain.value).toBeCloseTo(0.64);
    audio.setVolumes({ musicMuted: true });
    expect(music.gain.value).toBe(0);
    expect(sfx.gain.value).toBeCloseTo(0.25);
    audio.setVolumes({ musicMuted: false });
    expect(music.gain.value).toBeCloseTo(0.64);
  });

  it('muting the music stops the melody, unmuting starts it again', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    expect(audio.getState().musicPlaying).toBe(true);
    audio.setVolumes({ musicMuted: true });
    expect(audio.getState().musicPlaying).toBe(false);
    audio.setVolumes({ musicMuted: false });
    expect(audio.getState().musicPlaying).toBe(true);
  });
});

describe('music should run', () => {
  it('follows setMusicWanted', () => {
    const audio = make();
    audio.unlock();
    expect(audio.getState().musicPlaying).toBe(false);
    audio.setMusicWanted(true);
    expect(audio.getState().musicPlaying).toBe(true);
    audio.setMusicWanted(false);
    expect(audio.getState().musicPlaying).toBe(false);
  });

  it('schedules notes continuously over time (lookahead scheduler)', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    const ctx = ctxOf();
    let last = ctx.count('Oscillator');
    let grew = 0;
    for (let i = 0; i < 40; i++) {
      ctx.currentTime += 0.1;
      vi.advanceTimersByTime(100);
      const now = ctx.count('Oscillator');
      if (now > last) grew++;
      last = now;
    }
    expect(grew).toBeGreaterThan(10);
  });

  it('background: music stops, context is suspended; on return it runs again', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    audio.setHidden(true);
    expect(audio.getState().musicPlaying).toBe(false);
    vi.advanceTimersByTime(200);
    expect(ctxOf().suspendCalls).toBe(1);
    audio.sfx.takeoff();
    audio.setHidden(false);
    expect(ctxOf().resumeCalls).toBe(1);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('background without a music request: returning starts no music', () => {
    const audio = make();
    audio.unlock();
    audio.setHidden(true);
    audio.setHidden(false);
    expect(audio.getState().musicPlaying).toBe(false);
  });

  it('a request made while in the background is applied on return', () => {
    const audio = make();
    audio.unlock();
    audio.setHidden(true);
    audio.setMusicWanted(true);
    expect(audio.getState().musicPlaying).toBe(false);
    audio.setHidden(false);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('unlock in the background starts no sound', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.setHidden(true);
    audio.unlock();
    expect(audio.getState().musicPlaying).toBe(false);
    vi.advanceTimersByTime(200);
    expect(ctxOf().suspendCalls).toBe(1);
  });
});

describe('installUnlock', () => {
  function fakeTarget() {
    const handlers = new Map();
    return {
      handlers,
      addEventListener: (type, fn) => handlers.set(type, fn),
      removeEventListener: (type, fn) => {
        if (handlers.get(type) === fn) handlers.delete(type);
      },
    };
  }

  it('registers the gesture events and removes them after unlocking', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    expect([...target.handlers.keys()].sort()).toEqual([
      'click',
      'keydown',
      'pointerdown',
      'pointerup',
      'touchend',
    ]);
    target.handlers.get('pointerdown')({ type: 'pointerdown', pointerType: 'mouse' });
    await vi.advanceTimersByTimeAsync(0);
    expect(instances).toHaveLength(1);
    expect(target.handlers.size).toBe(0);
  });

  it('touch pointerdown and Escape do not unlock, touchend does', () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    target.handlers.get('pointerdown')({ type: 'pointerdown', pointerType: 'touch' });
    target.handlers.get('keydown')({ type: 'keydown', key: 'Escape' });
    expect(instances).toHaveLength(0);
    target.handlers.get('touchend')({ type: 'touchend' });
    expect(instances).toHaveLength(1);
  });

  it('a pen or stylus unlocks with pointerup or click (its pointerdown is no user activation)', () => {
    for (const trigger of [
      { type: 'pointerup', pointerType: 'pen' },
      { type: 'click' },
      { type: 'pointerup', pointerType: 'touch' },
    ]) {
      instances = [];
      const audio = make();
      const target = fakeTarget();
      audio.installUnlock(target);
      target.handlers.get('pointerdown')({ type: 'pointerdown', pointerType: 'pen' });
      expect(instances).toHaveLength(0);
      target.handlers.get(trigger.type)(trigger);
      expect(instances, trigger.type).toHaveLength(1);
    }
  });

  it('a mouse pointerup alone does not unlock (pointerdown does)', () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    target.handlers.get('pointerup')({ type: 'pointerup', pointerType: 'mouse' });
    expect(instances).toHaveLength(0);
  });

  it('returns an unsubscribe function and is harmless without a target', () => {
    const audio = make();
    const target = fakeTarget();
    const remove = audio.installUnlock(target);
    remove();
    expect(target.handlers.size).toBe(0);
    expect(() => audio.installUnlock(null)).not.toThrow();
  });
});

describe('re-arming the unlock (iOS interruptions, refused resume)', () => {
  function fakeTarget() {
    const handlers = new Map();
    return {
      handlers,
      addEventListener: (type, fn) => handlers.set(type, fn),
      removeEventListener: (type, fn) => {
        if (handlers.get(type) === fn) handlers.delete(type);
      },
    };
  }
  const press = (target) => target.handlers.get('pointerdown')({ type: 'pointerdown' });

  it('an interruption after unlocking installs the gesture listeners again; the next gesture resumes', async () => {
    const audio = make();
    audio.setMusicWanted(true);
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(target.handlers.size).toBe(0);
    // e.g. a phone call or the screen lock on iOS
    ctxOf().setState('interrupted');
    expect(target.handlers.size).toBeGreaterThan(0);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(ctxOf().resumeCalls).toBe(1);
    expect(ctxOf().state).toBe('running');
    expect(target.handlers.size).toBe(0);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('a suspended context re-arms as well', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(target.handlers.size).toBe(0);
    ctxOf().setState('suspended');
    expect(target.handlers.size).toBeGreaterThan(0);
  });

  it('keeps the listeners while a resume is refused and removes them once it works', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    ctxOf().setState('interrupted');
    ctxOf().refuseResume = true;
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(ctxOf().resumeCalls).toBe(1);
    expect(target.handlers.size).toBeGreaterThan(0);
    ctxOf().refuseResume = false;
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(ctxOf().resumeCalls).toBe(2);
    expect(target.handlers.size).toBe(0);
  });

  it('does not re-arm while the page is in the background (it suspends the context itself)', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    audio.setHidden(true);
    await vi.advanceTimersByTimeAsync(200);
    expect(ctxOf().state).toBe('suspended');
    expect(target.handlers.size).toBe(0);
  });

  it('returning to the page with a refused resume waits for the next gesture', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    audio.setHidden(true);
    await vi.advanceTimersByTimeAsync(200);
    ctxOf().refuseResume = true;
    audio.setHidden(false);
    expect(ctxOf().state).toBe('suspended');
    expect(target.handlers.size).toBeGreaterThan(0);
    ctxOf().refuseResume = false;
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    expect(ctxOf().state).toBe('running');
    expect(target.handlers.size).toBe(0);
  });

  it('the returned remove function ends the re-arming for good', async () => {
    const audio = make();
    const target = fakeTarget();
    const remove = audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    remove();
    ctxOf().setState('interrupted');
    expect(target.handlers.size).toBe(0);
  });

  it('after dispose a state change installs nothing', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    press(target);
    await vi.advanceTimersByTimeAsync(0);
    const ctx = ctxOf();
    audio.dispose();
    ctx.setState('interrupted');
    expect(target.handlers.size).toBe(0);
  });
});

describe('dispose', () => {
  it('stops the melody, closes the context and turns everything into a no-op', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    audio.dispose();
    expect(ctxOf().closed).toBe(true);
    expect(() => {
      audio.sfx.hoof('walk');
      audio.setMusicWanted(true);
      audio.unlock();
      audio.setVolumes({ sfxVolume: 1 });
    }).not.toThrow();
    expect(instances).toHaveLength(1);
    vi.advanceTimersByTime(1000);
  });
});
