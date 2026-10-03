// Ride session (use case "ride"): owns the riding sim and everything around a single ride that is
// a rule, not a pixel: stepping the sim, rebuilding fallen rails, counting jumps with instant
// badges, the jump aid, feedback and the end of a course ride. The UI adapter only feeds input
// and time, shows `view` and executes the returned commands. No DOM, no three.js.
import { createRidingSim } from '../domain/sim/riding-sim.js';
import { finishRide, recordJump } from './progress-service.js';
import { createSoundMapper } from './ride-sounds.js';

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
  const sounds = createSoundMapper();
  // Direction (+1 / −1) of the last fall per element, for the pole animation of the 3D view
  const fallDirs = new Map();
  // The jump aid needs the settings every frame: keep a copy and refresh it on change
  let settings = store.get('settings');
  const stopListening = store.onChange('settings', (next) => {
    settings = next;
  });

  // What a mode may ask for while it handles events or updates.
  const host = {
    feedback: (key) => commands.push({ type: 'feedback', key }),
    rebuildIn(elementId, seconds) {
      rebuilds = rebuilds.filter((r) => r.elementId !== elementId);
      rebuilds.push({ elementId, left: seconds });
    },
    rebuildNow(elementId) {
      rebuilds = rebuilds.filter((r) => r.elementId !== elementId);
      sim.rebuild(elementId);
    },
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
    for (const r of rebuilds) r.left -= dt;
    for (const r of rebuilds.filter((x) => x.left <= 0)) sim.rebuild(r.elementId);
    rebuilds = rebuilds.filter((r) => r.left > 0);
  }

  function finish({ screen, result, params }) {
    finished = true;
    commands.push({ type: 'sound', name: 'finishSignal' });
    const outcome = finishRide(store, clock, result);
    commands.push({ type: 'finished', screen, params: { ...params, result, ...outcome } });
  }

  function takeCommands() {
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
    if (finished) return { events: [], commands: [] };
    const prev = { x: sim.horse.x, z: sim.horse.z };
    const events = sim.step(dt, input);
    handleEvents(events);
    const update = mode.update(dt, { horse: sim.horse, prev }, host);
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
    /** Plain data for the UI to display; `horse`, `rails` and `fallDirs` are live (not copied). */
    get view() {
      const target = mode.aidTarget({ approach: sim.approach, settings });
      return {
        horse: sim.horse,
        rails: sim.rails,
        fallDirs,
        aid: target
          ? { ...target, zone: sim.zoneFor(target.elementId, target.dir, sim.horse.speed) }
          : null,
        highlight: mode.highlight ?? null,
        finishMarked: Boolean(mode.finishMarked),
        lines: mode.lines ?? null,
        hud: mode.hudModel ? mode.hudModel() : null,
      };
    },
  };
}
