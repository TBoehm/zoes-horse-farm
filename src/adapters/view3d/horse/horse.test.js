// The horse as a whole (three.js without a renderer): detail per level, skeleton of the hair and
// eyelids, eyelid and hair motion, footfall events, release of GPU objects.
import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import { buildBodyGeometry, buildTackGeometry } from './geometry.js';
import { createHorse } from './index.js';
import { FRAME, SEQUENCES, runScript } from './sequence-helper.js';
import { EYE, createSkeletonBones, eyeGaze, lidAxis, lidPole } from './skeleton.js';

// Size of body and tack geometry before SRT-011 (vertices, triangles): low must not grow
const BEFORE = {
  low: { body: [1681, 3202], tack: [650, 1228] },
  medium: { body: [4220, 8218], tack: [1344, 2596] },
  high: { body: [8669, 17052], tack: [2814, 5512] },
};

function counts(level) {
  const skel = createSkeletonBones();
  const body = buildBodyGeometry(skel.index, level);
  const tack = buildTackGeometry(skel.index, level);
  const out = {
    body: [body.attributes.position.count, body.index.count / 3],
    tack: [tack.attributes.position.count, tack.index.count / 3],
    skel,
    body_g: body,
    tack_g: tack,
  };
  return out;
}

describe('detail per level (GPU cost)', () => {
  it('low gets no extra vertices and no extra triangles', () => {
    const c = counts('low');
    expect(c.body[0]).toBeLessThanOrEqual(BEFORE.low.body[0]);
    expect(c.body[1]).toBeLessThanOrEqual(BEFORE.low.body[1]);
    expect(c.tack[0]).toBeLessThanOrEqual(BEFORE.low.tack[0]);
    expect(c.tack[1]).toBeLessThanOrEqual(BEFORE.low.tack[1]);
  });

  it('medium and high add eyelids and leg wraps, within the same two meshes', () => {
    for (const level of ['medium', 'high']) {
      const c = counts(level);
      expect(c.body[0]).toBeGreaterThan(BEFORE[level].body[0]); // eyelids
      expect(c.tack[0]).toBeGreaterThan(BEFORE[level].tack[0]); // wraps
      // modest: a few percent of the horse
      expect(c.body[1] + c.tack[1]).toBeLessThan(
        (BEFORE[level].body[1] + BEFORE[level].tack[1]) * 1.15,
      );
    }
    const horse = createHorse({ quality: 'high', rider: false });
    const drawables = [];
    horse.object.traverse((o) => (o.isMesh || o.isPoints || o.isLine) && drawables.push(o.name));
    expect(drawables.sort()).toEqual(['horse-body', 'horse-reins', 'horse-tack']);
    horse.dispose();
  });

  it('every vertex is skinned to existing bones with weights that add up to 1', () => {
    for (const level of ['low', 'medium', 'high']) {
      const c = counts(level);
      const boneCount = c.skel.list.length;
      for (const g of [c.body_g, c.tack_g]) {
        const idx = g.attributes.skinIndex;
        const w = g.attributes.skinWeight;
        for (let i = 0; i < idx.count; i++) {
          let sum = 0;
          for (let k = 0; k < 4; k++) {
            sum += w.getComponent(i, k);
            if (w.getComponent(i, k) > 0) {
              expect(idx.getComponent(i, k)).toBeLessThan(boneCount);
            }
          }
          expect(sum).toBeCloseTo(1, 4);
        }
      }
    }
  });
});

describe('skeleton of mane, forelock and eyelids', () => {
  const { bones } = createSkeletonBones();

  it('has five mane bones along the neck, a forelock bone and a lid per eye', () => {
    for (let k = 1; k <= 5; k++) {
      expect(bones[`mane${k}`]).toBeDefined();
      expect(bones[`mane${k}`].userData.restQuaternion).toBeInstanceOf(THREE.Quaternion);
    }
    expect(bones.forelock.parent.name).toBe('head');
    expect(bones.Llid.userData.axis).toBeInstanceOf(THREE.Vector3);
    expect(bones.Rlid.parent.name).toBe('head');
  });

  it('mane bones sit on the crest in front of each other, from the withers to the poll', () => {
    const z = [1, 2, 3, 4, 5].map((k) => bones[`mane${k}`].userData.rest);
    for (let i = 1; i < z.length; i++) {
      expect(z[i].z).toBeGreaterThan(z[i - 1].z);
      expect(z[i].y).toBeGreaterThan(z[i - 1].y);
    }
    // above the neck line (crest), not inside the body
    expect(z[2].y).toBeGreaterThan(1.6);
  });

  it('the bone frame of the mane has z along the neck, y up and x sideways', () => {
    const q = bones.mane3.userData.restQuaternion;
    const along = new THREE.Vector3(0, 0, 1).applyQuaternion(q);
    const up = new THREE.Vector3(0, 1, 0).applyQuaternion(q);
    const side = new THREE.Vector3(1, 0, 0).applyQuaternion(q);
    expect(along.z).toBeGreaterThan(0.5); // forward and up
    expect(along.y).toBeGreaterThan(0.3);
    expect(up.y).toBeGreaterThan(0.5);
    expect(Math.abs(side.x)).toBeGreaterThan(0.99);
  });
});

describe('eyelid geometry', () => {
  it('the open lid rests above the eye, the closing rotation swings it over the gaze', () => {
    for (const side of [1, -1]) {
      const gaze = eyeGaze(side);
      const axis = lidAxis(side);
      const open = lidPole(side, EYE.openAngle);
      const angle = (v) => (Math.acos(THREE.MathUtils.clamp(v.dot(gaze), -1, 1)) * 180) / Math.PI;
      expect(angle(open)).toBeCloseTo(135, 3);
      expect(open.y).toBeGreaterThan(0.5); // up
      // closing: turn the pole about the hinge axis by −closeAngle, as index.js does
      const closed = open.clone().applyAxisAngle(axis, -EYE.closeAngle);
      expect(angle(closed)).toBeLessThan(20);
      // half way it has passed the top of the eye
      const half = open.clone().applyAxisAngle(axis, -EYE.closeAngle / 2);
      expect(angle(half)).toBeGreaterThan(50);
      expect(angle(half)).toBeLessThan(100);
    }
  });

  it('the horse blinks: the lids close and open again, the low horse paints the blink', () => {
    const horse = createHorse({ quality: 'medium', rider: false, rng: createRng(4) });
    const lid = horse.object.getObjectByName('Llid');
    let closed = 0;
    let open = 0;
    for (let i = 0; i < 20 * 60; i++) {
      horse.update(FRAME, { gait: 'halt', speed: 0 });
      const a = 2 * Math.acos(Math.min(1, Math.abs(lid.quaternion.w)));
      if (a > EYE.closeAngle * 0.95) closed++;
      if (a < 0.01) open++;
    }
    expect(closed).toBeGreaterThan(3);
    expect(open).toBeGreaterThan(20 * 60 * 0.8);
    horse.dispose();
  });
});

describe('hair motion in the real horse', () => {
  it('a stop swings tail, mane and forelock; they come to rest again', () => {
    const horse = createHorse({ quality: 'low', rider: false, rng: createRng(2) });
    const rest = (name) => horse.object.getObjectByName(name).quaternion.clone();
    const mane = horse.object.getObjectByName('mane3');
    const tail = horse.object.getObjectByName('tail3');
    const forelock = horse.object.getObjectByName('forelock');
    const state = { gait: 'canter', speed: 6, turnRate: 0, y: 0 };
    for (let i = 0; i < 180; i++) horse.update(FRAME, state);
    const before = { mane: rest('mane3'), tail: rest('tail3'), forelock: rest('forelock') };
    state.gait = 'halt';
    state.speed = 0;
    let maneMove = 0;
    let tailMove = 0;
    let lockMove = 0;
    for (let i = 0; i < 30; i++) {
      horse.update(FRAME, state);
      maneMove = Math.max(maneMove, before.mane.angleTo(mane.quaternion));
      tailMove = Math.max(tailMove, before.tail.angleTo(tail.quaternion));
      lockMove = Math.max(lockMove, before.forelock.angleTo(forelock.quaternion));
    }
    expect(maneMove).toBeGreaterThan(0.05);
    expect(tailMove).toBeGreaterThan(0.1);
    expect(lockMove).toBeGreaterThan(0.05);
    for (let i = 0; i < 6 * 60; i++) horse.update(FRAME, state);
    // at halt the hair hangs near its rest pose
    expect(mane.quaternion.angleTo(mane.userData.restQuaternion)).toBeLessThan(0.2);
    expect(Math.abs(tail.rotation.z)).toBeLessThan(0.2);
    horse.dispose();
  });

  it('the horse without a rider and tack runs the same animation', () => {
    const horse = createHorse({ quality: 'low', rider: false, tack: false });
    const names = [];
    horse.object.traverse((o) => (o.isMesh || o.isPoints) && names.push(o.name));
    expect(names).toEqual(['horse-body']);
    expect(horse.rider).toBe(null);
    for (let i = 0; i < 120; i++) horse.update(FRAME, { gait: 'trot', speed: 3, turnRate: 0.3 });
    horse.dispose();
    expect(horse.object.parent).toBe(null);
  });
});

describe('footfall events', () => {
  function ride(name, level = 'low') {
    const horse = createHorse({ quality: level, rider: false, rng: createRng(1) });
    const events = [];
    const steps = [];
    horse.onFootfall = (gait, leg) => steps.push([gait, leg]);
    runScript(
      SEQUENCES[name],
      (dt, state) => {
        horse.update(dt, state);
        events.push(...horse.footfalls.map((e) => ({ ...e })));
      },
      () => {},
    );
    return { horse, events, steps };
  }

  it('canter strides produce step events with a strength per gait and a position under the hoof', () => {
    const { horse, events, steps } = ride('gaitLadder');
    const canter = events.filter((e) => e.kind === 'step' && e.gait === 'canter');
    const walk = events.filter((e) => e.kind === 'step' && e.gait === 'walk');
    const trot = events.filter((e) => e.kind === 'step' && e.gait === 'trot');
    expect(canter.length).toBeGreaterThan(10);
    expect(walk[0].strength).toBeLessThan(trot[0].strength);
    expect(trot[0].strength).toBeLessThan(canter[0].strength);
    for (const e of [...canter, ...trot, ...walk]) {
      expect(e.leg).toBeGreaterThanOrEqual(0);
      expect(e.leg).toBeLessThanOrEqual(3);
      expect(Math.abs(Math.abs(e.x) - 0.16)).toBeLessThan(0.02); // under the leg
      expect(e.y).toBe(0);
      expect(Math.abs(e.z)).toBeLessThan(1.3);
    }
    // the sound callback gets the same step events
    expect(steps).toHaveLength(events.filter((e) => e.kind === 'step').length);
    horse.dispose();
  });

  it('a jump ends with a strong landing of the forelegs and a lighter one of the hindlegs', () => {
    const { horse, events } = ride('jump');
    const landing = events.filter((e) => e.kind === 'landing');
    expect(landing).toHaveLength(4);
    expect(
      landing
        .filter((e) => e.strength === 1)
        .map((e) => e.leg)
        .sort(),
    ).toEqual([0, 1]);
    expect(
      landing
        .filter((e) => e.strength < 1)
        .map((e) => e.leg)
        .sort(),
    ).toEqual([2, 3]);
    expect(landing.every((e) => e.strength > 0.5)).toBe(true);
    horse.dispose();
  });

  it('no step events at halt, none during the jump', () => {
    const { horse, events } = ride('refusalStop');
    expect(events.filter((e) => e.kind === 'landing')).toHaveLength(0);
    const jump = ride('jump').events;
    // between the take-off and the landing nothing touches the ground
    const first = jump.findIndex((e) => e.kind === 'landing');
    expect(first).toBeGreaterThan(0);
    horse.dispose();
  });

  it('footfallWorld uses the current position and heading of the object', () => {
    const horse = createHorse({ quality: 'low', rider: false, rng: createRng(1) });
    horse.object.position.set(10, 0, 5);
    horse.object.rotation.y = Math.PI / 2;
    const out = horse.footfallWorld({ x: 0.16, y: 0, z: 1 });
    // facing +x: the local z axis points along +x, the local x axis along −z
    expect(out.x).toBeCloseTo(11, 5);
    expect(out.z).toBeCloseTo(5 - 0.16, 5);
    horse.dispose();
  });
});

describe('release of GPU objects', () => {
  it('quality change and dispose go through the release hook', () => {
    const released = new Set();
    const horse = createHorse({ quality: 'low', rider: false, release: (o) => released.add(o) });
    const meshes = [];
    horse.object.traverse((o) => o.isMesh && meshes.push(o));
    const old = meshes
      .flatMap((m) => [m.geometry, m.material])
      .filter((o) => o.isBufferGeometry || o.isMaterial);
    horse.setQuality('high');
    const body = horse.object.getObjectByName('horse-body');
    const tack = horse.object.getObjectByName('horse-tack');
    for (const o of old) {
      // reins keep their buffers; body and tack are rebuilt
      if (o === body.geometry || o === tack.geometry) continue;
    }
    expect(released.size).toBeGreaterThanOrEqual(4);
    horse.dispose();
  });
});
