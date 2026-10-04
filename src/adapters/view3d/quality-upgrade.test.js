import { describe, expect, it, vi } from 'vitest';
import { createUpgradeGovernor, nextUpgradeLevel, UPGRADE_DEFAULTS } from './quality-upgrade.js';
import { createFrameWindow } from './quality.js';

/** Frames of a constant rate for `seconds`; returns the first result that is not null. */
function run(gov, seconds, fps, { measuring = true, busy = false } = {}) {
  const dt = 1 / fps;
  const n = Math.round(seconds * fps);
  for (let i = 0; i < n; i += 1) {
    const result = gov.frame(dt, measuring, busy);
    if (result) return result;
  }
  return null;
}

/** Governor that is allowed to go to medium. */
const setup = (options) => {
  const chooseTarget = vi.fn(() => 'medium');
  return { chooseTarget, gov: createUpgradeGovernor({ chooseTarget, options }) };
};

describe('createFrameWindow slow frames', () => {
  it('counts the share of frames slower than the limit and forgets old ones', () => {
    const window = createFrameWindow(1, 0.025);
    for (let i = 0; i < 9; i += 1) window.push(0.01); // 90 ms
    window.push(0.05); // slow
    expect(window.slowShare()).toBeCloseTo(0.1);
    for (let i = 0; i < 100; i += 1) window.push(0.01); // the slow frame leaves the window
    expect(window.slowShare()).toBe(0);
    window.clear();
    expect(window.slowShare()).toBe(0);
  });

  it('counts none without a limit', () => {
    const window = createFrameWindow(1);
    window.push(5);
    expect(window.slowShare()).toBe(0);
  });
});

describe('createUpgradeGovernor (rule 4: climbing)', () => {
  it('steps up after 3 s warm-up and 10 s at 60 fps, and tells level and frame rate', () => {
    const { gov } = setup({ cooldownS: 0 });
    expect(run(gov, 12.9, 60)).toBeNull();
    expect(run(gov, 0.3, 60)).toEqual({ level: 'medium', fps: expect.closeTo(60, 0) });
  });

  it('uses the documented limits', () => {
    expect(UPGRADE_DEFAULTS).toMatchObject({
      windowS: 10,
      minFps: 57,
      graceS: 3,
      cooldownS: 20,
    });
    expect(UPGRADE_DEFAULTS.slowFrameS).toBe(0.025);
    expect(UPGRADE_DEFAULTS.maxSlowShare).toBe(0.02);
  });

  describe('frame rate', () => {
    it('does not step up below 57 fps on average', () => {
      const { gov } = setup();
      expect(run(gov, 60, 56)).toBeNull();
    });

    it('steps up at exactly 57 fps', () => {
      const { gov } = setup();
      expect(run(gov, 20, 57)).toMatchObject({ level: 'medium' });
    });

    it('averages over the 10 s window: a weak stretch holds the step back until it has slid out', () => {
      const { gov } = setup({ cooldownS: 0 });
      run(gov, 3, 60); // warm-up
      expect(run(gov, 5, 45)).toBeNull(); // 22 ms frames: no hitches, but not enough reserve
      expect(run(gov, 4.9, 60)).toBeNull(); // 10 s window at 52 fps on average
      expect(run(gov, 10, 60)).toMatchObject({ level: 'medium' }); // the weak stretch has slid out
    });
  });

  describe('slow frames', () => {
    /** Every `every`-th frame takes `slowS`, the others `baseS`. */
    function runWithHitches(gov, seconds, every, slowS, baseS = 1 / 100) {
      let t = 0;
      let i = 0;
      while (t < seconds) {
        i += 1;
        const dt = i % every === 0 ? slowS : baseS;
        t += dt;
        const result = gov.frame(dt, true, false);
        if (result) return result;
      }
      return null;
    }

    it('does not step up when more than 2 % of the frames are slower than 25 ms', () => {
      const { gov } = setup();
      // every 40th frame (2.5 %) takes 30 ms, the rest 10 ms: the average is above 57 fps
      expect(runWithHitches(gov, 60, 40, 0.03)).toBeNull();
    });

    it('steps up with a few slow frames (at most 2 %)', () => {
      const { gov } = setup();
      // every 100th frame (1 %) takes 30 ms
      expect(runWithHitches(gov, 60, 100, 0.03)).toMatchObject({ level: 'medium' });
    });

    it('25 ms is the limit: a frame of 24.9 ms is no hitch, one of 25.1 ms is', () => {
      // every second frame is long, the others 5 ms: the average is above 57 fps either way
      const fine = setup();
      expect(runWithHitches(fine.gov, 60, 2, 0.0249, 0.005)).toMatchObject({ level: 'medium' });
      const hitchy = setup();
      expect(runWithHitches(hitchy.gov, 60, 2, 0.0251, 0.005)).toBeNull();
    });
  });

  describe('warm-up', () => {
    it('does not count the first 3 s after the start', () => {
      const { gov } = setup({ cooldownS: 0 });
      // 3 s of warm-up at 10 fps would ruin the window otherwise; here they are not measured
      run(gov, 3, 10);
      expect(run(gov, 9.9, 60)).toBeNull();
      expect(run(gov, 0.2, 60)).toMatchObject({ level: 'medium' });
    });

    it('an interruption (pause, menu, hidden tab) clears the window and warms up again', () => {
      const { gov } = setup();
      run(gov, 12, 60);
      gov.interrupt();
      expect(run(gov, 12.9, 60)).toBeNull();
      expect(run(gov, 0.3, 60)).toMatchObject({ level: 'medium' });
    });

    it('frames while not measuring never count', () => {
      const { gov } = setup();
      expect(run(gov, 60, 60, { measuring: false })).toBeNull();
      expect(run(gov, 12.9, 60)).toBeNull();
      expect(run(gov, 0.3, 60)).toMatchObject({ level: 'medium' });
    });

    it('a frame longer than 2 s is an interruption', () => {
      const { gov } = setup();
      run(gov, 12, 60);
      expect(gov.frame(2.5, true, false)).toBeNull();
      expect(run(gov, 12.9, 60)).toBeNull();
      expect(run(gov, 0.3, 60)).toMatchObject({ level: 'medium' });
    });
  });

  describe('cooldown', () => {
    it('waits 20 s after a step before the next one', () => {
      const gov = createUpgradeGovernor({ chooseTarget: () => 'high' });
      let t = 0;
      const times = [];
      for (let i = 0; i < 60 * 80; i += 1) {
        t += 1 / 60;
        if (gov.frame(1 / 60, true, false)) times.push(t);
      }
      // first step after warm-up + window, then every 20 s (the window is refilled by then)
      expect(times[0]).toBeGreaterThan(12.9);
      expect(times[0]).toBeLessThan(13.5);
      expect(times[1] - times[0]).toBeGreaterThanOrEqual(20 - 1e-6);
      expect(times[1] - times[0]).toBeLessThan(21);
    });

    it('also waits 20 s after a change from outside (the downgrade, a context loss, a manual pick)', () => {
      const { gov } = setup();
      run(gov, 14, 60); // the window would be full
      gov.noteChange();
      // 3 s warm-up + 10 s window are fine again after 13 s, but the 20 s are not over yet
      expect(run(gov, 19.5, 60)).toBeNull();
      expect(run(gov, 1, 60)).toMatchObject({ level: 'medium' });
    });

    it('does not run down while nothing is measured (pause, menus)', () => {
      const { gov } = setup();
      gov.noteChange();
      run(gov, 100, 60, { measuring: false });
      expect(run(gov, 19, 60)).toBeNull();
      expect(run(gov, 2, 60)).toMatchObject({ level: 'medium' });
    });
  });

  describe('jump in progress', () => {
    it('never starts a step while busy; it follows right after the jump when the window is fine', () => {
      const { gov } = setup({ cooldownS: 0 });
      expect(run(gov, 30, 60, { busy: true })).toBeNull();
      // the jump is over: the window is still full of good frames
      expect(gov.frame(1 / 60, true, false)).toMatchObject({ level: 'medium' });
    });

    it('a jump does not reset the measurement', () => {
      const { gov } = setup({ cooldownS: 0 });
      run(gov, 12.8, 60); // 9.8 s of the window
      expect(run(gov, 0.5, 60, { busy: true })).toBeNull(); // the window fills up in the jump
      expect(run(gov, 0.1, 60)).toMatchObject({ level: 'medium' });
    });
  });

  describe('target', () => {
    it('asks for the target only when everything else says go', () => {
      const { gov, chooseTarget } = setup();
      run(gov, 12, 60);
      expect(chooseTarget).not.toHaveBeenCalled();
      run(gov, 10, 60, { busy: true });
      expect(chooseTarget).not.toHaveBeenCalled();
      expect(run(gov, 1, 60)).toMatchObject({ level: 'medium' });
      expect(chooseTarget).toHaveBeenCalledTimes(1);
    });

    it('no allowed target: no step, and the window starts over (no check on every frame)', () => {
      const chooseTarget = vi.fn(() => null);
      const gov = createUpgradeGovernor({ chooseTarget, options: { cooldownS: 0 } });
      expect(run(gov, 60, 60)).toBeNull();
      // 3 s warm-up, then a check after each full window of 10 s: 13 s, 23 s, 33 s, ...
      expect(chooseTarget.mock.calls.length).toBeGreaterThanOrEqual(3);
      expect(chooseTarget.mock.calls.length).toBeLessThanOrEqual(6);
    });

    it('a step needs no extra change notice: the governor starts its own cooldown', () => {
      const { gov } = setup();
      expect(run(gov, 25, 60)).toMatchObject({ level: 'medium' });
      expect(run(gov, 19, 60)).toBeNull();
    });
  });
});

describe('nextUpgradeLevel', () => {
  const free = {};

  it('is the next level up', () => {
    expect(nextUpgradeLevel({ level: 'low', ...free })).toBe('medium');
    expect(nextUpgradeLevel({ level: 'medium', ...free })).toBe('high');
  });

  it('never goes above high', () => {
    expect(nextUpgradeLevel({ level: 'high', ...free })).toBeNull();
  });

  it('never goes to a blocked level, and does not jump over it', () => {
    expect(nextUpgradeLevel({ level: 'low', blocked: ['medium'] })).toBeNull();
    expect(nextUpgradeLevel({ level: 'low', blocked: ['high'] })).toBe('medium');
    expect(nextUpgradeLevel({ level: 'medium', blocked: ['high'] })).toBeNull();
  });

  it('never goes to a level the automatic left because of a low frame rate in this session', () => {
    expect(nextUpgradeLevel({ level: 'low', left: new Set(['medium']) })).toBeNull();
    expect(nextUpgradeLevel({ level: 'low', left: ['high'] })).toBe('medium');
  });

  it('never goes to a level that does not fit the memory budget of the device', () => {
    const fits = vi.fn((level) => level !== 'high');
    expect(nextUpgradeLevel({ level: 'low', fits })).toBe('medium');
    expect(nextUpgradeLevel({ level: 'medium', fits })).toBeNull();
    expect(fits).toHaveBeenCalledWith('high');
  });

  it('does not ask the budget for a level that is excluded anyway', () => {
    const fits = vi.fn(() => true);
    expect(nextUpgradeLevel({ level: 'low', blocked: ['medium'], fits })).toBeNull();
    expect(fits).not.toHaveBeenCalled();
  });

  it('an unknown level has no next one', () => {
    expect(nextUpgradeLevel({ level: 'ultra' })).toBeNull();
  });
});
