// Hilfen für die Sim-Tests (kein Produktivcode).
import { createRidingSim } from './riding-sim.js';
import { createRng } from './rng.js';
import { TUNING } from './tuning.js';
import { axisOf, crossAxisOf, headingOf, toLocal, wrapAngle } from './geometry.js';
import { blockExtents } from './jump.js';

export const DEG = Math.PI / 180;
export const DT = 1 / 60;

let nextId = 1;

export function makeElement(kind, height, extra = {}) {
  return {
    id: extra.id ?? `e${nextId++}`,
    kind,
    height,
    spread: kind === 'oxer' ? (extra.spread ?? 0.7) : 0,
    x: extra.x ?? 0,
    z: extra.z ?? 0,
    rot: extra.rot ?? 0,
  };
}

export function obstaclesOf(...elements) {
  return elements.map((el) => ({ number: null, elements: [el], directed: false }));
}

export function makeSim(elements, { seed = 1, rng, canRefuse, obstacles } = {}) {
  return createRidingSim({
    obstacles: obstacles ?? obstaclesOf(...elements),
    rules: { canRefuse: canRefuse ?? (() => true) },
    rng: rng ?? createRng(seed),
    tuning: TUNING,
  });
}

/**
 * Stellt das Pferd `distance` m vor die Vorderkante (Richtung dir), mit Kurswinkel `angle`
 * (positiv = Kurs driftet nach +t) und Querversatz `crossing` an der Hindernis-Ebene.
 */
export function placeBefore(sim, el, distance, opts = {}) {
  const { angle = 0, dir = 1, crossing = 0, speed = 0, gallop = false } = opts;
  const n = axisOf(el);
  const t = crossAxisOf(el);
  const halfSpread = (el.spread || 0) / 2;
  const along = -dir * (halfSpread + distance);
  const fAcross = Math.sin(angle);
  const fAlong = dir * Math.cos(angle);
  const across = crossing - (fAcross * Math.abs(along)) / Math.cos(angle);
  const x = el.x + along * n.x + across * t.x;
  const z = el.z + along * n.z + across * t.z;
  const heading = headingOf(fAlong * n.x + fAcross * t.x, fAlong * n.z + fAcross * t.z);
  sim.reset({ x, z, heading, speed, gallop });
}

/**
 * Lässt die Sim laufen. input: Objekt oder Funktion (sim, t) → Objekt.
 * until(sim, events, t) beendet vorzeitig. Liefert { events, t, steps }.
 */
export function drive(sim, input, { until, maxT = 15, dt = DT, onStep } = {}) {
  const events = [];
  let t = 0;
  let steps = 0;
  while (t < maxT) {
    const inp = typeof input === 'function' ? input(sim, t) : input;
    const ev = sim.step(dt, inp);
    events.push(...ev);
    t += dt;
    steps++;
    if (onStep) onStep(sim, ev, t);
    if (until && until(sim, ev, t)) break;
  }
  return { events, t, steps };
}

/** Eingabe, die Space drückt, sobald das Pferd `distance` m vor der Vorderkante ist. */
export function pressAt(baseInput, distanceFn) {
  let pressed = false;
  return (sim) => {
    const a = sim.approach;
    if (!pressed && a && sim.horse.jump === null) {
      const target = typeof distanceFn === 'function' ? distanceFn(sim, a) : distanceFn;
      if (target !== null && a.distance <= target) {
        pressed = true;
        return { ...baseInput, jump: true };
      }
    }
    return { ...baseInput, jump: false };
  };
}

export function ofType(events, type) {
  return events.filter((e) => e.type === type);
}

/** Wendet im Halt auf der Stelle, bis die Blickrichtung `heading` erreicht ist. */
export function turnInPlace(sim, heading) {
  return drive(sim, (s) => ({ steer: wrapAngle(s.horse.heading - heading) > 0 ? 1 : -1 }), {
    until: (s) => Math.abs(wrapAngle(s.horse.heading - heading)) < 0.03,
    maxT: 10,
  });
}

/** Bringt das Pferd per S zum Halt. */
export function brakeToHalt(sim) {
  return drive(sim, { throttle: -1 }, { until: (s) => s.horse.speed === 0, maxT: 10 });
}

/** true, wenn der Bezugspunkt im Sperrbereich eines Elements liegt (Pferd im Hindernis). */
export function insideBlock(sim, el) {
  const ext = blockExtents(el, TUNING);
  const p = toLocal(el, sim.horse.x, sim.horse.z);
  return Math.abs(p.along) < ext.along - 1e-6 && Math.abs(p.across) < ext.across - 1e-6;
}

/** RNG-Hülle, die die Anzahl der Züge zählt. */
export function countingRng(seed) {
  const base = createRng(seed);
  const f = () => {
    f.calls++;
    return base();
  };
  f.calls = 0;
  return f;
}
