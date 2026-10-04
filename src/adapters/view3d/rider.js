// Procedural rider (three.js adapter): helmet, jacket, breeches, boots, gloves; one skinned mesh.
// Seat per gait: walk/halt sitting, trot rising (posting) in rhythm, canter light seat,
// jump two-point seat with crest release. Feet stay in the stirrups and hands on the reins via
// two-bone IK. Local frame: origin = seat point on the saddle, +Z forward, Y up.
import * as THREE from 'three';
import {
  Loft,
  MeshBuilder,
  chainWeights,
  curveFrames,
  ellipsoidData,
  lineFrames,
  oval,
  table,
  torusData,
} from './horse/loft.js';
import { smoothstep } from './horse/math.js';
import { createSeatFilter } from './horse/seat.js';
import { createVertexColorMaterial } from './horse/material.js';
import { releaseNow } from './resilience.js';
import {
  PONY_JOINTS,
  addChinStrap,
  addFace,
  addJacketDetails,
  addPonytail,
} from './rider-details.js';
import { createHeadLook, stepHeadLook } from './rider-look.js';
import { breathing, createPat, stepPat } from './rider-life.js';
import { limbReach } from './rider-reach.js';
import { PONY_SEGMENTS, createPonytail, stepPonytail } from './rider-ponytail.js';

const SIDES = [1, -1];
const PONY_NAMES = ['pony1', 'pony2', 'pony3'];
// the head takes most of the look, the neck the rest
const LOOK_SHARE = { neck: 0.4, head: 0.6 };
// right hand on the horse's neck for the pat after a jump (offsets to the rein position)
const PAT = { x: 0.04, y: -0.1, z: 0.24, tap: 0.03, lean: 0.5 };
// the ponytail ignores head jumps above this distance per frame (restart, teleport) in metres
const TELEPORT_DISTANCE = 3;
const ACCEL_FILTER = 22; // 1/s, low-pass on the head acceleration
const boneNames = (p) =>
  Object.fromEntries(
    ['thigh', 'shin', 'foot', 'upperArm', 'forearm', 'hand'].map((n) => [n, `${p}${n}`]),
  );
// precomputed: update() runs every frame
const LEFT_NAMES = boneNames('L');
const RIGHT_NAMES = boneNames('R');

// Bind pose (sitting) joint positions in rider space
const J = {
  base: [0, 0, 0],
  pelvis: [0, 0.1, -0.04],
  spine: [0, 0.28, -0.05],
  chest: [0, 0.46, -0.04],
  neck: [0, 0.63, -0.03],
  head: [0, 0.72, -0.02],
  shoulder: [0.17, 0.59, -0.03],
  elbow: [0.19, 0.36, 0.07],
  wrist: [0.09, 0.22, 0.33],
  hip: [0.1, 0.08, -0.02],
  knee: [0.27, -0.17, 0.28],
  ankle: [0.36, -0.56, 0.15],
  toe: [0.365, -0.59, 0.31],
  ...PONY_JOINTS,
};
const mir = (a, s) => [a[0] * s, a[1], a[2]];
const V = (a) => new THREE.Vector3(a[0], a[1], a[2]);

const COLORS = {
  jacket: [0.07, 0.09, 0.2],
  breeches: [0.92, 0.9, 0.84],
  boot: [0.03, 0.03, 0.03],
  skin: [0.88, 0.68, 0.56],
  hair: [0.38, 0.24, 0.12],
  helmet: [0.04, 0.04, 0.05],
  glove: [0.06, 0.06, 0.06],
  collar: [0.95, 0.95, 0.95],
  steel: [0.72, 0.73, 0.75],
  leather: [0.16, 0.09, 0.05],
  hairTip: [0.5, 0.33, 0.17],
  eyeWhite: [0.97, 0.97, 0.95],
  iris: [0.22, 0.4, 0.62],
  pupil: [0.02, 0.02, 0.03],
  brow: [0.24, 0.15, 0.08],
  nose: [0.86, 0.6, 0.5],
  lip: [0.66, 0.24, 0.24],
  blush: [0.96, 0.58, 0.52],
  strap: [0.05, 0.045, 0.045],
  bow: [0.93, 0.3, 0.5],
  bowKnot: [0.8, 0.2, 0.4],
  button: [0.86, 0.7, 0.28],
  piping: [0.82, 0.68, 0.3],
};
const lin = (c) => {
  const col = new THREE.Color().setRGB(c[0], c[1], c[2], THREE.SRGBColorSpace);
  return [col.r, col.g, col.b];
};
const C = Object.fromEntries(Object.entries(COLORS).map(([k, v]) => [k, lin(v)]));

// Rings and segments per level. `low` must not get more triangles than the rider had before the
// face and the ponytail were added (1336): the face is paid for with coarser hands, boots and
// stirrups, which are small in the picture.
const DETAIL = {
  low: {
    torso: [8, 8],
    limb: [4, 6],
    head: [8, 6],
    peak: [5, 3],
    hand: [5, 4],
    iron: [3, 5],
    foot: 3,
    leather: 2,
  },
  medium: {
    torso: [14, 12],
    limb: [7, 9],
    head: [12, 9],
    peak: [10, 4],
    hand: [10, 6],
    iron: [4, 10],
    foot: 4,
    leather: 4,
  },
  high: {
    torso: [20, 16],
    limb: [10, 12],
    head: [16, 12],
    peak: [14, 4],
    hand: [14, 6],
    iron: [4, 14],
    foot: 4,
    leather: 4,
  },
};

const samples = (n) => Array.from({ length: n + 1 }, (_, i) => i / n);

function createBones() {
  const bones = {};
  const list = [];
  const make = (name, pos, parent) => {
    const b = new THREE.Bone();
    b.name = name;
    b.userData.rest = V(pos);
    if (parent) {
      b.position.copy(b.userData.rest).sub(bones[parent].userData.rest);
      bones[parent].add(b);
    } else b.position.copy(b.userData.rest);
    bones[name] = b;
    list.push(b);
  };
  make('base', J.base, null);
  make('pelvis', J.pelvis, 'base');
  make('spine', J.spine, 'pelvis');
  make('chest', J.chest, 'spine');
  make('neck', J.neck, 'chest');
  make('head', J.head, 'neck');
  make('pony1', J.pony1, 'head');
  make('pony2', J.pony2, 'pony1');
  make('pony3', J.pony3, 'pony2');
  SIDES.forEach((s) => {
    const p = s > 0 ? 'L' : 'R';
    make(`${p}upperArm`, mir(J.shoulder, s), 'chest');
    make(`${p}forearm`, mir(J.elbow, s), `${p}upperArm`);
    make(`${p}hand`, mir(J.wrist, s), `${p}forearm`);
    make(`${p}thigh`, mir(J.hip, s), 'pelvis');
    make(`${p}shin`, mir(J.knee, s), `${p}thigh`);
    make(`${p}foot`, mir(J.ankle, s), `${p}shin`);
  });
  const index = {};
  list.forEach((b, i) => (index[b.name] = i));
  return { bones, list, index };
}

/** Tube along a polyline of joints with radius table r(u) and bone chain weights. */
function limb(b, points, radii, bones, color, D, opts = {}) {
  const curve = new THREE.CatmullRomCurve3(points.map(V), false, 'centripetal');
  const len = curve.getLength();
  const joints = [];
  let acc = 0;
  for (let i = 1; i < points.length - 1; i++) {
    acc += V(points[i]).distanceTo(V(points[i - 1]));
    joints.push(acc / len);
  }
  const rt = table(radii);
  new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [r, flat = 1] = rt(u);
      return oval(a, { w: r, up: r * flat, down: r * flat });
    },
    weights: (u) => chainWeights(u, joints, bones, 0.04 / len),
    attrs: (u) => ({ color: typeof color === 'function' ? color(u) : color }),
  }).build(b, {
    uSamples: samples(opts.n || D.limb[0]),
    radial: D.limb[1],
    capStart: { len: opts.capStart ?? 0.02, rings: 1 },
    capEnd: { len: opts.capEnd ?? 0.02, rings: 1 },
  });
}

function buildRiderGeometry(index, level) {
  const D = DETAIL[level] || DETAIL.medium;
  const b = new MeshBuilder(index, { color: 3 });

  // Torso (n points backward for an upward tangent: "up" = back, "down" = front)
  const torsoPts = [
    [0, -0.015, -0.06],
    [0, 0.2, -0.06],
    [0, 0.42, -0.05],
    [0, 0.63, -0.03],
  ];
  const curve = new THREE.CatmullRomCurve3(torsoPts.map(V), false, 'centripetal');
  const T = table([
    [0.0, 0.155, 0.13, 0.09],
    [0.15, 0.17, 0.125, 0.11],
    [0.36, 0.14, 0.09, 0.1],
    [0.6, 0.165, 0.1, 0.11],
    [0.84, 0.19, 0.09, 0.08],
    [1.0, 0.07, 0.05, 0.05],
  ]);
  const ju = [0.27, 0.62, 0.95];
  const torso = new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [w, back, front] = T(u);
      return oval(a, { w, up: back, down: front, nUp: 2.2, nDown: 2.2 });
    },
    weights: (u) => chainWeights(u, ju, ['pelvis', 'spine', 'chest', 'neck'], 0.06),
    attrs: (u) => ({ color: u < 0.3 ? C.breeches : u > 0.95 ? C.collar : C.jacket }),
  });
  torso.build(b, {
    uSamples: samples(D.torso[0]),
    radial: D.torso[1],
    capStart: { len: 0.05, rings: 2 },
    capEnd: { len: 0.02, rings: 1 },
  });
  addJacketDetails(b, torso, level, C);

  // Neck, head, ponytail
  limb(
    b,
    [
      [0, 0.6, -0.035],
      [0, 0.69, -0.025],
      [0, 0.78, -0.01],
    ],
    [
      [0, 0.048],
      [1, 0.045],
    ],
    ['neck', 'head'],
    C.skin,
    D,
    { n: 3 },
  );
  const hd = ellipsoidData(0.083, 0.112, 0.1, D.head[0], D.head[1]);
  b.addIndexed(
    hd.p,
    hd.idx,
    new THREE.Matrix4().makeTranslation(0, 0.815, 0.0),
    () => [['head', 1]],
    (v) => ({ color: v.z < -0.02 && v.y > 0.755 ? C.hair : C.skin }),
  );
  addPonytail(b, level, C);
  // Helmet (shell) with peak
  const helmet = new THREE.SphereGeometry(
    1,
    D.head[0],
    D.head[1],
    0,
    Math.PI * 2,
    0,
    Math.PI * 0.56,
  );
  helmet.scale(0.096, 0.1, 0.116);
  b.addIndexed(
    Array.from(helmet.attributes.position.array),
    Array.from(helmet.index.array),
    new THREE.Matrix4().makeRotationX(-0.12).setPosition(0, 0.845, -0.005),
    () => [['head', 1]],
    () => ({ color: C.helmet }),
  );
  helmet.dispose();
  const peak = ellipsoidData(0.072, 0.008, 0.05, D.peak[0], D.peak[1]);
  b.addIndexed(
    peak.p,
    peak.idx,
    new THREE.Matrix4().makeRotationX(0.25).setPosition(0, 0.86, 0.095),
    () => [['head', 1]],
    () => ({ color: C.helmet }),
  );
  addFace(b, level, C);
  addChinStrap(b, level, C);

  for (const s of SIDES) {
    const p = s > 0 ? 'L' : 'R';
    // Arm
    limb(
      b,
      [mir(J.shoulder, s), mir(J.elbow, s), mir(J.wrist, s)],
      [
        [0, 0.05],
        [0.45, 0.043],
        [0.55, 0.04],
        [0.95, 0.031],
        [1, 0.032],
      ],
      [`${p}upperArm`, `${p}forearm`],
      C.jacket,
      D,
    );
    const hand = ellipsoidData(0.028, 0.042, 0.05, D.hand[0], D.hand[1]);
    const hp = V(J.wrist)
      .setX(J.wrist[0] * s)
      .add(new THREE.Vector3(-0.01 * s, -0.01, 0.04));
    b.addIndexed(
      hand.p,
      hand.idx,
      new THREE.Matrix4().makeRotationX(0.5).setPosition(hp),
      () => [[`${p}hand`, 1]],
      () => ({ color: C.glove }),
    );
    // Leg: thigh (breeches), shin and foot (boot)
    limb(
      b,
      [mir(J.hip, s), mir(J.knee, s), mir(J.ankle, s)],
      [
        [0, 0.085],
        [0.25, 0.08],
        [0.47, 0.058],
        [0.5, 0.057],
        [0.54, 0.06],
        [0.72, 0.055],
        [1, 0.04],
      ],
      [`${p}thigh`, `${p}shin`],
      (u) => (u < 0.52 ? C.breeches : C.boot),
      D,
      { capStart: 0.05 },
    );
    limb(
      b,
      [mir([J.ankle[0], J.ankle[1] - 0.01, J.ankle[2] - 0.05], s), mir(J.ankle, s), mir(J.toe, s)],
      [
        [0, 0.04, 1.1],
        [0.5, 0.042, 1.0],
        [1, 0.033, 0.8],
      ],
      [`${p}foot`, `${p}foot`],
      C.boot,
      D,
      { n: D.foot },
    );
    // Stirrup iron under the ball of the foot, leather up to the saddle
    const iron = torusData(0.055, 0.007, D.iron[0], D.iron[1]);
    const ip = new THREE.Vector3(J.toe[0] * s, J.toe[1] + 0.055 - 0.035, J.toe[2] - 0.07);
    b.addIndexed(
      iron.p,
      iron.idx,
      new THREE.Matrix4().makeScale(0.9, 1, 1).setPosition(ip),
      () => [[`${p}foot`, 1]],
      () => ({ color: C.steel }),
    );
    const top = new THREE.Vector3(0.2 * s, -0.04, 0.1);
    const bottom = ip.clone().add(new THREE.Vector3(0, 0.05, 0));
    new Loft({
      frame: lineFrames(top, bottom, new THREE.Vector3(0, 0, 1)),
      section: (u, a) => [Math.cos(a) * 0.003, Math.sin(a) * 0.016],
      weights: (u) => [
        ['base', 1 - smoothstep(0.0, 1.0, u)],
        [`${p}foot`, smoothstep(0.0, 1.0, u)],
      ],
      attrs: () => ({ color: C.leather }),
    }).build(b, { uSamples: samples(D.leather), radial: 4 });
  }
  return b.build();
}

/** Rider from bind pose; update() applies the seat and IK. */
export function createRider({ quality = 'medium', release = releaseNow } = {}) {
  let level = quality;
  const object = new THREE.Group();
  object.name = 'rider';
  const { bones, list, index } = createBones();
  object.add(bones.base);
  object.updateMatrixWorld(true);
  const skeleton = new THREE.Skeleton(list);
  const mesh = new THREE.SkinnedMesh(
    buildRiderGeometry(index, level),
    createVertexColorMaterial(level, 0.7),
  );
  mesh.name = 'rider-body';
  mesh.frustumCulled = false;
  object.add(mesh);
  mesh.bind(skeleton, mesh.matrixWorld);
  mesh.castShadow = level !== 'low';

  const restDir = {};
  for (const b of list) {
    const child = b.children.find((c) => c.isBone);
    if (child) restDir[b.name] = child.position.clone().normalize();
  }
  restDir.Lhand = new THREE.Vector3(0, -0.2, 1).normalize();
  restDir.Rhand = restDir.Lhand.clone();

  const invBase = new THREE.Matrix4();
  const m4 = new THREE.Matrix4();
  const pRoot = new THREE.Vector3();
  const pMid = new THREE.Vector3();
  const pTarget = new THREE.Vector3();
  const pPole = new THREE.Vector3();
  const qParent = new THREE.Quaternion();
  const qTmp = new THREE.Quaternion();
  const vA = new THREE.Vector3();
  const vB = new THREE.Vector3();
  const scl = new THREE.Vector3();
  const vTmp = new THREE.Vector3();
  const vDir = new THREE.Vector3();

  function frameOf(bone, posOut, quatOut) {
    m4.multiplyMatrices(invBase, bone.matrixWorld);
    m4.decompose(posOut, quatOut, scl);
  }

  function aim(bone, dirBase) {
    // local quaternion so that the bone's rest direction points along dirBase (base frame)
    frameOf(bone.parent, vTmp, qParent);
    vB.copy(dirBase).normalize().applyQuaternion(qTmp.copy(qParent).invert());
    bone.quaternion.setFromUnitVectors(restDir[bone.name], vB);
    bone.updateMatrixWorld(true);
  }

  function twoBone(a, bMid, end, target, pole) {
    frameOf(a, pRoot, qParent);
    const l1 = bMid.position.length();
    const l2 = end.position.length();
    const d = vA.subVectors(target, pRoot);
    const dist = limbReach(d.length(), l1, l2);
    d.normalize();
    const x = (l1 * l1 + dist * dist - l2 * l2) / (2 * dist);
    const h = Math.sqrt(Math.max(0, l1 * l1 - x * x));
    vB.subVectors(pole, pRoot);
    vB.addScaledVector(d, -vB.dot(d)).normalize();
    pMid.copy(pRoot).addScaledVector(d, x).addScaledVector(vB, h);
    aim(a, vDir.subVectors(pMid, pRoot));
    aim(bMid, vDir.subVectors(target, pMid));
  }

  const seatFilter = createSeatFilter();
  const seat = {};
  const look = createHeadLook();
  const pat = createPat();
  const breath = { chest: 0, roll: 0 };
  const pony = createPonytail();
  const ponyBones = PONY_NAMES.map((n) => bones[n]);
  const motion = {
    has: false,
    pos: new THREE.Vector3(),
    prev: new THREE.Vector3(),
    vel: new THREE.Vector3(),
    prevVel: new THREE.Vector3(),
    acc: new THREE.Vector3(),
    quat: new THREE.Quaternion(),
    input: { ax: 0, ay: 0, az: 0, vx: 0, vy: 0, vz: 0, gy: -1, gz: 0 },
  };
  const vL = new THREE.Vector3();
  const aL = new THREE.Vector3();
  const gL = new THREE.Vector3();
  const api = {
    object,
    hands: [bones.Lhand, bones.Rhand],
    bones,
    update(dt, state = {}, ctx = null) {
      seatFilter.step(dt, state, ctx, seat);
      const halt = ctx?.weights ? ctx.weights.halt || 0 : state.gait === 'halt' ? 1 : 0;
      stepHeadLook(look, dt, state, halt);
      stepPat(pat, dt, { jumping: !!state.jump, halted: halt > 0.95 });
      breathing(look.time, halt, breath);
      const B = bones;
      B.pelvis.position.set(J.pelvis[0], J.pelvis[1] + seat.rise, J.pelvis[2] + seat.forward);
      const lean = seat.lean + PAT.lean * pat.reach;
      B.pelvis.rotation.set(lean * 0.4 + seat.sway, 0, seat.roll);
      B.spine.rotation.set(lean * 0.33, 0, 0);
      B.chest.rotation.set(lean * 0.27 + breath.chest, 0, breath.roll);
      const yaw = look.yaw.x;
      B.neck.rotation.set(-lean * 0.45, yaw * LOOK_SHARE.neck, 0);
      B.head.rotation.set(
        -lean * 0.4 - seat.horsePitch * 0.6 + look.pitch.x,
        yaw * LOOK_SHARE.head,
        0,
      );
      B.base.updateMatrixWorld(true);
      invBase.copy(B.base.matrixWorld).invert();
      for (const s of SIDES) {
        const names = s > 0 ? LEFT_NAMES : RIGHT_NAMES;
        // legs: ankle in the stirrup (fixed on the saddle), knee forward/outward
        pTarget.set(J.ankle[0] * s, J.ankle[1], J.ankle[2] + seat.footForward);
        pPole.set(0.6 * s, -0.1, 1.2);
        twoBone(B[names.thigh], B[names.shin], B[names.foot], pTarget, pPole);
        frameOf(B[names.shin], vA, qParent);
        B[names.foot].quaternion.copy(qParent).invert();
        B[names.foot].rotateX(-0.1);
        // arms: hands on the reins above the withers, elbows down/back/outward; the right hand
        // goes to the horse's neck for a pat
        const patS = s < 0 ? pat.reach : 0;
        pTarget.set(
          (seat.handX + PAT.x * patS) * s,
          seat.handY + PAT.y * patS + PAT.tap * pat.tap * patS,
          seat.handZ + PAT.z * patS,
        );
        pPole.set(0.5 * s, -0.4, -0.6);
        twoBone(B[names.upperArm], B[names.forearm], B[names.hand], pTarget, pPole);
        frameOf(B[names.forearm], vA, qParent);
        B[names.hand].quaternion.copy(qParent).invert();
        B[names.hand].rotateZ(-0.5 * s);
      }
    },
    /**
     * Secondary motion that needs the final world matrices of the head (call after the horse has
     * updated them): the ponytail lags behind the head's bob, acceleration and turns.
     */
    lateUpdate(dt) {
      if (!(dt > 0)) return;
      const m = motion;
      bones.head.matrixWorld.decompose(m.pos, m.quat, scl);
      if (!m.has || m.pos.distanceToSquared(m.prev) > TELEPORT_DISTANCE ** 2) {
        // first frame or a jump in place (restart): no velocity yet
        m.has = true;
        m.prev.copy(m.pos);
        m.vel.set(0, 0, 0);
        m.prevVel.set(0, 0, 0);
        m.acc.set(0, 0, 0);
        return;
      }
      m.vel.subVectors(m.pos, m.prev).divideScalar(dt);
      m.acc.lerp(
        vA.subVectors(m.vel, m.prevVel).divideScalar(dt),
        1 - Math.exp(-ACCEL_FILTER * dt),
      );
      m.prev.copy(m.pos);
      m.prevVel.copy(m.vel);
      // into the head's own frame (+Z forward, +X left)
      qTmp.copy(m.quat).invert();
      vL.copy(m.vel).applyQuaternion(qTmp);
      aL.copy(m.acc).applyQuaternion(qTmp);
      gL.set(0, -1, 0).applyQuaternion(qTmp);
      const input = m.input;
      input.ax = aL.x;
      input.ay = aL.y;
      input.az = aL.z;
      input.vx = vL.x;
      input.vy = vL.y;
      input.vz = vL.z;
      input.gy = gL.y;
      input.gz = gL.z;
      stepPonytail(pony, input, dt);
      for (let i = 0; i < PONY_SEGMENTS; i++) {
        ponyBones[i].rotation.set(pony.pitch[i].x, pony.yaw[i].x, 0);
      }
    },
    setQuality(l) {
      if (l === level) return;
      level = l;
      release(mesh.geometry);
      release(mesh.material);
      mesh.geometry = buildRiderGeometry(index, level);
      mesh.material = createVertexColorMaterial(level, 0.7);
      mesh.castShadow = level !== 'low';
    },
    dispose() {
      // through `release`, so that objects of a lost context are not freed with GL calls
      release(mesh.geometry);
      release(mesh.material);
      release(skeleton.boneTexture);
      object.removeFromParent();
    },
  };
  return api;
}
