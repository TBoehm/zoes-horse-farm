// Rule 24: gait changes, canter lead change, jumps, refusals and the halt run without visible
// popping. The tests drive the animation through scripted rides at 60 fps and measure what changes
// from one frame to the next.
import * as THREE from 'three';
import { describe, expect, it, vi } from 'vitest';
import { createRng } from '../textures.js';
import { REST } from './anatomy.js';
import { createHorse } from './index.js';
import { createMotion, stepMotion } from './motion.js';

// The motion state of the real horse is private; the tests of the planted hooves read it from the
// motion objects that the horse creates.
const created = vi.hoisted(() => []);
vi.mock('./motion.js', async (importOriginal) => {
  const original = await importOriginal();
  return {
    ...original,
    createMotion: (...args) => {
      const motion = original.createMotion(...args);
      created.push(motion);
      return motion;
    },
  };
});
import {
  FRAME,
  SEQUENCES,
  createDeltaTracker,
  runScript,
} from '../../../../tests/support/sequence-helper.js';

// Largest per-frame change of the motion state (pure model, 60 fps). The steady canter at full
// speed reaches about 0.10 m for a hoof, so these leave a margin of 20-30 % and nothing more.
const LIMITS = {
  dz: 0.13, // m, fore/aft hoof position relative to the body
  y: 0.07, // m, hoof height
  flex: 0.42, // rad, carpus/hock flexion
  past: 0.35, // rad, pastern fold
  sink: 0.03, // m
  bob: 0.012, // m
  pitch: 0.03, // rad
  neck: 0.045, // rad
  roll: 0.02, // rad
  weight: 0.08, // gait weights (cross-fade of ≈ 0.5 s)
  jumpWeight: 0.25,
  hopWeight: 0.2,
  stopWeight: 0.2,
  runoutWeight: 0.12,
  bend: 0.03,
  lean: 0.03,
  leadBlend: 0.1,
};
// a planted hoof moves less than this relative to the ground per frame (m)
const SLIDE = 0.001;

function trackMotion(name) {
  const m = createMotion({ rng: createRng(5) });
  const tr = createDeltaTracker();
  const slide = { max: 0, where: '' };
  const prev = [null, null, null, null];
  let bodyZ = 0;
  runScript(
    SEQUENCES[name],
    (dt, state) => {
      stepMotion(m, dt, state);
      bodyZ += (state.speed || 0) * dt;
    },
    (state, step, t) => {
      const tag = `${name}#${step}`;
      m.legs.forEach((L, k) => {
        for (const c of ['dz', 'y', 'flex', 'past', 'sink']) tr.sample(`${c}${k}`, L[c], t, tag);
        const world = bodyZ + L.dz;
        const planted = L.y === 0 && !L.squaring;
        if (prev[k] && planted && prev[k].planted) {
          const d = Math.abs(world - prev[k].world);
          if (d > slide.max) {
            slide.max = d;
            slide.where = `leg ${k} ${tag}@${t.toFixed(2)}s`;
          }
        }
        prev[k] = { world, planted };
      });
      for (const c of ['bob', 'pitch', 'neck', 'roll']) tr.sample(`body.${c}`, m.body[c], t, tag);
      for (const k of ['halt', 'walk', 'trot', 'canter', 'back']) {
        tr.sample(`weight.${k}`, m.weights[k], t, tag);
      }
      for (const k of ['jumpWeight', 'hopWeight', 'stopWeight', 'runoutWeight', 'bend', 'lean']) {
        tr.sample(k, m[k], t, tag);
      }
      tr.sample('leadBlend', m.leadBlend, t, tag);
    },
  );
  return { tr, slide };
}

function limitFor(channel) {
  const base = channel.replace(/[0-3]$/, '');
  if (base.startsWith('body.')) return LIMITS[base.slice(5)];
  if (base.startsWith('weight.')) return LIMITS.weight;
  return LIMITS[base];
}

describe('motion continuity at 60 fps (pure model)', () => {
  for (const name of Object.keys(SEQUENCES)) {
    it(`${name}: no popping of hooves, body and weights`, () => {
      const { tr } = trackMotion(name);
      for (const [channel, value] of tr.max) {
        const limit = limitFor(channel);
        expect(limit, `unknown channel ${channel}`).toBeDefined();
        expect(
          value,
          `${channel} changes by ${value.toFixed(4)} per frame at ${tr.where.get(channel)}`,
        ).toBeLessThanOrEqual(limit);
      }
    });

    it(`${name}: planted hooves do not slide`, () => {
      const { slide } = trackMotion(name);
      expect(slide.max, `slide at ${slide.where}`).toBeLessThanOrEqual(SLIDE);
    });
  }

  it('the checker catches a pop (a gait weight that jumps)', () => {
    const m = createMotion();
    const tr = createDeltaTracker();
    const state = { gait: 'canter', speed: 6, turnRate: 0 };
    for (let i = 0; i < 60; i++) stepMotion(m, FRAME, state);
    for (let i = 0; i < 20; i++) {
      stepMotion(m, FRAME, state);
      // what the old exponential cross-fade at rate 5 did on the first frame of a change
      tr.sample('weight.trot', i === 10 ? 0.35 : m.weights.trot, i * FRAME);
    }
    expect(tr.max.get('weight.trot')).toBeGreaterThan(LIMITS.weight);
  });

  it('cross-fades the gaits over about half a second', () => {
    const m = createMotion();
    const state = { gait: 'walk', speed: 1.5, turnRate: 0 };
    for (let i = 0; i < 120; i++) stepMotion(m, FRAME, state);
    state.gait = 'trot';
    state.speed = 3;
    let t95 = 0;
    let t10 = 0;
    for (let i = 1; i <= 90; i++) {
      stepMotion(m, FRAME, state);
      if (!t10 && m.weights.trot > 0.1) t10 = i * FRAME;
      if (!t95 && m.weights.trot > 0.95) t95 = i * FRAME;
    }
    expect(t10).toBeGreaterThan(0.05); // starts gently
    expect(t95).toBeGreaterThan(0.3);
    expect(t95).toBeLessThan(0.6);
  });
});

describe('canter lead change', () => {
  function canter(turnA, turnB, seconds = 3) {
    const m = createMotion();
    const state = { gait: 'canter', speed: 6, turnRate: turnA };
    for (let i = 0; i < 180; i++) stepMotion(m, FRAME, state);
    state.turnRate = turnB;
    const blend = [];
    const leads = [];
    for (let i = 0; i < seconds * 60; i++) {
      stepMotion(m, FRAME, state);
      blend.push(m.leadBlend);
      leads.push(m.lead);
    }
    return { m, blend, leads };
  }

  it('starts on the lead of the turn and keeps it', () => {
    const left = canter(-0.5, -0.5, 1);
    expect(left.m.lead).toBe(1);
    const right = canter(0.5, 0.5, 1);
    expect(right.m.lead).toBe(-1);
  });

  it('changes the lead when the turn goes the other way for a while — gradually, not as a flip', () => {
    const { blend, leads } = canter(-0.5, 0.6);
    expect(leads[0]).toBe(1);
    expect(leads[leads.length - 1]).toBe(-1);
    const first = leads.indexOf(-1);
    expect(first / 60).toBeGreaterThan(0.3); // not at once
    expect(blend[blend.length - 1]).toBe(-1);
    for (let i = 1; i < blend.length; i++) {
      expect(Math.abs(blend[i] - blend[i - 1])).toBeLessThan(0.1);
    }
    // the blend passes through the values in between
    expect(blend.some((b) => b > -0.5 && b < 0.5)).toBe(true);
  });

  it('keeps the lead through a short swerve the other way', () => {
    const m = createMotion();
    const state = { gait: 'canter', speed: 6, turnRate: -0.5 };
    for (let i = 0; i < 180; i++) stepMotion(m, FRAME, state);
    state.turnRate = 0.8;
    for (let i = 0; i < 12; i++) stepMotion(m, FRAME, state); // 0.2 s
    state.turnRate = -0.5;
    for (let i = 0; i < 180; i++) stepMotion(m, FRAME, state);
    expect(m.lead).toBe(1);
  });

  it('the legs follow the new lead: left lead RH → LH+RF → LF, right lead LH → RH+LF → RF', () => {
    const m = createMotion();
    const state = { gait: 'canter', speed: 6, turnRate: -0.5 };
    for (let i = 0; i < 180; i++) stepMotion(m, FRAME, state);
    state.turnRate = 0.6;
    for (let i = 0; i < 4 * 60; i++) stepMotion(m, FRAME, state);
    expect(m.lead).toBe(-1);
    const seq = [];
    for (let i = 0; i < 2 * 60; i++) seq.push(...stepMotion(m, FRAME, state));
    const LF = 0;
    const RF = 1;
    const LH = 2;
    const RH = 3;
    const i = seq.indexOf(LH);
    expect([seq[i + 1], seq[i + 2]].sort()).toEqual([RH, LF].sort());
    expect(seq[i + 3]).toBe(RF);
  });
});

// --- the whole animation: bones, hooves and body of the real horse --------------------------

const LEG_BONES = /^(L|R)(scapula|humerus|forearm|fcannon|fpastern|femur|tibia|hcannon|hpastern)$/;
const PASTERN = ['Lfpastern', 'Rfpastern', 'Lhpastern', 'Rhpastern'];
const HOOF_REST = [REST.front.hoof, REST.front.hoof, REST.hind.hoof, REST.hind.hoof].map(
  (h, i) => new THREE.Vector3(i % 2 === 0 ? h[0] : -h[0], h[1], h[2]),
);
const FETLOCK_REST = [
  REST.front.fetlock,
  REST.front.fetlock,
  REST.hind.fetlock,
  REST.hind.fetlock,
].map((h, i) => new THREE.Vector3(i % 2 === 0 ? h[0] : -h[0], h[1], h[2]));

// Bones that carry no leg may turn this much per frame, leg joints more (the canter folds the
// carpus by 100° in a tenth of a second). The old animation reached 1.0-1.2 rad on leg joints.
// A leg joint also may not change its angular velocity by more than LEG_ACCEL per frame: a joint
// that speeds up or stops dead within a frame is a visible pop even when its step is small (the
// first difference alone let one-frame V-kinks of 0.5-0.8 rad through).
const ROT_LIMIT = { body: 0.15, leg: 0.35 };
const LEG_ACCEL = 0.3; // rad, change of the per-frame rotation from one frame to the next
const ROOT_STEP = 0.08; // m per frame (the take-off rotates the body about the hind feet)
const HOOF_STEP = 0.2; // m per frame, hoof relative to the body
const IK_SLIDE = 0.004; // m per frame, planted hoof relative to the ground (IK error)

/**
 * Largest per-frame rotation (first difference) and largest change of that rotation from one
 * frame to the next (second difference) of leg joints. Leg bones turn about X only, so the signed
 * rotation.x is the angle.
 */
function createLegTracker() {
  const prev = new Map();
  const worst = { step: { v: 0, w: '' }, accel: { v: 0, w: '' } };
  return {
    worst,
    sample(bone, tag) {
      const x = bone.rotation.x;
      const p = prev.get(bone);
      if (!p) {
        prev.set(bone, { x, d: null });
        return;
      }
      const d = x - p.x;
      if (Math.abs(d) > worst.step.v) worst.step = { v: Math.abs(d), w: `${bone.name} ${tag}` };
      if (p.d !== null && Math.abs(d - p.d) > worst.accel.v) {
        worst.accel = { v: Math.abs(d - p.d), w: `${bone.name} ${tag}` };
      }
      p.x = x;
      p.d = d;
    },
  };
}

function trackHorse(name) {
  const horse = createHorse({ quality: 'low', rider: false, rng: createRng(3) });
  const m = created[created.length - 1];
  const bones = [];
  horse.object.traverse((o) => o.isBone && bones.push(o));
  const byName = Object.fromEntries(bones.map((b) => [b.name, b]));
  const prevQ = new Map();
  const worst = { body: { v: 0, w: '' } };
  const legs = createLegTracker();
  const prevHoof = [null, null, null, null];
  const hoof = { step: 0, slide: 0, where: '' };
  const root = { prev: null, step: 0, where: '' };
  const v = new THREE.Vector3();
  let z = 0;
  runScript(
    SEQUENCES[name],
    (dt, state) => {
      horse.update(dt, state);
      z += (state.speed || 0) * dt;
    },
    (state, step, t) => {
      const tag = `${name}#${step}@${t.toFixed(2)}s`;
      for (const b of bones) {
        if (LEG_BONES.test(b.name)) {
          legs.sample(b, tag);
          continue;
        }
        const q = prevQ.get(b);
        // the eyelids close within three frames (a blink)
        if (q && !b.name.endsWith('lid')) {
          const angle = 2 * Math.acos(Math.min(1, Math.abs(q.dot(b.quaternion))));
          if (angle > worst.body.v) worst.body = { v: angle, w: `${b.name} ${tag}` };
        }
        prevQ.set(b, b.quaternion.clone());
      }
      // planted hooves are only comparable on a straight line (a turn moves them in the horse frame)
      const poseFree =
        m.jumpWeight < 0.01 &&
        m.hopWeight < 0.01 &&
        m.stopWeight < 0.01 &&
        m.runoutWeight < 0.01 &&
        Math.abs(state.turnRate || 0) < 0.3;
      for (let k = 0; k < 4; k++) {
        v.copy(HOOF_REST[k]).sub(FETLOCK_REST[k]).applyMatrix4(byName[PASTERN[k]].matrixWorld);
        const planted = m.legs[k].y === 0 && !m.legs[k].squaring && poseFree;
        if (prevHoof[k]) {
          const d = Math.hypot(v.z - prevHoof[k].z, v.y - prevHoof[k].y);
          if (d > hoof.step) hoof.step = d;
          if (planted && prevHoof[k].planted) {
            const s = Math.abs(v.z + z - prevHoof[k].world);
            if (s > hoof.slide) {
              hoof.slide = s;
              hoof.where = `leg ${k} ${tag}`;
            }
          }
        }
        prevHoof[k] = { z: v.z, y: v.y, world: v.z + z, planted };
      }
      const p = byName.root.position;
      if (root.prev) {
        const d = p.distanceTo(root.prev);
        if (d > root.step) {
          root.step = d;
          root.where = tag;
        }
      }
      root.prev = p.clone();
    },
  );
  horse.dispose();
  return { worst, legs: legs.worst, hoof, root };
}

describe('full animation continuity at 60 fps (bones of the real horse)', () => {
  for (const name of Object.keys(SEQUENCES)) {
    it(`${name}: bones, body and hooves move smoothly, planted hooves stay put`, () => {
      const { worst, legs, hoof, root } = trackHorse(name);
      expect(worst.body.v, `body bone ${worst.body.w}`).toBeLessThanOrEqual(ROT_LIMIT.body);
      expect(legs.step.v, `leg joint ${legs.step.w}`).toBeLessThanOrEqual(ROT_LIMIT.leg);
      expect(legs.accel.v, `leg joint ${legs.accel.w}`).toBeLessThanOrEqual(LEG_ACCEL);
      expect(root.step, `root at ${root.where}`).toBeLessThanOrEqual(ROOT_STEP);
      expect(hoof.step).toBeLessThanOrEqual(HOOF_STEP);
      expect(hoof.slide, `hoof ${hoof.where}`).toBeLessThanOrEqual(IK_SLIDE);
    });
  }
});

// --- jumps from every gait, with the real height of the horse and the rider on top ---------------

// The same limits as in the gaits: before the soft reach of the IK, the landing (the hoof target
// comes back into reach and the straight leg bends in one frame) turned the forearm by 0.8-0.9
// rad, and the quick tuck-in of the take-off by 0.5 rad in one frame and then not at all.
const JUMP_LEG_LIMIT = ROT_LIMIT.leg;
const JUMP_RIDER_LIMIT = 0.15; // the rider's joints move calmly through the whole jump
const RIDER_BONES = /^(pelvis|spine|chest|neck|head|(L|R)(upperArm|forearm|hand|thigh|shin|foot))$/;

function jumpFrames(gait, speed, height, total = 1.0) {
  const lead = 120;
  const n = Math.round(total / FRAME);
  const frames = [];
  for (let i = 0; i < lead; i++) frames.push({ gait, speed, y: 0, jump: null });
  for (let i = 0; i < n; i++) {
    const s = i / n;
    let phase = 'takeoff';
    let progress = s / 0.2;
    if (s >= 0.75) {
      phase = 'landing';
      progress = (s - 0.75) / 0.25;
    } else if (s >= 0.2) {
      phase = 'flight';
      progress = (s - 0.2) / 0.55;
    }
    const jump = { phase, progress: Math.min(1, progress) };
    frames.push({ gait, speed, y: height * Math.sin(Math.PI * s), jump });
  }
  for (let i = 0; i < 60; i++) frames.push({ gait, speed, y: 0, jump: null });
  return { frames, from: lead - 1 };
}

describe('jumps from every gait: no pop in the take-off or the landing', () => {
  const cases = [
    ['walk', 1.2, 1.2],
    ['trot', 3.5, 0.6],
    ['trot', 4.5, 1.2],
    ['canter', 5.2, 0.6],
    ['canter', 5.2, 1.2],
  ];
  for (const [gait, speed, height] of cases) {
    it(`${gait} at ${speed} m/s over ${height} m: legs and rider stay smooth`, () => {
      const horse = createHorse({ quality: 'low', rng: createRng(3) });
      const riderObjects = new Set();
      horse.rider.object.traverse((o) => riderObjects.add(o));
      const bones = [];
      horse.object.traverse((o) => o.isBone && bones.push(o));
      const prevQ = new Map();
      const worst = { rider: { v: 0, w: '' } };
      const legs = createLegTracker();
      const { frames, from } = jumpFrames(gait, speed, height);
      frames.forEach((state, i) => {
        horse.update(FRAME, { turnRate: 0, hop: null, refusal: null, ...state });
        for (const b of bones) {
          const isRider = riderObjects.has(b);
          if (!isRider && LEG_BONES.test(b.name)) {
            if (i >= from) legs.sample(b, `frame ${i - from}`);
            continue;
          }
          const q = prevQ.get(b);
          prevQ.set(b, b.quaternion.clone());
          if (!q || i < from || !isRider || !RIDER_BONES.test(b.name)) continue;
          const angle = 2 * Math.acos(Math.min(1, Math.abs(q.dot(b.quaternion))));
          if (angle > worst.rider.v) worst.rider = { v: angle, w: `${b.name} frame ${i - from}` };
        }
      });
      horse.dispose();
      expect(legs.worst.step.v, `leg joint ${legs.worst.step.w}`).toBeLessThanOrEqual(
        JUMP_LEG_LIMIT,
      );
      expect(legs.worst.accel.v, `leg joint ${legs.worst.accel.w}`).toBeLessThanOrEqual(LEG_ACCEL);
      expect(worst.rider.v, `rider bone ${worst.rider.w}`).toBeLessThanOrEqual(JUMP_RIDER_LIMIT);
    });
  }
});
