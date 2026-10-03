// Bewegungszustand des Pferdes (rein, ohne three.js): Überblendung der Gangarten, Taktphase,
// Huf-Bahnen je Bein, Rumpfbewegung, Sprung-/Hopser-/Verweigerungs-Gewichte und
// Hufaufsetz-Ereignisse (Footfall). Der three.js-Adapter (index.js) setzt daraus die Knochen.
import {
  GAITS,
  GAIT_KEYS,
  approach,
  blendedFrequency,
  bodySample,
  bump,
  legSample,
} from './gaits.js';
import { clamp, smoothstep } from './math.js';
import { jumpParam } from './poses.js';

const GAIT_RATE = 5; // Überblendung Gangarten (1/s)

export function createMotion() {
  return {
    weights: { halt: 1, walk: 0, trot: 0, canter: 0 },
    phi: 0,
    freq: 0,
    lead: 1, // +1 Linksgalopp, −1 Rechtsgalopp
    legs: [0, 1, 2, 3].map(() => ({ dz: 0, y: 0, flex: 0, past: 0, sink: 0, stance: true, c: 1 })),
    body: { bob: 0, pitch: 0, neck: 0, roll: 0 },
    jumpWeight: 0,
    jumpJ: 0,
    hopWeight: 0,
    hopJ: 0,
    stopWeight: 0,
    runoutWeight: 0,
    runoutDir: 1,
    bend: 0, // Biegung, + = nach links (+X)
    lean: 0, // Neigung, + = nach rechts (rotation.z)
    speed: 0,
    time: 0,
  };
}

const tmpLeg = {};
const tmpBody = {};

/**
 * Einen Zeitschritt rechnen. state = sim.horse. Rückgabe: Beinindizes, deren Huf in diesem
 * Schritt aufgesetzt hat (0 LV, 1 RV, 2 LH, 3 RH).
 */
export function stepMotion(m, dt, state) {
  const gait = GAIT_KEYS.includes(state.gait) ? state.gait : 'halt';
  const v = Math.max(0, state.speed || 0);
  const turn = state.turnRate || 0;
  m.time += dt;
  m.speed = v;

  // Gangart-Gewichte. Wenden auf der Stelle: Schrittbewegung ohne Vorwärtsweg.
  const target = { halt: 0, walk: 0, trot: 0, canter: 0 };
  if (gait === 'halt') {
    const step = smoothstep(0.15, 0.6, Math.abs(turn));
    target.walk = step;
    target.halt = 1 - step;
  } else {
    target[gait] = 1;
  }
  if (gait === 'canter' && m.weights.canter < 0.05) {
    if (turn > 0.05) m.lead = -1;
    else if (turn < -0.05) m.lead = 1;
  }
  let sum = 0;
  for (const k of GAIT_KEYS) {
    m.weights[k] = approach(m.weights[k], target[k], GAIT_RATE, dt);
    sum += m.weights[k];
  }
  for (const k of GAIT_KEYS) m.weights[k] /= sum;
  const w = m.weights;

  // Taktphase: Frequenz aus den Gangarten (beim Wenden im Halt ein ruhiger Schritt-Takt)
  const vf = gait === 'halt' ? Math.max(v, 0.9 * Math.abs(turn)) : v;
  m.freq = blendedFrequency(w, vf);
  m.phi = (m.phi + m.freq * dt) % 1;

  // Sprung, Hopser, Verweigerung
  if (state.jump) {
    m.jumpWeight = approach(m.jumpWeight, 1, 14, dt);
    m.jumpJ = jumpParam(state.jump);
  } else {
    m.jumpWeight = approach(m.jumpWeight, 0, 4, dt);
    if (m.jumpWeight < 0.02) m.jumpJ = 0;
  }
  if (state.hop) {
    const p = clamp(state.hop.progress ?? 0, 0, 1);
    m.hopJ = 3 * p;
    m.hopWeight = bump(p, 0.15, 0.75);
  } else {
    m.hopWeight = approach(m.hopWeight, 0, 10, dt);
  }
  const ref = state.refusal;
  const stopT = ref && ref.type === 'stop' ? bump(ref.progress ?? 0, 0.12, 0.6) : 0;
  m.stopWeight = approach(m.stopWeight, stopT, 12, dt);
  if (ref && ref.type === 'runout') {
    if (m.runoutWeight < 0.02 && Math.abs(turn) > 0.01) m.runoutDir = turn > 0 ? -1 : 1;
    m.runoutWeight = approach(m.runoutWeight, bump(ref.progress ?? 0, 0.2, 0.7), 8, dt);
  } else {
    m.runoutWeight = approach(m.runoutWeight, 0, 8, dt);
  }

  // Kurven: Biegung in die Kurve, Neigung nach innen (Fliehkraft)
  m.bend = approach(m.bend, clamp(-turn * 0.28, -0.35, 0.35), 4, dt);
  m.lean = approach(m.lean, clamp(Math.atan((v * turn) / 9.81), -0.3, 0.3), 4, dt);

  // Huf-Bahnen und Bodenkontakt je Bein
  const falls = [];
  const quiet = gait === 'halt' || m.jumpWeight > 0.3 || m.hopWeight > 0.4;
  for (let leg = 0; leg < 4; leg++) {
    const L = m.legs[leg];
    let dz = 0;
    let y = 0;
    let flex = 0;
    let past = 0;
    let sink = w.halt * 0.008;
    let c = w.halt;
    for (const g of ['walk', 'trot', 'canter']) {
      const wg = w[g];
      if (wg < 1e-4) continue;
      legSample(g, leg, m.phi, v, m.freq, m.lead, tmpLeg);
      dz += wg * tmpLeg.dz;
      y += wg * tmpLeg.y;
      flex += wg * tmpLeg.flex;
      past += wg * tmpLeg.past;
      sink += wg * tmpLeg.sink;
      if (tmpLeg.stance) c += wg;
    }
    const wasDown = L.c >= 0.5;
    L.dz = dz;
    L.y = y;
    L.flex = flex;
    L.past = past;
    L.sink = sink;
    L.c = c;
    L.stance = c >= 0.5;
    if (L.stance && !wasDown && !quiet) falls.push(leg);
  }

  // Rumpf
  const B = m.body;
  B.bob = 0;
  B.pitch = 0;
  B.neck = 0;
  B.roll = 0;
  for (const g of ['walk', 'trot', 'canter']) {
    const wg = w[g];
    if (wg < 1e-4) continue;
    bodySample(g, m.phi, g === 'walk' && gait === 'halt' ? 0.3 : v, m.lead, tmpBody);
    B.bob += wg * tmpBody.bob;
    B.pitch += wg * tmpBody.pitch;
    B.neck += wg * tmpBody.neck;
    B.roll += wg * tmpBody.roll;
  }
  return falls;
}

/** Grundhaltung des Halses je Gangart (+ = tiefer/vorn). */
export function neckCarriage(weights) {
  return weights.halt * 0.04 + weights.walk * 0.12 + weights.trot * 0.0 + weights.canter * -0.05;
}

export { GAITS };
