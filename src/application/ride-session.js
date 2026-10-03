// Ride session (use case "ride"): owns the riding sim and everything around a single ride that is
// a rule, not a pixel: stepping the sim, rebuilding fallen rails, counting jumps with instant
// badges, the jump aid, feedback and the end of a course ride. The UI adapter only feeds input
// and time, shows `view` and executes the returned commands. No DOM, no three.js.
import { createRidingSim } from '../domain/sim/riding-sim.js';
import { finishRide, recordJump } from './progress-service.js';
import { createSoundMapper } from './ride-sounds.js';

// Shared result for steps without commands/events (read only): no allocation per frame.
const NONE = Object.freeze([]);

/**
 * @param {object} deps
 * @param {object} deps.mode strategy from application/modes (free or course)
 * @param {object} deps.store store port { get, update, onChange }
 * @param {{ nowIso(): string }} deps.clock clock port (badge dates)
 * @param {() => number} deps.rng random source in [0, 1)
 *
 * Commands returned by restart()/step():
 *   { type: 'endGallop' }  { type: 'resetTouchGallop' }  { type: 'feedback', key }
 *   { type: 'badges', ids }  { type: 'sound', name }  (takeoff, landing, railDown, finishSignal)
 *   { type: 'finished', screen, params }  (course ride ended, progress is already saved;
 *   a { type: 'sound', name: 'finishSignal' } comes right before it)
 */
export function createRideSession({ mode, store, clock, rng }) {
  const sim = createRidingSim({ obstacles: mode.obstacles, rules: mode.rules, rng });
  let rebuilds = [];
  let finished = false;
  let commands = [];
  // Direction (+1 / −1) of the last fall per element, for the pole animation of the 3D view
  const fallDirs = new Map();
  // Scratch objects reused every frame (the view is read by the render loop at 60 Hz)
  const prev = { x: 0, z: 0 };
  const frame = { horse: sim.horse, prev };
  const aidScratch = { elementId: null, dir: 1, zone: null };
  const view = {
    horse: sim.horse,
    rails: sim.rails,
    fallDirs,
    aid: null,
    highlight: null,
    finishMarked: false,
    lines: null,
    hud: null,
  };
  const sounds = createSoundMapper();
  // The jump aid needs the settings every frame: keep a copy and refresh it on change
  let settings = store.get('settings');
  const stopListening = store.onChange('settings', (next) => {
    settings = next;
  });

  function cancelRebuild(elementId) {
    if (rebuilds.length > 0) rebuilds = rebuilds.filter((r) => r.elementId !== elementId);
  }

  // What a mode may ask for while it handles events or updates.
  const host = {
    feedback: (key) => commands.push({ type: 'feedback', key }),
    rebuildIn(elementId, seconds) {
      cancelRebuild(elementId);
      rebuilds.push({ elementId, left: seconds });
    },
    rebuildNow(elementId) {
      cancelRebuild(elementId);
      sim.rebuild(elementId);
    },
    cancelRebuild,
  };

  function handleEvents(events) {
    for (const e of events) {
      if (e.type === 'gallopEnded') commands.push({ type: 'endGallop' });
      if (e.type === 'landed') {
        // Every jump over an obstacle counts, saved at once (rules 40, 45)
        const ids = recordJump(store, clock);
        if (ids.length) commands.push({ type: 'badges', ids });
      }
      if (e.type === 'railDown') fallDirs.set(e.elementId, e.dir);
    }
    commands.push(...sounds.commandsFor(events));
    mode.onEvents(events, host);
  }

  function tickRebuilds(dt) {
    if (rebuilds.length === 0) return;
    let due = false;
    for (const r of rebuilds) {
      r.left -= dt;
      if (r.left <= 0) {
        sim.rebuild(r.elementId);
        due = true;
      }
    }
    if (due) rebuilds = rebuilds.filter((r) => r.left > 0);
  }

  function finish({ screen, result, params }) {
    finished = true;
    commands.push({ type: 'sound', name: 'finishSignal' });
    const outcome = finishRide(store, clock, result);
    commands.push({ type: 'finished', screen, params: { ...params, result, ...outcome } });
  }

  function takeCommands() {
    if (commands.length === 0) return NONE;
    const out = commands;
    commands = [];
    return out;
  }

  function restart() {
    rebuilds = [];
    finished = false;
    sounds.reset();
    fallDirs.clear();
    sim.reset(mode.startPose());
    sim.rebuildAll();
    mode.onRestart();
    commands = [{ type: 'resetTouchGallop' }];
    return { commands: takeCommands() };
  }

  /**
   * One simulation step.
   * @param {number} dt seconds
   * @param {object} input InputState (steer, throttle, gallop, jump)
   * @returns {{ events: object[], commands: object[] }}
   */
  function step(dt, input) {
    if (finished) return { events: NONE, commands: NONE };
    prev.x = sim.horse.x;
    prev.z = sim.horse.z;
    const events = sim.step(dt, input);
    handleEvents(events);
    const update = mode.update(dt, frame, host);
    tickRebuilds(dt);
    if (update?.finished) finish(update.finished);
    return { events, commands: takeCommands() };
  }

  restart();

  return {
    restart,
    step,
    modeId: mode.id,
    obstacles: mode.obstacles,
    flags: mode.flags,
    quitLabelKey: mode.quitLabelKey,
    quitScreen: mode.quitScreen,
    /** Stops listening to the store; call when the ride screen is left. */
    dispose: stopListening,
    /** Plain data for the UI to display. The same object (and the same `aid` object) is reused on
     * every read: read it, do not keep it. `horse`, `rails` and `fallDirs` are live (not copied). */
    get view() {
      const target = mode.aidTarget({ approach: sim.approach, settings });
      if (target) {
        aidScratch.elementId = target.elementId;
        aidScratch.dir = target.dir;
        aidScratch.zone = sim.zoneFor(target.elementId, target.dir, sim.horse.speed);
        view.aid = aidScratch;
      } else {
        view.aid = null;
      }
      view.highlight = mode.highlight ?? null;
      view.finishMarked = Boolean(mode.finishMarked);
      view.lines = mode.lines ?? null;
      view.hud = mode.hudModel ? mode.hudModel() : null;
      return view;
    },
  };
}
