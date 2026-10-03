import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAudio } from './index.js';

// Kleiner Fake: jede create*-Methode liefert einen Knoten, unbekannte Eigenschaften sind AudioParams.
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
    this.state = 'running';
    return Promise.resolve();
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

describe('vor unlock', () => {
  it('erzeugt keinen AudioContext und ignoriert alle Aufrufe still', () => {
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

  it('merkt den Musikwunsch und startet die Melodie nach unlock', () => {
    const audio = make();
    audio.setMusicWanted(true);
    audio.unlock();
    expect(instances).toHaveLength(1);
    expect(audio.getState().musicPlaying).toBe(true);
    vi.advanceTimersByTime(100);
    expect(ctxOf().count('Oscillator')).toBeGreaterThan(0);
  });

  it('unlock ist wiederholbar und erzeugt nur einen Context', () => {
    const audio = make();
    audio.unlock();
    audio.unlock();
    expect(instances).toHaveLength(1);
  });
});

describe('fehlender oder fehlschlagender AudioContext', () => {
  it('ist ohne AudioContext komplett wirkungslos', () => {
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

  it('fängt einen werfenden Konstruktor ab', () => {
    class Broken {
      constructor() {
        throw new Error('nicht erlaubt');
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

describe('Effekte', () => {
  it('erzeugen nach unlock Klang und laufen über den Effektkanal', () => {
    const audio = make();
    audio.unlock();
    const before = ctxOf().count('Oscillator');
    audio.sfx.hoof('canter');
    expect(ctxOf().count('Oscillator')).toBeGreaterThan(before);
  });

  it('jeder Effekt und jede Gangart erzeugt Knoten, unbekannte Gangart nicht', () => {
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

  it('Pause: neue Effekte werden ignoriert, laufende gekappt', () => {
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

  it('stummer Effektkanal erzeugt keine Knoten, Musik bleibt unberührt', () => {
    const audio = make({ sfxMuted: true });
    audio.setMusicWanted(true);
    audio.unlock();
    const nodes = ctxOf().nodes.length;
    audio.sfx.landing();
    expect(ctxOf().nodes.length).toBe(nodes);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('ignoriert Effekte, solange der Context nicht läuft', () => {
    const audio = make();
    audio.unlock();
    ctxOf().state = 'suspended';
    const nodes = ctxOf().nodes.length;
    audio.sfx.hoof('walk');
    expect(ctxOf().nodes.length).toBe(nodes);
  });
});

describe('Lautstärke und Stumm', () => {
  it('setVolumes wirkt sofort und Stumm behält die Lautstärke', () => {
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

  it('Musik-Stumm hält die Melodie an, Aufheben startet sie wieder', () => {
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

describe('Musik soll laufen', () => {
  it('folgt setMusicWanted', () => {
    const audio = make();
    audio.unlock();
    expect(audio.getState().musicPlaying).toBe(false);
    audio.setMusicWanted(true);
    expect(audio.getState().musicPlaying).toBe(true);
    audio.setMusicWanted(false);
    expect(audio.getState().musicPlaying).toBe(false);
  });

  it('plant fortlaufend Noten über die Zeit (Lookahead-Scheduler)', () => {
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

  it('Hintergrund: Musik stoppt, Context wird suspendiert; zurück läuft sie wieder', () => {
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

  it('Hintergrund ohne Musikwunsch: zurück startet keine Musik', () => {
    const audio = make();
    audio.unlock();
    audio.setHidden(true);
    audio.setHidden(false);
    expect(audio.getState().musicPlaying).toBe(false);
  });

  it('Wunsch während Hintergrund wird beim Zurückkehren umgesetzt', () => {
    const audio = make();
    audio.unlock();
    audio.setHidden(true);
    audio.setMusicWanted(true);
    expect(audio.getState().musicPlaying).toBe(false);
    audio.setHidden(false);
    expect(audio.getState().musicPlaying).toBe(true);
  });

  it('unlock im Hintergrund startet keinen Ton', () => {
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

  it('registriert pointerdown, keydown, touchend und entfernt sie nach dem Entsperren', async () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    expect([...target.handlers.keys()].sort()).toEqual(['keydown', 'pointerdown', 'touchend']);
    target.handlers.get('pointerdown')({ type: 'pointerdown', pointerType: 'mouse' });
    await vi.advanceTimersByTimeAsync(0);
    expect(instances).toHaveLength(1);
    expect(target.handlers.size).toBe(0);
  });

  it('Touch-Pointerdown und Escape entsperren nicht, touchend schon', () => {
    const audio = make();
    const target = fakeTarget();
    audio.installUnlock(target);
    target.handlers.get('pointerdown')({ type: 'pointerdown', pointerType: 'touch' });
    target.handlers.get('keydown')({ type: 'keydown', key: 'Escape' });
    expect(instances).toHaveLength(0);
    target.handlers.get('touchend')({ type: 'touchend' });
    expect(instances).toHaveLength(1);
  });

  it('gibt eine Abmeldefunktion zurück und ist ohne Ziel harmlos', () => {
    const audio = make();
    const target = fakeTarget();
    const remove = audio.installUnlock(target);
    remove();
    expect(target.handlers.size).toBe(0);
    expect(() => audio.installUnlock(null)).not.toThrow();
  });
});

describe('dispose', () => {
  it('stoppt Melodie, schließt den Context und macht alles zum No-op', () => {
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
