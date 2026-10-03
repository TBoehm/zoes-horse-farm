// Procedural 3D horse (three.js adapter). Pure computations live in motion.js, gaits.js, poses.js,
// ik.js, seat.js and coats.js; this file turns them into bone rotations.
//
//   const horse = createHorse({ coat: 'bay', marking: 'star', quality: 'medium' });
//   scene.add(horse.object);          // origin on the ground below the forelegs, faces +Z
//   horse.update(dt, sim.horse);      // gait, jump, hop and refusal animation
//
// Height: the integration puts sim.horse.y on object.position.y; the model does not apply y again
// but keeps stance hooves on the ground (ground = −y in model space).
import * as THREE from 'three';
import { createRider } from '../rider.js';
import { normalizeAppearance } from './coats.js';
import { bitRingPoint, buildBodyGeometry, buildTackGeometry, reinRestPoint } from './geometry.js';
import { angD, hindSweep, makeFrontRig, makeHindRig, solveFront, solveHind } from './ik.js';
import {
  applyAppearance,
  createCoatMaterial,
  createCoatUniforms,
  createVertexColorMaterial,
} from './material.js';
import { clamp, lerp } from './math.js';
import { createMotion, neckCarriage, stepMotion } from './motion.js';
import { JUMP_KEYS, LEG_OFFSET, POSE_KEYS, POSE_SIZE, STOP_POSE, samplePoses } from './poses.js';
import { EAR_ANCHOR, REST, SADDLE_SEAT, createSkeletonBones } from './skeleton.js';
import { createReins } from './reins.js';

export { COATS, MARKINGS, DEFAULT_APPEARANCE } from './coats.js';
import { GRAPHICS_LEVELS } from '../../../application/graphics-levels.js';

export const QUALITY_LEVELS = GRAPHICS_LEVELS;

/** Distance body centre → simulation reference point (ground below the forelegs). */
export const ORIGIN_OFFSET_Z = REST.front.hoof[2];

const LEG_PREFIX = ['L', 'R', 'L', 'R'];
const PI = POSE_KEYS.reduce((o, k, i) => ((o[k] = i), o), {});

function rigPoint(a, parent) {
  return { y: a[1] - parent[1], z: a[2] - parent[2] };
}

export function createHorse(options = {}) {
  const {
    quality: q0 = 'medium',
    rider: withRider = true,
    origin = 'front', // 'front' (sim reference point) or 'center' (e.g. menu preview)
  } = options;
  let level = QUALITY_LEVELS.includes(q0) ? q0 : 'medium';
  let appearance = normalizeAppearance(options);

  const object = new THREE.Group();
  object.name = 'horse';
  const rig = new THREE.Group();
  rig.name = 'horse-rig';
  rig.position.z = origin === 'center' ? 0 : -ORIGIN_OFFSET_Z;
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
  const tack = new THREE.SkinnedMesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial());
  tack.name = 'horse-tack';
  for (const m of [body, tack]) {
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
    rider = createRider({ quality: level });
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
  const reins = createReins();
  rig.add(reins.mesh);
  const bitLocal = [1, -1].map((s) => bitRingPoint(s).sub(new THREE.Vector3(...REST.head)));
  const restLocal = [1, -1].map((s) => reinRestPoint(s).sub(new THREE.Vector3(...REST.spineFront)));

  function applyQuality() {
    body.geometry.dispose();
    tack.geometry.dispose();
    body.material.dispose();
    tack.material.dispose();
    body.geometry = buildBodyGeometry(skel.index, level);
    tack.geometry = buildTackGeometry(skel.index, level);
    body.material = createCoatMaterial(level, uniforms);
    tack.material = createVertexColorMaterial(level, 0.55);
    reins.setMaterial(tack.material);
    body.bind(skeleton, body.matrixWorld);
    tack.bind(skeleton, tack.matrixWorld);
    const shadows = level !== 'low';
    for (const m of [body, tack, reins.mesh]) m.castShadow = shadows;
    rider?.setQuality(level);
  }
  // bind matrices in the rest pose (object not transformed yet)
  object.updateMatrixWorld(true);
  applyQuality();

  const motion = createMotion();
  const pose = new Array(POSE_SIZE).fill(0);
  const tmpPose = new Array(POSE_SIZE).fill(0);
  const mFront = new THREE.Matrix4();
  const mRear = new THREE.Matrix4();
  const inv = new THREE.Matrix4();
  const v3 = new THREE.Vector3();
  const down = new THREE.Vector3();
  const rotOut = new Array(5);
  const qObj = new THREE.Quaternion();
  const qHead = new THREE.Quaternion();
  const qWant = new THREE.Quaternion();
  const eX = new THREE.Euler();
  const mInvRig = new THREE.Matrix4();
  const tmpA = new THREE.Vector3();
  const tmpB = new THREE.Vector3();

  const api = {
    object,
    earAnchor,
    onFootfall: null,
    rider,
    get quality() {
      return level;
    },
    get appearance() {
      return { ...appearance };
    },
    /** Internal motion state (read-only; tests/debug). */
    motion,
    update,
    setAppearance(a) {
      appearance = applyAppearance(uniforms, { ...appearance, ...a });
    },
    setQuality(l) {
      if (!QUALITY_LEVELS.includes(l) || l === level) return;
      level = l;
      applyQuality();
    },
    dispose() {
      body.geometry.dispose();
      tack.geometry.dispose();
      body.material.dispose();
      tack.material.dispose();
      reins.dispose();
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
    const pivotZ = W > 1e-4 ? pose[PI.pivot] / W : 0;
    const G = 1 - W; // gait share

    // --- body ----------------------------------------------------------------------------
    const breath = Math.sin(t * 2 * Math.PI * 0.22);
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
    const lazy = m.weights.halt * (0.05 * Math.sin(t * 0.37) + 0.03 * Math.sin(t * 0.91 + 1));
    const neck =
      (neckCarriage(m.weights) + m.body.neck + lazy) * G +
      pose[PI.neck] +
      -pitch * 0.35 * G +
      m.runoutWeight * -0.1;
    B.neck1.rotation.set(neck * 0.3, turnBend * 0.33, 0);
    B.neck2.rotation.set(neck * 0.35, turnBend * 0.33, 0);
    B.neck3.rotation.set(neck * 0.35, turnBend * 0.3, 0);
    B.head.rotation.set(
      pose[PI.head] + m.body.neck * 0.3 * G - lazy * 0.5,
      turnBend * 0.2 + m.weights.halt * 0.08 * Math.sin(t * 0.23),
      0,
    );
    const flick = (k) => {
      const s = Math.sin(t * (0.7 + 0.13 * k) + k * 2.1);
      return s > 0.96 ? (s - 0.96) * 12 : 0;
    };
    const earBack = m.stopWeight * 0.6;
    B.Lear.rotation.set(
      -0.1 - earBack - flick(1) * 0.5 * m.weights.halt,
      0.15 * Math.sin(t * 0.3),
      0,
    );
    B.Rear.rotation.set(
      -0.1 - earBack - flick(2) * 0.5 * m.weights.halt,
      -0.15 * Math.sin(t * 0.27 + 1),
      0,
    );
    const motionLift = m.weights.trot * 0.15 + m.weights.canter * 0.35;
    const swishAmp =
      0.05 + 0.25 * m.weights.halt * Math.max(0, Math.sin(t * 0.43) - 0.7) * 3 + 0.04 * motionLift;
    const tailLift = motionLift + pose[PI.tail];
    for (let k = 0; k < 5; k++) {
      const tb = B[`tail${k + 1}`];
      tb.rotation.set(
        (k === 0 ? 0.15 + tailLift * 0.7 : tailLift * (0.25 - k * 0.04)) +
          0.03 * Math.sin(2 * Math.PI * m.phi * 2 - k),
        0,
        swishAmp * Math.sin(t * 2.2 - k * 0.7) + turnBend * 0.2,
      );
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
        const scap = clamp((hz - rigL.H.z) * 0.38, -0.25, 0.3);
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
      rider.update(dt, state, {
        weights: m.weights,
        phi: m.phi,
        lead: m.lead,
        jumpWeight: m.jumpWeight,
        jumpJ: m.jumpJ,
        hopWeight: m.hopWeight,
        stopWeight: m.stopWeight,
        pitch,
        neck,
        speed: m.speed,
      });
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
    for (let s = 0; s < 2; s++) {
      const bit = tmpA.copy(bitLocal[s]).applyMatrix4(B.head.matrixWorld).applyMatrix4(mInvRig);
      let hand;
      if (handTargets) {
        hand = tmpB.setFromMatrixPosition(handTargets[s].matrixWorld).applyMatrix4(mInvRig);
      } else {
        hand = tmpB.copy(restLocal[s]).applyMatrix4(B.spineFront.matrixWorld).applyMatrix4(mInvRig);
      }
      reins.setRein(s, bit, hand, handTargets ? 0.06 : 0.02);
    }
    reins.commit();

    if (falls.length && api.onFootfall) {
      for (const leg of falls) api.onFootfall(state.gait, leg);
    }
  }

  return api;
}
