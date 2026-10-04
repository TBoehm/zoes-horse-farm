import { describe, expect, it } from 'vitest';
import { createFpsMeter, formatFpsText } from './fps-display.js';

/** Feeds `seconds` of frames with a constant frame time; returns every reported value. */
function feed(meter, seconds, frameS) {
  const reported = [];
  for (let t = 0; t < seconds - 1e-9; t += frameS) {
    const value = meter.frame(frameS);
    if (value !== null) reported.push(value);
  }
  return reported;
}

describe('createFpsMeter', () => {
  it('reports nothing before the interval is over', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    expect(feed(meter, 0.4, 1 / 60)).toEqual([]);
  });

  it('reports the average about twice per second', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    const reported = feed(meter, 2, 1 / 50);
    expect(reported).toHaveLength(4);
    for (const fps of reported) expect(fps).toBe(50);
  });

  it('rounds to whole fps', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    const reported = feed(meter, 1, 1 / 58.4);
    expect(reported.every(Number.isInteger)).toBe(true);
    expect(reported[0]).toBe(58);
  });

  it('follows a change of the frame rate in the next interval', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    expect(feed(meter, 0.5, 1 / 60)).toEqual([60]);
    expect(feed(meter, 0.5, 1 / 20)).toEqual([20]);
  });

  it('ignores invalid frame times', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    for (const bad of [NaN, -1, 0, Infinity, undefined]) expect(meter.frame(bad)).toBeNull();
    expect(feed(meter, 0.5, 1 / 40)).toEqual([40]);
  });

  it('treats a frame longer than maxFrameS as an interruption and starts over', () => {
    const meter = createFpsMeter({ intervalS: 0.5, maxFrameS: 1 });
    feed(meter, 0.3, 1 / 30);
    expect(meter.frame(5)).toBeNull(); // suspended tab: not a slow game
    expect(feed(meter, 0.5, 1 / 60)).toEqual([60]);
  });

  it('counts a frame of exactly maxFrameS as a slow frame', () => {
    const meter = createFpsMeter({ intervalS: 0.5, maxFrameS: 1 });
    expect(meter.frame(1)).toBe(1);
  });

  it('starts a fresh interval after reset', () => {
    const meter = createFpsMeter({ intervalS: 0.5 });
    feed(meter, 0.3, 1 / 30);
    meter.reset();
    expect(feed(meter, 0.5, 1 / 60)).toEqual([60]);
  });
});

describe('formatFpsText', () => {
  const TEXTS = {
    'ride.fps': '{fps} fps',
    'ride.fpsLevel': '{fps} fps | {level}',
    'ride.fpsLevelAuto': '{fps} fps | {level} (auto)',
    'ride.fpsNone': '?',
    'graphics.low': 'Low',
    'graphics.medium': 'Medium',
    'graphics.high': 'High',
  };
  // Minimal translate function with {param} substitution, like the real one
  const t = (key, params = {}) =>
    TEXTS[key].replace(/\{(\w+)\}/g, (_, name) => String(params[name]));

  it('shows fps and the level of a manual choice', () => {
    expect(formatFpsText({ fps: 58, level: 'medium', auto: false }, t)).toBe('58 fps | Medium');
  });

  it('marks an automatically chosen level', () => {
    expect(formatFpsText({ fps: 41, level: 'low', auto: true }, t)).toBe('41 fps | Low (auto)');
  });

  it('shows the placeholder text until the first measurement is there', () => {
    expect(formatFpsText({ fps: null, level: 'high', auto: false }, t)).toBe('? fps | High');
    expect(formatFpsText({ fps: undefined, level: null, auto: false }, t)).toBe('? fps');
  });

  it('leaves out the level when it is not known', () => {
    expect(formatFpsText({ fps: 60, level: null, auto: true }, t)).toBe('60 fps');
  });

  it('builds no text of its own: every visible character comes from a translation', () => {
    const marked = (key, params = {}) => `[${key}${JSON.stringify(params)}]`;
    expect(formatFpsText({ fps: 58, level: 'low', auto: true }, marked)).toBe(
      '[ride.fpsLevelAuto{"fps":58,"level":"[graphics.low{}]"}]',
    );
  });
});
