// Reit-Simulation (Vertrag: docs/specs/springreiten-trainer/architecture.md, „Reit-Simulation").
// Rein und deterministisch: alle Zufallszüge laufen über das injizierte rng.
import { TUNING } from './tuning.js';
import {
  approachInfo,
  axisOf,
  crossAxisOf,
  forwardOf,
  fromLocal,
  headingOf,
  toLocal,
  wrapAngle,
} from './geometry.js';
import {
  advance,
  applyFence,
  arenaBounds,
  clamp,
  gaitForSpeed,
  updateSpeed,
  updateSteering,
} from './movement.js';
import {
  blockExtents,
  gaitAllows,
  landingDistance,
  railLayout,
  selfMinSpeed,
  takeoffRisk,
  zoneForElement,
} from './jump.js';

const ALWAYS_REFUSE = { canRefuse: () => true };
// ab dieser Querkomponente (≈ sin 10°) bestimmt die Kursrichtung die Ausweichseite
const DRIFT_SIDE = Math.sin((10 * Math.PI) / 180);

function sideOf(value) {
  return value >= 0 ? 1 : -1;
}

/** Schnitt eines Strahls (Start p, Richtung d) mit einem achsparallelen Rechteck um 0. */
function rayHitsBox(a, c, da, dc, halfA, halfC) {
  let tMin = 0;
  let tMax = Infinity;
  for (const [p, d, h] of [
    [a, da, halfA],
    [c, dc, halfC],
  ]) {
    if (Math.abs(d) < 1e-9) {
      if (p < -h || p > h) return false;
      continue;
    }
    let t1 = (-h - p) / d;
    let t2 = (h - p) / d;
    if (t1 > t2) [t1, t2] = [t2, t1];
    tMin = Math.max(tMin, t1);
    tMax = Math.min(tMax, t2);
    if (tMin > tMax) return false;
  }
  return true;
}

export function createRidingSim({
  obstacles = [],
  rules = ALWAYS_REFUSE,
  rng = Math.random,
  tuning = TUNING,
} = {}) {
  const T = tuning;
  const canRefuse = (id, dir) => (rules && rules.canRefuse ? rules.canRefuse(id, dir) : true);

  let elements = [];
  const byId = new Map();
  const rails = new Map();

  const horse = {
    x: 0,
    z: 0,
    heading: 0,
    speed: 0,
    gait: 'halt',
    gallop: false,
    y: 0,
    jump: null,
    hop: null,
    refusal: null,
    turnRate: 0,
  };

  let settling = false;
  let gallopBlocked = false;
  let fenceStopNormal = null;
  let jump = null;
  let hop = null;
  let refusal = null;
  let maneuver = null;
  let approach = null;
  const locks = new Set();
  const armed = new Set();

  function setObstacles(list) {
    elements = [];
    byId.clear();
    rails.clear();
    for (const obstacle of list || []) {
      for (const el of obstacle.elements) {
        elements.push(el);
        byId.set(el.id, el);
        rails.set(
          el.id,
          railLayout(el).map(() => true),
        );
      }
    }
    armed.clear();
    locks.clear();
    jump = null;
    approach = computeApproach();
  }

  function reset({ x = 0, z = 0, heading = 0, speed = 0, gallop = false } = {}) {
    Object.assign(horse, {
      x,
      z,
      heading: wrapAngle(heading),
      speed,
      gallop,
      gait: gaitForSpeed(speed, gallop, T.speeds),
      y: 0,
      jump: null,
      hop: null,
      refusal: null,
      turnRate: 0,
    });
    settling = false;
    // nach einem Neustart galoppiert das Pferd erst nach neuem Drücken
    gallopBlocked = !gallop;
    fenceStopNormal = null;
    jump = null;
    hop = null;
    refusal = null;
    maneuver = null;
    locks.clear();
    armed.clear();
    approach = computeApproach();
  }

  function rebuild(elementId) {
    const r = rails.get(elementId);
    if (r) r.fill(true);
  }

  function rebuildAll() {
    for (const r of rails.values()) r.fill(true);
  }

  // ---- Anreiten -----------------------------------------------------------

  function approaches() {
    const list = [];
    for (const el of elements) {
      if (jump && jump.el === el) continue;
      const info = approachInfo(el, horse, T.approachDistance);
      if (info && info.approaching) list.push({ el, info });
    }
    return list;
  }

  function computeApproach() {
    let best = null;
    for (const a of approaches()) {
      if (!best || a.info.distance < best.info.distance) best = a;
    }
    if (!best) return null;
    return {
      elementId: best.el.id,
      dir: best.info.dir,
      distance: best.info.distance,
      angle: best.info.angle,
    };
  }

  // ---- Galopp ---------------------------------------------------------------

  function updateGallop(input) {
    if (!input.gallop) gallopBlocked = false;
    const want = input.gallop && !gallopBlocked && !refusal;
    if (want && !horse.gallop) settling = false;
    horse.gallop = want;
  }

  function endGallop(reason, events) {
    horse.gallop = false;
    gallopBlocked = true;
    events.push({ type: 'gallopEnded', reason });
  }

  // ---- Springen ---------------------------------------------------------------

  function pressJump(events) {
    if (jump || refusal || maneuver) return;
    let cand = null;
    for (const a of approaches()) {
      const zone = zoneForElement(a.el, horse.speed, T);
      if (a.info.distance > zone.reach) continue;
      if (!cand || a.info.distance < cand.info.distance) cand = { ...a, zone };
    }
    if (cand) {
      // Hindernis in Reichweite: Sprung oder nichts (kein Hopser), Regeln 19, 21
      const ok =
        gaitAllows(cand.el, horse.gait) &&
        cand.info.angle <= T.jump.maxAngle &&
        cand.info.distance >= cand.zone.lastPoint - 1e-6;
      if (ok) takeoff(cand.el, cand.info, false, events);
      return;
    }
    if ((horse.gait === 'trot' || horse.gait === 'canter') && !hop) {
      hop = { t: 0 };
      events.push({ type: 'hop' });
    }
  }

  function chooseRail(el, info, zone) {
    const up = rails.get(el.id);
    if (up.length === 1) return up[0] ? 0 : -1;
    const first = info.dir > 0 ? 0 : 1;
    const second = 1 - first;
    let pref;
    if (info.distance < zone.near) pref = first;
    else if (info.distance > zone.far) pref = second;
    else pref = rng() < 0.5 ? first : second;
    if (up[pref]) return pref;
    return up[1 - pref] ? 1 - pref : -1;
  }

  function takeoff(el, info, self, events) {
    const zone = zoneForElement(el, horse.speed, T);
    const risk = takeoffRisk(
      el,
      {
        gait: horse.gait,
        speed: horse.speed,
        distance: info.distance,
        angle: info.angle,
        self,
      },
      T,
    );
    // Abwurf wird vor dem Sprung entschieden; im sicheren Kern (risk 0) gibt es keinen Zufallszug
    const fallRail = risk > 0 && rng() < risk ? chooseRail(el, info, zone) : -1;
    const u0 = toLocal(el, horse.x, horse.z).along * info.dir;
    const uEnd = (el.spread || 0) / 2 + landingDistance(el, info.distance, T);
    jump = {
      el,
      dir: info.dir,
      traveled: 0,
      path: (uEnd - u0) / Math.cos(info.angle),
      v: Math.max(horse.speed, T.jump.flight.minSpeed),
      peak: el.height + T.jump.flight.clearance,
      crossings: railLayout(el).map((r) => ({
        rail: r.rail,
        u: r.along * info.dir,
        falls: r.rail === fallRail,
        done: false,
      })),
      knocked: false,
    };
    hop = null;
    armed.delete(el.id);
    horse.turnRate = 0;
    events.push({ type: 'takeoff', elementId: el.id, dir: info.dir, self, risk });
    syncJumpView(0);
  }

  function syncJumpView(s) {
    const f = T.jump.flight;
    let phase;
    let progress;
    if (s < f.takeoffShare) {
      phase = 'takeoff';
      progress = s / f.takeoffShare;
    } else if (s < 1 - f.landingShare) {
      phase = 'flight';
      progress = (s - f.takeoffShare) / (1 - f.takeoffShare - f.landingShare);
    } else {
      phase = 'landing';
      progress = (s - (1 - f.landingShare)) / f.landingShare;
    }
    horse.jump = { phase, progress: clamp(progress, 0, 1), elementId: jump.el.id };
    horse.y = jump.peak * Math.sin(Math.PI * s);
  }

  function crossRails(u, events) {
    const up = rails.get(jump.el.id);
    for (const c of jump.crossings) {
      if (c.done || u < c.u) continue;
      c.done = true;
      if (c.falls && up[c.rail]) {
        up[c.rail] = false;
        jump.knocked = true;
        events.push({ type: 'railDown', elementId: jump.el.id, rail: c.rail });
      }
    }
  }

  function advanceJump(dt, events) {
    advance(horse, jump.v, dt);
    jump.traveled += jump.v * dt;
    const s = Math.min(1, jump.traveled / jump.path);
    const u = toLocal(jump.el, horse.x, horse.z).along * jump.dir;
    crossRails(s >= 1 ? Infinity : u, events);
    if (s >= 1) {
      events.push({ type: 'landed', elementId: jump.el.id, dir: jump.dir, knocked: jump.knocked });
      jump = null;
      horse.jump = null;
      horse.y = 0;
      return;
    }
    syncJumpView(s);
  }

  // ---- Letzter Absprungpunkt, Verweigerung, Ausweichen ------------------------

  function checkLastPoints(events) {
    let best = null;
    for (const el of elements) {
      const info = approachInfo(el, horse, T.approachDistance);
      if (!info || !info.approaching) {
        armed.delete(el.id);
        continue;
      }
      const zone = zoneForElement(el, horse.speed, T);
      if (info.distance > zone.lastPoint) {
        armed.add(el.id);
        continue;
      }
      if (armed.has(el.id) && (!best || info.distance < best.info.distance)) best = { el, info };
    }
    if (best) decide(best.el, best.info, events);
  }

  function decide(el, info, events) {
    armed.delete(el.id);
    if (locks.has(el.id) || !canRefuse(el.id, info.dir)) {
      // Sperre bzw. Hindernis ohne Verweigerung: nie selbst springen, ohne Fehler ausweichen
      startManeuver(el, 'front', info.crossing);
      events.push({ type: 'swerve', elementId: el.id });
      return;
    }
    const gaitOk = gaitAllows(el, horse.gait);
    if (!gaitOk || horse.speed < selfMinSpeed(el, T)) {
      refuse(el, info, gaitOk ? 'speed' : 'gait', 'stop', events);
    } else if (info.angle > T.jump.maxAngle) {
      refuse(el, info, 'angle', 'runout', events);
    } else {
      takeoff(el, info, true, events);
    }
  }

  function refuse(el, info, reason, type, events) {
    events.push({ type: 'refusal', elementId: el.id, dir: info.dir, reason });
    endGallop('refusal', events);
    locks.add(el.id);
    hop = null;
    if (type === 'stop') {
      const room = Math.max(0.05, info.distance - T.refusal.stopMargin);
      refusal = {
        type,
        t: 0,
        elementId: el.id,
        decel: Math.max(4, (horse.speed * horse.speed) / (2 * room)),
      };
    } else {
      refusal = { type, t: 0, elementId: el.id };
      startManeuver(el, 'front', info.crossing);
    }
  }

  function startManeuver(el, face, offset) {
    const f = forwardOf(horse.heading);
    const n = axisOf(el);
    const t = crossAxisOf(el);
    const fa = f.x * n.x + f.z * n.z;
    const fc = f.x * t.x + f.z * t.z;
    let out;
    if (face === 'front') {
      const side = Math.abs(fc) > DRIFT_SIDE ? sideOf(fc) : sideOf(offset);
      out = headingOf(t.x * side, t.z * side);
    } else {
      const side = Math.abs(fa) > DRIFT_SIDE ? sideOf(fa) : sideOf(offset);
      out = headingOf(n.x * side, n.z * side);
    }
    maneuver = {
      el,
      phase: 'out',
      outHeading: out,
      origHeading: horse.heading,
      t: 0,
      aligned: false,
    };
  }

  function steerManeuver(dt) {
    const target = maneuver.phase === 'out' ? maneuver.outHeading : maneuver.origHeading;
    const diff = wrapAngle(target - horse.heading);
    const maxStep = T.refusal.maneuverTurnRate * dt;
    const turn = clamp(diff, -maxStep, maxStep);
    horse.heading = wrapAngle(horse.heading + turn);
    horse.turnRate = -turn / dt;
    maneuver.aligned = Math.abs(diff - turn) < 1e-9;
  }

  function maneuverClear() {
    const el = maneuver.el;
    const p = toLocal(el, horse.x, horse.z);
    const f = forwardOf(maneuver.origHeading);
    const n = axisOf(el);
    const t = crossAxisOf(el);
    const ext = blockExtents(el, T);
    const m = T.refusal.clearMargin;
    return !rayHitsBox(
      p.along,
      p.across,
      f.x * n.x + f.z * n.z,
      f.x * t.x + f.z * t.z,
      ext.along + m,
      ext.across + m,
    );
  }

  function updateManeuver(dt) {
    maneuver.t += dt;
    if (maneuver.phase === 'out') {
      if (maneuverClear()) maneuver.phase = 'back';
    } else if (maneuver.aligned) {
      endManeuver();
      return;
    }
    if (maneuver.t > T.refusal.maneuverTimeout) endManeuver();
  }

  function endManeuver() {
    maneuver = null;
    horse.turnRate = 0;
  }

  function updateRefusal(dt) {
    refusal.t += dt;
    if (refusal.type === 'stop') {
      if (refusal.t >= T.refusal.stopDuration) {
        horse.speed = 0;
        horse.gait = 'halt';
        refusal = null;
      }
    } else if (!maneuver && refusal.t >= T.refusal.runoutDuration) {
      refusal = null;
    }
  }

  // ---- Kollision mit Hindernissen und Zaun ----------------------------------------

  function constrainObstacles(prevX, prevZ, events) {
    for (const el of elements) {
      if (jump && jump.el === el) continue;
      const ext = blockExtents(el, T);
      const p = toLocal(el, horse.x, horse.z);
      if (Math.abs(p.along) >= ext.along || Math.abs(p.across) >= ext.across) continue;
      const q = toLocal(el, prevX, prevZ);
      let a = p.along;
      let c = p.across;
      let face;
      const eps = 1e-6;
      if (Math.abs(q.along) >= ext.along - eps) {
        a = sideOf(q.along) * ext.along;
        face = 'front';
      } else if (Math.abs(q.across) >= ext.across - eps) {
        c = sideOf(q.across) * ext.across;
        face = 'side';
      } else if (ext.along - Math.abs(p.along) <= ext.across - Math.abs(p.across)) {
        a = sideOf(p.along) * ext.along;
        face = 'front';
      } else {
        c = sideOf(p.across) * ext.across;
        face = 'side';
      }
      const w = fromLocal(el, a, c);
      horse.x = w.x;
      horse.z = w.z;
      // Trifft das Pferd Ständer/Hindernis ohne Sprung: seitlich ausweichen (Regel 22)
      if (!jump && !maneuver && !refusal && horse.speed >= T.speeds.trotMin) {
        startManeuver(el, face, face === 'front' ? p.across : p.along);
        events.push({ type: 'swerve', elementId: el.id });
      }
    }
  }

  function handleFence(events) {
    const speedBefore = horse.speed;
    const res = applyFence(horse, T, { allowStop: !jump });
    if (res && res.frontal) {
      const n = res.normal;
      const repeat = fenceStopNormal && fenceStopNormal.x === n.x && fenceStopNormal.z === n.z;
      horse.speed = 0;
      horse.gait = 'halt';
      horse.turnRate = 0;
      settling = false;
      maneuver = null;
      hop = null;
      if (refusal && refusal.type === 'runout') refusal = null;
      if (!repeat && speedBefore >= T.speeds.haltBelow) {
        events.push({ type: 'fenceStop' });
        endGallop('fence', events);
      } else if (horse.gallop) {
        endGallop('fence', events);
      }
      fenceStopNormal = n;
    } else if (fenceStopNormal) {
      const n = fenceStopNormal;
      const f = forwardOf(horse.heading);
      const { maxX, maxZ } = arenaBounds(T);
      const gap = n.x !== 0 ? maxX - n.x * horse.x : maxZ - n.z * horse.z;
      if (gap > 0.05 || f.x * n.x + f.z * n.z < Math.cos(T.fence.frontalAngle)) {
        fenceStopNormal = null;
      }
    }
  }

  // ---- Schritt ---------------------------------------------------------------------

  function releaseLocks() {
    for (const id of locks) {
      const el = byId.get(id);
      if (!el || Math.hypot(horse.x - el.x, horse.z - el.z) > T.approachDistance) locks.delete(id);
    }
  }

  function syncView() {
    const hopCfg = T.jump.hop;
    if (hop) {
      const p = clamp(hop.t / hopCfg.duration, 0, 1);
      horse.hop = { progress: p };
      if (!jump) horse.y = hopCfg.height * Math.sin(Math.PI * p);
    } else {
      horse.hop = null;
      if (!jump) horse.y = 0;
    }
    if (refusal) {
      const dur = refusal.type === 'stop' ? T.refusal.stopDuration : T.refusal.runoutDuration;
      horse.refusal = {
        type: refusal.type,
        progress: clamp(refusal.t / dur, 0, 1),
        elementId: refusal.elementId,
      };
    } else {
      horse.refusal = null;
    }
  }

  function substep(dt, input, events) {
    const prevX = horse.x;
    const prevZ = horse.z;
    if (jump) {
      advanceJump(dt, events);
    } else {
      if (refusal && refusal.type === 'stop') {
        horse.speed = Math.max(0, horse.speed - refusal.decel * dt);
        horse.turnRate = 0;
      } else {
        const ctl = { speed: horse.speed, gallop: horse.gallop, settling };
        updateSpeed(ctl, refusal ? 0 : input.throttle, dt, T);
        horse.speed = ctl.speed;
        settling = ctl.settling;
        if (maneuver) steerManeuver(dt);
        else updateSteering(horse, input.steer, dt, T);
      }
      horse.gait = gaitForSpeed(horse.speed, horse.gallop, T.speeds);
      advance(horse, horse.speed, dt);
    }
    constrainObstacles(prevX, prevZ, events);
    handleFence(events);
    if (maneuver) updateManeuver(dt);
    if (refusal) updateRefusal(dt);
    if (hop) {
      hop.t += dt;
      if (hop.t >= T.jump.hop.duration) hop = null;
    }
    if (!jump && !refusal && !maneuver) checkLastPoints(events);
    releaseLocks();
    syncView();
  }

  function step(dt, input = {}) {
    const events = [];
    const inp = {
      steer: Number.isFinite(input.steer) ? clamp(input.steer, -1, 1) : 0,
      throttle: Number.isFinite(input.throttle) ? clamp(input.throttle, -1, 1) : 0,
      gallop: !!input.gallop,
      jump: !!input.jump,
    };
    const total = Number.isFinite(dt) ? clamp(dt, 0, T.sim.maxDt) : 0;
    if (total > 0) {
      updateGallop(inp);
      if (inp.jump) pressJump(events);
      const n = Math.max(1, Math.ceil(total / T.sim.substep - 1e-9));
      for (let i = 0; i < n; i++) substep(total / n, inp, events);
    }
    approach = computeApproach();
    return events;
  }

  function zoneFor(elementId, _dir, speed = horse.speed) {
    const el = byId.get(elementId);
    if (!el) return null;
    const { far, near, lastPoint, reach } = zoneForElement(el, speed, T);
    return { far, near, lastPoint, reach };
  }

  setObstacles(obstacles);
  reset();

  return {
    horse,
    rails,
    step,
    reset,
    rebuild,
    rebuildAll,
    zoneFor,
    setObstacles,
    get approach() {
      return approach;
    },
    get elements() {
      return elements;
    },
  };
}
