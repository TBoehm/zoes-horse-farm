import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMusicGate } from './music-gate.js';

let calls;
let gate;

beforeEach(() => {
  vi.useFakeTimers();
  calls = [];
  gate = createMusicGate((value) => calls.push(value));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('music gate (music per screen, optionally delayed)', () => {
  it('passes the music wish of a screen on immediately', () => {
    gate.onScreen({ music: true });
    gate.onScreen({ music: false });
    expect(calls).toEqual([true, false]);
  });

  it('starts the music after the delay of the screen, not before', () => {
    gate.onScreen({ music: false });
    calls.length = 0;
    gate.onScreen({ music: true, musicDelayMs: 1500 });
    vi.advanceTimersByTime(1499);
    expect(calls).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(calls).toEqual([true]);
  });

  it('leaving the screen before the delay is over cancels the start', () => {
    gate.onScreen({ music: true, musicDelayMs: 1500 });
    vi.advanceTimersByTime(500);
    gate.onScreen({ music: false });
    vi.advanceTimersByTime(5000);
    expect(calls).toEqual([false]);
  });

  it('a newer screen replaces a pending delayed start', () => {
    gate.onScreen({ music: true, musicDelayMs: 1500 });
    vi.advanceTimersByTime(500);
    gate.onScreen({ music: true });
    expect(calls).toEqual([true]);
    vi.advanceTimersByTime(5000);
    expect(calls).toEqual([true]);
  });

  it('music that already plays is not delayed again (e.g. language change on the screen)', () => {
    gate.onScreen({ music: true });
    calls.length = 0;
    gate.onScreen({ music: true, musicDelayMs: 1500 });
    expect(calls).toEqual([true]);
  });
});
