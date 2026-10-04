// Procedural 3D horse (three.js adapter). Pure computations live in motion.js (+ legs.js, gaits.js,
// poses.js), ik.js, life.js (hair springs, blink, breathing; + spring.js, schedule.js), seat.js and
// coats.js; this file turns them into bone rotations and shader uniforms.
//
//   const horse = createHorse({ coat: 'bay', marking: 'star', quality: 'medium' });
//   scene.add(horse.object);          // origin on the ground below the forelegs, faces +Z
//   horse.update(dt, sim.horse);      // gait, jump, hop and refusal animation
//   horse.footfalls                   // footfalls of the last update (for dust and sound), see below
//
// Footfalls: after update(), `horse.footfalls` lists what touched the ground in this frame (the
// array is reused by the next update): { kind: 'step' | 'landing', leg: 0..3 (LF, RF, LH, RH),
// gait, strength: 0..1, x, y, z } with the contact point in the local frame of horse.object
// (y = the ground level there). 'landing' events come in pairs per jump: forelegs (strength 1) and
// hindlegs (0.65). horse.footfallWorld(event, out) converts a point to world space with the
// CURRENT position and heading of horse.object, so call it after the object has been placed.
// `onFootfall(gait, leg)` is still called for 'step' events (sound).
//
// Height: the integration puts sim.horse.y on object.position.y; the model does not apply y again
// but keeps stance hooves on the ground (ground = −y in model space).
import * as THREE from 'three';
import { createRider } from '../rider.js';
import { createRng } from '../textures.js';
import { normalizeAppearance } from './coats.js';
import { bitRingPoint, buildBodyGeometry, buildTackGeometry, reinRestPoint } from './geometry.js';
import {
  angD,
  hindSweep,
  makeFrontRig,
  makeHindRig,
  scapulaSlide,
  solveFront,
  solveHind,
} from './ik.js';
import {
  applyAppearance,
  createCoatMaterial,
  createCoatUniforms,
  createVertexColorMaterial,
} from './material.js';
import { clamp, lerp } from './math.js';
import { createLife, gestureHead, stepLife } from './life.js';
import { createMotion, neckCarriage, stepMotion } from './motion.js';
import { JUMP_KEYS, LEG_OFFSET, POSE_KEYS, POSE_SIZE, STOP_POSE, samplePoses } from './poses.js';
import { EAR_ANCHOR, EYE, REST, SADDLE_SEAT, createSkeletonBones } from './skeleton.js';
import { createReins } from './reins.js';
import { GRAPHICS_LEVELS } from '../../../application/graphics-levels.js';
import { releaseNow } from '../resilience.js';

/** Distance body centre → simulation reference point (ground below the forelegs). */
const ORIGIN_OFFSET_Z = REST.front.hoof[2];

const LEG_PREFIX = ['L', 'R', 'L', 'R'];

// Dust strength of a footfall per gait (a landing after a jump is always stronger)
const STEP_STRENGTH = { walk: 0.12, trot: 0.5, canter: 0.8, back: 0.1 };
// the free hair on the right side of the neck cannot be pressed into the neck by more than this (rad)
const MANE_PRESS = 0.1;
// … and it cannot swing further along the neck than this (rad)
const MANE_SWING = 0.6;
// Grazing (state.graze 0..1): neck (per neck bone) and head down to the grass, chewing now and
// then; the muzzle ends up about 0.15 m above the ground, 0.55 m in front of the forefeet
const GRAZE = Object.freeze({ neck: [1.0, 0.5, 0.15], head: -0.85, chew: 0.03, chewRate: 1.7 });
const GRAZE_NECK_SUM = GRAZE.neck[0] + GRAZE.neck[1] + GRAZE.neck[2];
const PI = POSE_KEYS.reduce((o, k, i) => ((o[k] = i), o), {});

function rigPoint(a, parent) {
  return { y: a[1] - parent[1], z: a[2] - parent[2] };
}

/** Occasional ear flick (0 most of the time), k = ear index. */
function earFlick(t, k) {
  const s = Math.sin(t * (0.7 + 0.13 * k) + k * 2.1);
  return s > 0.96 ? (s - 0.96) * 12 : 0;
}

export function createHorse(options = {}) {
  // `release` frees GPU objects that are replaced on a quality change (see createGpuEpoch)
  const {
    quality: q0 = 'medium',
    rider: withRider = true,
    // saddle, bridle and reins; a grazing horse has none (see grazing.js)
    tack: withTack = true,
    release = releaseNow,
    rng = createRng(options.seed ?? 7),
    // cast shadows (default: from medium up); the horses of the paddock do not
    castShadow = null,
  } = options;
  let level = GRAPHICS_LEVELS.includes(q0) ? q0 : 'medium';
  let appearance = normalizeAppearance(options);

  const object = new THREE.Group();
  object.name = 'horse';
  const rig = new THREE.Group();
  rig.name = 'horse-rig';
  rig.position.z = -ORIGIN_OFFSET_Z;
  object.add(rig);

  const skel = createSkeletonBones();
  const B = skel.bones;
  rig.add(skel.root);
  object.updateMatrixWorld(true);
  const skeleton = new THREE.Skeleton(skel.list);

  const uniforms = createCoatUniforms();
  applyAppearance(uniforms, appearance);
  const body = new THREE.SkinnedMesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial());
  body.name = 'horse-body';
  // bandages are part of the tack geometry, so a horse without tack has none
  const tack = withTack
    ? new THREE.SkinnedMesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial())
    : null;
  if (tack) tack.name = 'horse-tack';
  for (const m of [body, tack]) {
    if (!m) continue;
    m.frustumCulled = false;
    rig.add(m);
  }

  // leg rigs in the local frames of spineFront / spineRear
  const F = REST.front;
  const H = REST.hind;
  const frontRig = makeFrontRig(
    rigPoint(F.scapula, REST.spineFront),
    rigPoint(F.shoulder, REST.spineFront),
    rigPoint(F.elbow, REST.spineFront),
    rigPoint(F.knee, REST.spineFront),
    rigPoint(F.fetlock, REST.spineFront),
    rigPoint(F.hoof, REST.spineFront),
  );
  const hindRig = makeHindRig(
    rigPoint(H.hip, REST.spineRear),
    rigPoint(H.stifle, REST.spineRear),
    rigPoint(H.hock, REST.spineRear),
    rigPoint(H.fetlock, REST.spineRear),
    rigPoint(H.hoof, REST.spineRear),
  );
  const legBones = [0, 1, 2, 3].map((i) => {
    const p = LEG_PREFIX[i];
    return i < 2
      ? [B[`${p}scapula`], B[`${p}humerus`], B[`${p}forearm`], B[`${p}fcannon`], B[`${p}fpastern`]]
      : [B[`${p}femur`], B[`${p}tibia`], B[`${p}hcannon`], B[`${p}hpastern`]];
  });
  const legX = [F.hoof[0], -F.hoof[0], H.hoof[0], -H.hoof[0]];
  const legZ = [F.hoof[2], F.hoof[2], H.hoof[2], H.hoof[2]];

  // rider on the saddle
  let rider = null;
  if (withRider) {
    rider = createRider({ quality: level, release });
    rider.object.position.set(
      SADDLE_SEAT.x - REST.root[0],
      SADDLE_SEAT.y - REST.root[1],
      SADDLE_SEAT.z - REST.root[2],
    );
    B.root.add(rider.object);
  }

  // ear anchor (rider view), looking forward
  const earAnchor = new THREE.Object3D();
  earAnchor.name = 'horse-ear-anchor';
  const earLocal = EAR_ANCHOR().sub(new THREE.Vector3(...REST.head));
  earAnchor.position.copy(earLocal);
  B.head.add(earAnchor);

  // reins (dynamic, bit → hands or neck)
  const reins = withTack ? createReins() : null;
  if (reins) rig.add(reins.mesh);
  const bitLocal = [1, -1].map((s) => bitRingPoint(s).sub(new THREE.Vector3(...REST.head)));
  const restLocal = [1, -1].map((s) => reinRestPoint(s).sub(new THREE.Vector3(...REST.spineFront)));

  function applyQuality() {
    release(body.geometry);
    release(body.material);
    body.geometry = buildBodyGeometry(skel.index, level);
    body.material = createCoatMaterial(level, uniforms);
    // Always bind with the rest-pose matrix: the object may have been moved since (quality change)
    body.bind(skeleton, bindMatrix);
    if (tack) {
      release(tack.geometry);
      release(tack.material);
      tack.geometry = buildTackGeometry(skel.index, level);
      tack.material = createVertexColorMaterial(level, 0.55);
      reins.setMaterial(tack.material);
      tack.bind(skeleton, bindMatrix);
    }
    const shadows = castShadow ?? level !== 'low';
    for (const m of [body, tack, reins?.mesh]) if (m) m.castShadow = shadows;
    rider?.setQuality(level);
  }
  // bind matrices in the rest pose (object not transformed yet)
  object.updateMatrixWorld(true);
  const bindMatrix = body.matrixWorld.clone();
  applyQuality();

  const motion = createMotion({ rng });
  const life = createLife({ rng });
  const footfalls = [];
  const headGesture = { yaw: 0, pitch: 0, neck: 0 };
  const maneBones = [1, 2, 3, 4, 5].map((k) => B[`mane${k}`]);
  const lidBones = [B.Llid, B.Rlid];
  const qTmp = new THREE.Quaternion();
  const eTmp = new THREE.Euler();
  const pose = new Array(POSE_SIZE).fill(0);
  const tmpPose = new Array(POSE_SIZE).fill(0);
  const mFront = new THREE.Matrix4();
  const mRear = new THREE.Matrix4();
  const inv = new THREE.Matrix4();
  const v3 = new THREE.Vector3();
  const down = new THREE.Vector3();
  const rotOut = new Array(5);
  const riderCtx = {}; // reused every frame
  const tailBones = [1, 2, 3, 4, 5].map((k) => B[`tail${k}`]);
  const qObj = new THREE.Quaternion();
  const qHead = new THREE.Quaternion();
  const qWant = new THREE.Quaternion();
  const eX = new THREE.Euler();
  const mInvRig = new THREE.Matrix4();
  const tmpA = new THREE.Vector3();
  const tmpB = new THREE.Vector3();
  const objMatrix = new THREE.Matrix4();

  const api = {
    object,
    earAnchor,
    onFootfall: null,
    rider,
    /** The motion state (read-only; for tests and debugging). */
    motion,
    footfalls,
    update,
    /** Contact point of a footfall event in world space (uses the current object transform). */
    footfallWorld(ev, out = new THREE.Vector3()) {
      objMatrix.compose(object.position, object.quaternion, object.scale);
      return out.set(ev.x, ev.y, ev.z).applyMatrix4(objMatrix);
    },
    setAppearance(a) {
      appearance = applyAppearance(uniforms, { ...appearance, ...a });
    },
    setQuality(l) {
      if (!GRAPHICS_LEVELS.includes(l) || l === level) return;
      level = l;
      applyQuality();
    },
    dispose() {
      body.geometry.dispose();
      tack?.geometry.dispose();
      body.material.dispose();
      tack?.material.dispose();
      reins?.dispose();
      rider?.dispose();
      skeleton.dispose();
      object.removeFromParent();
    },
  };

  function addPose(src, w) {
    if (w <= 1e-4) return 0;
    for (let i = 0; i < POSE_SIZE; i++) pose[i] += src[i] * w;
    return w;
  }

  function update(dtIn, state = {}) {
    const dt = clamp(dtIn || 0, 0, 0.1);
    const m = motion;
    const falls = stepMotion(m, dt, state);
    const t = m.time;
    const y = state.y || 0;

    // --- blend poses (jump, hop, refusal) ------------------------------------------------
    pose.fill(0);
    let W = 0;
    if (m.jumpWeight > 1e-3) W += addPose(samplePoses(JUMP_KEYS, m.jumpJ, tmpPose), m.jumpWeight);
    if (m.hopWeight > 1e-3) {
      samplePoses(JUMP_KEYS, m.hopJ, tmpPose);
      const hw = m.hopWeight * (1 - m.jumpWeight);
      // hop = small jump: 40 % pose amplitude
      for (let i = 0; i < POSE_SIZE; i++) if (i !== PI.pivot) tmpPose[i] *= 0.4;
      W += addPose(tmpPose, hw);
    }
    if (m.stopWeight > 1e-3) W += addPose(STOP_POSE, m.stopWeight * (1 - Math.min(1, W)));
    if (W > 1) {
      for (let i = 0; i < POSE_SIZE; i++) pose[i] /= W;
      W = 1;
    }
    // pivot of the body pitch: weighted like the rest of the pose, so it never jumps with W
    const pivotZ = pose[PI.pivot];
    const G = 1 - W; // gait share

    // --- body ----------------------------------------------------------------------------
    const breath = life.breath; // breathing cycle of the previous step (one frame behind: fine)
    const pitch = m.body.pitch * G + pose[PI.pitch];
    const lift = m.body.bob * G + pose[PI.dy] + m.weights.halt * 0.004 * breath;
    const roll = m.lean + m.body.roll * G + m.runoutWeight * m.runoutDir * -0.1;
    const root = B.root;
    const ry = REST.root[1] + lift;
    const dz0 = -pivotZ;
    // rotation about (y = 0, z = pivotZ)
    root.position.set(
      -Math.sin(roll) * REST.root[1],
      ry * Math.cos(pitch) - dz0 * Math.sin(pitch),
      pivotZ + ry * Math.sin(pitch) + dz0 * Math.cos(pitch),
    );
    root.rotation.set(pitch, 0, roll, 'XZY');
    const bend = pose[PI.bend];
    const turnBend = m.bend + m.runoutWeight * m.runoutDir * 0.4;
    B.spineFront.rotation.set(bend * 0.55, turnBend * 0.3, 0);
    B.spineRear.rotation.set(-bend * 0.45, -turnBend * 0.22, 0);
    B.belly.scale.set(1 + 0.014 * breath, 1 + 0.01 * breath, 1);

    // --- neck, head, ears, tail ----------------------------------------------------------
    gestureHead(m.gesture?.state, headGesture);
    const graze = clamp(state.graze || 0, 0, 1);
    const chew =
      graze *
      GRAZE.chew *
      Math.sin(t * 2 * Math.PI * GRAZE.chewRate) *
      (0.5 + 0.5 * Math.sin(t * 0.35));
    const lazy = m.weights.halt * (0.05 * Math.sin(t * 0.37) + 0.03 * Math.sin(t * 0.91 + 1));
    const neckPose =
      (neckCarriage(m.weights) + m.body.neck + lazy) * G +
      pose[PI.neck] +
      -pitch * 0.35 * G +
      m.runoutWeight * -0.1 +
      headGesture.neck;
    const neck = neckPose + graze * GRAZE_NECK_SUM;
    B.neck1.rotation.set(
      neckPose * 0.3 + graze * GRAZE.neck[0],
      turnBend * 0.33 + headGesture.yaw * 0.25,
      0,
    );
    B.neck2.rotation.set(
      neckPose * 0.35 + graze * GRAZE.neck[1],
      turnBend * 0.33 + headGesture.yaw * 0.3,
      0,
    );
    B.neck3.rotation.set(
      neckPose * 0.35 + graze * GRAZE.neck[2],
      turnBend * 0.3 + headGesture.yaw * 0.35,
      0,
    );
    B.head.rotation.set(
      pose[PI.head] +
        m.body.neck * 0.3 * G -
        lazy * 0.5 +
        headGesture.pitch +
        graze * GRAZE.head +
        chew,
      turnBend * 0.2 + m.weights.halt * 0.08 * Math.sin(t * 0.23) + headGesture.yaw * 0.6,
      0,
    );
    const earBack = m.stopWeight * 0.6;
    B.Lear.rotation.set(
      -0.1 - earBack - earFlick(t, 1) * 0.5 * m.weights.halt,
      0.15 * Math.sin(t * 0.3),
      0,
    );
    B.Rear.rotation.set(
      -0.1 - earBack - earFlick(t, 2) * 0.5 * m.weights.halt,
      -0.15 * Math.sin(t * 0.27 + 1),
      0,
    );
    const motionLift = m.weights.trot * 0.15 + m.weights.canter * 0.35;
    const tailLift = motionLift + pose[PI.tail];

    // --- life signs: spring-driven tail, mane and forelock, blink, nostrils -----------------
    const alert = state.jump || state.hop ? 1 : m.weights.canter * 0.6;
    stepLife(life, dt, {
      speed: state.speed || 0,
      turnRate: state.turnRate || 0,
      bodyY: y + lift,
      neckAngle: neck,
      weights: m.weights,
      alert,
    });
    for (let k = 0; k < 5; k++) {
      const seg = life.tail.segments[k];
      tailBones[k].rotation.set(
        (k === 0 ? 0.15 + tailLift * 0.7 : tailLift * (0.25 - k * 0.04)) + seg.pitch.x,
        0,
        seg.sway.x + turnBend * 0.2 * (k === 0 ? 1 : 0.5),
      );
    }
    for (let k = 0; k < maneBones.length; k++) {
      const seg = life.mane.segments[k];
      const bone = maneBones[k];
      // + sway presses the hair into the neck: only a little room for that
      const sway = seg.sway.x > 0 ? MANE_PRESS * Math.tanh(seg.sway.x / MANE_PRESS) : seg.sway.x;
      const pitch = MANE_SWING * Math.tanh(seg.pitch.x / MANE_SWING);
      qTmp.setFromEuler(eTmp.set(pitch, 0, sway));
      bone.quaternion.copy(bone.userData.restQuaternion).multiply(qTmp);
    }
    const fl = life.forelock.segments[0];
    B.forelock.rotation.set(fl.pitch.x, 0, fl.sway.x);
    uniforms.uFlare.value = life.flare;
    uniforms.uBlink.value = life.blinkClosure;
    for (const lid of lidBones) {
      lid.quaternion.setFromAxisAngle(lid.userData.axis, -life.blinkClosure * EYE.closeAngle);
    }

    // --- legs (IK) -----------------------------------------------------------------------
    root.updateMatrix();
    B.spineFront.updateMatrix();
    B.spineRear.updateMatrix();
    mFront.multiplyMatrices(root.matrix, B.spineFront.matrix);
    mRear.multiplyMatrices(root.matrix, B.spineRear.matrix);
    for (let leg = 0; leg < 4; leg++) {
      const front = leg < 2;
      const rigL = front ? frontRig : hindRig;
      inv.copy(front ? mFront : mRear).invert();
      const L = m.legs[leg];
      // gait target in rig space (ground = −y because the integration lifts the object by y)
      v3.set(legX[leg], -y + L.y, legZ[leg] + L.dz).applyMatrix4(inv);
      down.set(0, -1, 0).transformDirection(inv);
      const a = angD(down.z, down.y);
      const o = LEG_OFFSET + leg * 4;
      const hz = lerp(v3.z, rigL.H.z + pose[o] / Math.max(W, 1e-4), W);
      const hy = lerp(v3.y, rigL.H.y + pose[o + 1] / Math.max(W, 1e-4), W);
      const flex = L.flex * G + pose[o + 2];
      const pastFold = L.past * G + pose[o + 3];
      const past = rigL.t4 + a * (1 - 0.7 * W) + L.sink * 6 * G - pastFold;
      if (front) {
        const scap = scapulaSlide(hz - rigL.H.z);
        solveFront(rigL, hz, hy, past, flex, scap, rotOut);
        const bones = legBones[leg];
        for (let k = 0; k < 5; k++) bones[k].rotation.x = rotOut[k];
      } else {
        const sweep = hindSweep(rigL, hz, hy, past);
        const cannon = rigL.t3 + 0.85 * (sweep - a) + a - flex;
        solveHind(rigL, hz, hy, past, cannon, rotOut);
        const bones = legBones[leg];
        for (let k = 0; k < 4; k++) bones[k].rotation.x = rotOut[k];
      }
    }

    // --- rider ---------------------------------------------------------------------------
    if (rider) {
      riderCtx.weights = m.weights;
      riderCtx.phi = m.phi;
      riderCtx.lead = m.leadBlend;
      riderCtx.jumpWeight = m.jumpWeight;
      riderCtx.jumpJ = m.jumpJ;
      riderCtx.hopWeight = m.hopWeight;
      riderCtx.stopWeight = m.stopWeight;
      riderCtx.pitch = pitch;
      riderCtx.neck = neck;
      riderCtx.speed = m.speed;
      rider.update(dt, state, riderCtx);
    }

    // --- world matrices, ear anchor, reins -----------------------------------------------
    object.updateMatrixWorld(true);
    object.matrixWorld.decompose(tmpA, qObj, tmpB);
    B.head.matrixWorld.decompose(tmpA, qHead, tmpB);
    eX.set(pitch * 0.4, 0, -roll * 0.5);
    qWant.setFromEuler(eX);
    earAnchor.quaternion.copy(qHead.invert().multiply(qObj).multiply(qWant));
    earAnchor.updateMatrixWorld(true);

    mInvRig.copy(rig.matrixWorld).invert();
    const handTargets = rider ? rider.hands : null;
    for (let s = 0; reins && s < 2; s++) {
      const bit = tmpA.copy(bitLocal[s]).applyMatrix4(B.head.matrixWorld).applyMatrix4(mInvRig);
      let hand;
      if (handTargets) {
        hand = tmpB.setFromMatrixPosition(handTargets[s].matrixWorld).applyMatrix4(mInvRig);
      } else {
        hand = tmpB.copy(restLocal[s]).applyMatrix4(B.spineFront.matrixWorld).applyMatrix4(mInvRig);
      }
      reins.setRein(s, bit, hand, handTargets ? 0.06 : 0.02);
    }
    reins?.commit();

    // --- footfalls (dust, sound) ---------------------------------------------------------
    footfalls.length = 0;
    const groundY = -y || 0; // (−0 → 0)
    const strength = STEP_STRENGTH[state.gait] ?? 0.3;
    for (const leg of falls) {
      footfalls.push({
        kind: 'step',
        leg,
        gait: state.gait,
        strength,
        x: legX[leg],
        y: groundY,
        z: legZ[leg] + m.legs[leg].dz - ORIGIN_OFFSET_Z,
      });
    }
    pushLanding(0, m.landing.front, 0.12, state.gait, groundY);
    pushLanding(2, m.landing.hind, 0.1, state.gait, groundY);
    if (api.onFootfall) {
      for (const e of footfalls) if (e.kind === 'step') api.onFootfall(state.gait, e.leg);
    }
    return footfalls;
  }

  /** Landing of the legs first and first + 1 (strength 0 = nothing). */
  function pushLanding(first, power, reach, gait, groundY) {
    if (power <= 0) return;
    for (let leg = first; leg < first + 2; leg++) {
      footfalls.push({
        kind: 'landing',
        leg,
        gait,
        strength: power,
        x: legX[leg],
        y: groundY,
        z: legZ[leg] + reach - ORIGIN_OFFSET_Z,
      });
    }
  }

  return api;
}
