// Test helper: drives the horse animation through scripted ride sequences at a fixed frame rate
// and measures how much the pose changes from frame to frame (rule 24: no visible popping). Only
// used by tests; it has no dependency on three.js.
//
// A script is a list of steps. Each step lasts `for` seconds and sets (part of) the simulation
// state sim.horse: gait, speed, turnRate and optionally jump / hop / refusal, which advance their
// own progress from 0 to 1 over the step. A number or a pair [from, to] (linear ramp) is accepted
// for speed and turnRate. The speed of a gait change is not ramped automatically: write the ramp
// as steps if the sim does so.

export const FRAME = 1 / 60;

/** Linear value of `v` (number or [from, to]) at progress p. */
function valueAt(v, p) {
  if (Array.isArray(v)) return v[0] + (v[1] - v[0]) * p;
  return v ?? 0;
}

/** sim.horse-like state for step `st` at progress p ∈ [0, 1]. */
export function stateAt(st, p) {
  const state = {
    gait: st.gait ?? 'halt',
    speed: valueAt(st.speed, p),
    turnRate: valueAt(st.turn, p),
    y: 0,
    jump: null,
    hop: null,
    refusal: null,
  };
  if (st.jump) state.jump = { phase: st.jump, progress: p };
  if (st.hop) state.hop = { progress: p };
  if (st.refusal) state.refusal = { type: st.refusal, progress: p };
  return state;
}

/**
 * Runs the script, calls onFrame(state, stepIndex, t) after each frame has been advanced by
 * `advance(dt, state)`. Returns the number of frames.
 */
export function runScript(script, advance, onFrame, dt = FRAME) {
  let t = 0;
  let frames = 0;
  script.forEach((st, index) => {
    const n = Math.max(1, Math.round(st.for / dt));
    for (let i = 0; i < n; i++) {
      const state = stateAt(st, (i + 0.5) / n);
      advance(dt, state);
      onFrame(state, index, t);
      t += dt;
      frames++;
    }
  });
  return frames;
}

/** Tracks the maximum per-frame change of named scalar channels. */
export function createDeltaTracker() {
  const prev = new Map();
  const max = new Map();
  const where = new Map();
  return {
    max,
    where,
    /** value of channel `name` at time t */
    sample(name, value, t, tag = '') {
      if (prev.has(name)) {
        if (!max.has(name)) max.set(name, 0);
        const d = Math.abs(value - prev.get(name));
        if (d > (max.get(name) ?? 0)) {
          max.set(name, d);
          where.set(name, `${tag}@${t.toFixed(2)}s`);
        }
      }
      prev.set(name, value);
    },
    worst(filter = () => true) {
      let best = { name: '', value: 0, where: '' };
      for (const [name, value] of max) {
        if (filter(name) && value > best.value) best = { name, value, where: where.get(name) };
      }
      return best;
    },
  };
}

// The sequences the tests use (durations in seconds, speeds from TUNING.speeds)
export const SEQUENCES = {
  // halt → walk → trot → canter → trot → walk → halt, with the speed ramps of the sim
  gaitLadder: [
    { for: 1.5, gait: 'halt' },
    { for: 0.8, gait: 'walk', speed: [0, 1.5] },
    { for: 2.5, gait: 'walk', speed: 1.6 },
    { for: 0.6, gait: 'trot', speed: [1.6, 3.2] },
    { for: 2.5, gait: 'trot', speed: 3.2 },
    { for: 0.8, gait: 'canter', speed: [3.2, 6] },
    { for: 3, gait: 'canter', speed: 6 },
    { for: 0.6, gait: 'trot', speed: [6, 3.2] },
    { for: 2, gait: 'trot', speed: 3.2 },
    { for: 0.6, gait: 'walk', speed: [3.2, 1.6] },
    { for: 1.5, gait: 'walk', speed: 1.6 },
    { for: 0.6, gait: 'halt', speed: [1.6, 0] },
    { for: 2, gait: 'halt' },
  ],
  // canter in circles with a change of direction (lead change)
  leadChange: [
    { for: 3, gait: 'canter', speed: 6, turn: -0.5 },
    { for: 0.6, gait: 'canter', speed: 6, turn: [-0.5, 0.5] },
    { for: 3, gait: 'canter', speed: 6, turn: 0.5 },
    { for: 0.6, gait: 'canter', speed: 6, turn: [0.5, -0.5] },
    { for: 3, gait: 'canter', speed: 6, turn: -0.5 },
  ],
  // approach, jump, landing, canter away
  jump: [
    { for: 3, gait: 'canter', speed: 6 },
    { for: 0.2, gait: 'canter', speed: 6, jump: 'takeoff' },
    { for: 0.6, gait: 'canter', speed: 6, jump: 'flight' },
    { for: 0.25, gait: 'canter', speed: 6, jump: 'landing' },
    { for: 3, gait: 'canter', speed: 6 },
  ],
  // jump at a trot, then a hop over a small obstacle
  jumpTrot: [
    { for: 3, gait: 'trot', speed: 3.4 },
    { for: 0.25, gait: 'trot', speed: 3.4, jump: 'takeoff' },
    { for: 0.5, gait: 'trot', speed: 3.4, jump: 'flight' },
    { for: 0.3, gait: 'trot', speed: 3.4, jump: 'landing' },
    { for: 2, gait: 'trot', speed: 3.4 },
    { for: 0.4, gait: 'trot', speed: 3.4, hop: true },
    { for: 2, gait: 'trot', speed: 3.4 },
  ],
  // refusal: the horse brakes in front of the jump (through the gaits, as the sim does) and stands
  refusalStop: [
    { for: 3, gait: 'canter', speed: 6 },
    { for: 0.25, gait: 'trot', speed: [5, 3], refusal: 'stop' },
    { for: 0.25, gait: 'walk', speed: [3, 0.6], refusal: 'stop' },
    { for: 0.7, gait: 'halt', speed: 0, refusal: 'stop' },
    { for: 2.5, gait: 'halt' },
    { for: 0.8, gait: 'walk', speed: [0, 1.5] },
    { for: 2, gait: 'walk', speed: 1.5 },
  ],
  // frontal stop at the fence: gait and speed drop to zero in one frame
  fenceStop: [
    { for: 3, gait: 'canter', speed: 6 },
    { for: 2.5, gait: 'halt', speed: 0 },
    { for: 0.8, gait: 'walk', speed: [0.2, 1.8] },
    { for: 0.5, gait: 'trot', speed: [2, 3] },
    { for: 3, gait: 'trot', speed: 3 },
    { for: 2, gait: 'halt', speed: 0 },
  ],
  // run-out to the side
  runout: [
    { for: 3, gait: 'canter', speed: 6 },
    { for: 0.6, gait: 'canter', speed: 5, turn: 1.2, refusal: 'runout' },
    { for: 2, gait: 'canter', speed: 6 },
  ],
  // turning on the spot, rein-back
  spot: [
    { for: 1, gait: 'halt' },
    { for: 2, gait: 'halt', turn: 1.4 },
    { for: 1.5, gait: 'halt' },
    { for: 1.2, gait: 'back', speed: [-0.1, -0.5] },
    { for: 1, gait: 'back', speed: -0.5 },
    { for: 0.5, gait: 'halt', speed: [0, 0] },
    { for: 2, gait: 'halt' },
  ],
};
