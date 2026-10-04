// Upgrade governor (concept rule 4): while "Automatic" is on and the player rides, a device that
// has room to spare climbs one level at a time, starting from the low level the automatic begins
// with. The counterpart of the downgrade governor in quality.js, with the same way of measuring
// (only while riding, not in the first seconds after an interruption). Pure, no three.js: the
// engine feeds it the frame times, applies the level it names, and tells it about every change.
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';
import { createFrameWindow, GOVERNOR_DEFAULTS } from './quality.js';

// Technical constants (no game play, so not in TUNING): how much reserve the device must show
// before the automatic risks a heavier level. The safety lies in the margin: the downgrade steps
// in below 50 fps, so a climb needs clearly more than that, and in the long window.
export const UPGRADE_DEFAULTS = Object.freeze({
  windowS: 10, // moving average
  minFps: 57, // average over the window (a 60 Hz screen delivers 60)
  // A frame slower than this is a hitch. At 60 Hz one missed refresh is 33 ms, so 25 ms (below
  // 40 fps) is the first duration that is clearly a missed one, but not mere jitter (16.7 ms ± 5).
  slowFrameS: 0.025,
  // Hitches allowed in the window: a good average can hide stutter that a child sees. 2 % are
  // about 12 of 600 frames at 60 fps.
  maxSlowShare: 0.02,
  graceS: GOVERNOR_DEFAULTS.graceS, // after an interruption or a change: same as downgrading
  maxFrameS: GOVERNOR_DEFAULTS.maxFrameS,
  cooldownS: 20, // minimum riding time after any level change, up or down
});

/**
 * The level the automatic may climb to from `level`, or null. Only the next level up is a
 * candidate (never above high, a blocked level is not skipped over). Excluded are
 * - `blocked`: levels that crashed or lost the 3D picture on this device (crash guard),
 * - `left`: levels the downgrade governor stepped down from in this running session,
 * - levels for which `fits(level)` is false: their (unreduced) memory estimate exceeds the budget
 *   of the device.
 * @param {{ level: string, blocked?: Iterable<string>, left?: Iterable<string>,
 *   fits?: (level: string) => boolean }} input
 */
export function nextUpgradeLevel({ level, blocked = [], left = [], fits = () => true }) {
  const i = GRAPHICS_LEVELS.indexOf(level);
  if (i < 0 || i >= GRAPHICS_LEVELS.length - 1) return null;
  const next = GRAPHICS_LEVELS[i + 1];
  if (new Set(blocked).has(next) || new Set(left).has(next)) return null;
  return fits(next) ? next : null;
}

/**
 * Whether the upgrade governor may measure at all: only while riding (`measuring`) and with
 * "Automatic" on (`auto`). A manually chosen level is never raised, so it gets no measurement.
 * @param {{ measuring: boolean, auto: boolean }} input
 */
export function upgradeMeasuring({ measuring, auto }) {
  return Boolean(measuring) && Boolean(auto);
}

/**
 * @param {object} deps
 * @param {() => string|null} deps.chooseTarget the level to climb to (see nextUpgradeLevel), or
 *   null; only called once the frame rate says go
 * @param {object} [deps.options] overrides of UPGRADE_DEFAULTS
 *
 * frame(dtSeconds, measuring, busy): `measuring` = riding with "Automatic" on and the page
 * visible; `busy` = a jump is in progress or an obstacle is being approached (the decision about
 * it is the session's, see `view.jumping` and `view.approaching`): a step never starts then (a
 * stage can stall the frames for seconds), the measurement goes on. Returns `{ level, fps }`
 * when the level has to go up now (cooldown and warm-up start by themselves), else null.
 */
export function createUpgradeGovernor({ chooseTarget, options = {} }) {
  const cfg = { ...UPGRADE_DEFAULTS, ...options };
  let grace = cfg.graceS;
  let cooldown = 0;
  const frames = createFrameWindow(cfg.windowS, cfg.slowFrameS);

  function interrupt() {
    frames.clear();
    grace = cfg.graceS;
  }

  return {
    frame(dtSeconds, measuring = true, busy = false) {
      if (!(dtSeconds >= 0)) return null;
      if (!measuring || dtSeconds > cfg.maxFrameS) {
        interrupt();
        return null;
      }
      // the cooldown is riding time: pauses and menus do not shorten it
      if (cooldown > 0) cooldown = Math.max(0, cooldown - dtSeconds);
      if (grace > 0) {
        grace -= dtSeconds;
        return null;
      }
      frames.push(dtSeconds);
      if (!frames.full || cooldown > 0) return null;
      const fps = frames.averageFps();
      if (!(fps >= cfg.minFps) || frames.slowShare() > cfg.maxSlowShare) return null;
      if (busy) return null; // the window stays: the step follows right after the jump
      const level = chooseTarget();
      frames.clear(); // a step starts the measurement over; no target: look again in a full window
      if (!level) return null;
      cooldown = cfg.cooldownS;
      grace = cfg.graceS;
      return { level, fps };
    },
    /** Frames around a stage of a level change, a pause or a menu are no measurement. */
    interrupt,
    /** The level changed (down, up, context loss, manual pick): wait before climbing again. */
    noteChange() {
      interrupt();
      cooldown = cfg.cooldownS;
    },
  };
}
