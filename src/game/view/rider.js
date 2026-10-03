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
import { clamp, lerp, smoothstep } from './horse/math.js';
import { riderSeat } from './horse/seat.js';
import { createVertexColorMaterial } from './horse/material.js';

const SIDES = [1, -1];

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
  ankle: [0.315, -0.57, 0.15],
  toe: [0.32, -0.6, 0.31],
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
};
const lin = (c) => {
  const col = new THREE.Color().setRGB(c[0], c[1], c[2], THREE.SRGBColorSpace);
  return [col.r, col.g, col.b];
};
const C = Object.fromEntries(Object.entries(COLORS).map(([k, v]) => [k, lin(v)]));

const DETAIL = {
  low: { torso: [8, 8], limb: [4, 6], head: [8, 6], misc: 6 },
  medium: { torso: [14, 12], limb: [7, 9], head: [12, 9], misc: 10 },
  high: { torso: [20, 16], limb: [10, 12], head: [16, 12], misc: 14 },
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
  new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [w, back, front] = T(u);
      return oval(a, { w, up: back, down: front, nUp: 2.2, nDown: 2.2 });
    },
    weights: (u) => chainWeights(u, ju, ['pelvis', 'spine', 'chest', 'neck'], 0.06),
    attrs: (u) => ({ color: u < 0.3 ? C.breeches : u > 0.95 ? C.collar : C.jacket }),
  }).build(b, {
    uSamples: samples(D.torso[0]),
    radial: D.torso[1],
    capStart: { len: 0.05, rings: 2 },
    capEnd: { len: 0.02, rings: 1 },
  });

  // Neck, head, ponytail
  limb(b, [[0, 0.6, -0.035], [0, 0.69, -0.025], [0, 0.78, -0.01]], [[0, 0.048], [1, 0.045]], ['neck', 'head'], C.skin, D, { n: 3 });
  const hd = ellipsoidData(0.083, 0.112, 0.1, D.head[0], D.head[1]);
  b.addIndexed(
    hd.p,
    hd.idx,
    new THREE.Matrix4().makeTranslation(0, 0.84, 0.0),
    () => [['head', 1]],
    (v) => ({ color: v.z < -0.02 && v.y > 0.78 ? C.hair : C.skin }),
  );
  limb(b, [[0, 0.84, -0.09], [0, 0.78, -0.13], [0, 0.68, -0.15]], [[0, 0.03], [0.5, 0.026], [1, 0.012]], ['head', 'head'], C.hair, D, { n: 4 });
  // Helmet (shell) with peak
  const helmet = new THREE.SphereGeometry(1, D.head[0], D.head[1], 0, Math.PI * 2, 0, Math.PI * 0.56);
  helmet.scale(0.096, 0.1, 0.116);
  b.addIndexed(
    Array.from(helmet.attributes.position.array),
    Array.from(helmet.index.array),
    new THREE.Matrix4().makeRotationX(-0.12).setPosition(0, 0.87, -0.005),
    () => [['head', 1]],
    () => ({ color: C.helmet }),
  );
  helmet.dispose();
  const peak = ellipsoidData(0.072, 0.008, 0.05, D.misc, 4);
  b.addIndexed(
    peak.p,
    peak.idx,
    new THREE.Matrix4().makeRotationX(0.25).setPosition(0, 0.885, 0.095),
    () => [['head', 1]],
    () => ({ color: C.helmet }),
  );

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
    const hand = ellipsoidData(0.028, 0.042, 0.05, D.misc, 6);
    const hp = V(J.wrist).setX(J.wrist[0] * s).add(new THREE.Vector3(-0.01 * s, -0.01, 0.04));
    b.addIndexed(hand.p, hand.idx, new THREE.Matrix4().makeRotationX(0.5).setPosition(hp), () => [[`${p}hand`, 1]], () => ({ color: C.glove }));
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
      { n: 4 },
    );
    // Stirrup iron under the ball of the foot, leather up to the saddle
    const iron = torusData(0.055, 0.007, 4, D.misc);
    const ip = new THREE.Vector3(J.toe[0] * s, J.toe[1] + 0.055 - 0.035, J.toe[2] - 0.07);
    b.addIndexed(iron.p, iron.idx, new THREE.Matrix4().makeScale(0.9, 1, 1).setPosition(ip), () => [[`${p}foot`, 1]], () => ({ color: C.steel }));
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
    }).build(b, { uSamples: samples(4), radial: 4 });
  }
  return b.build();
}

/** Rider from bind pose; update() applies the seat and IK. */
export function createRider({ quality = 'medium' } = {}) {
  let level = quality;
  const object = new THREE.Group();
  object.name = 'rider';
  const { bones, list, index } = createBones();
  object.add(bones.base);
  object.updateMatrixWorld(true);
  const skeleton = new THREE.Skeleton(list);
  const mesh = new THREE.SkinnedMesh(buildRiderGeometry(index, level), createVertexColorMaterial(level, 0.7));
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
    const dist = clamp(d.length(), Math.abs(l1 - l2) + 1e-3, l1 + l2 - 1e-4);
    d.normalize();
    const x = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist);
    const h = Math.sqrt(Math.max(0, l1 * l1 - x * x));
    vB.subVectors(pole, pRoot);
    vB.addScaledVector(d, -vB.dot(d)).normalize();
    pMid.copy(pRoot).addScaledVector(d, x).addScaledVector(vB, h);
    aim(a, vDir.subVectors(pMid, pRoot));
    aim(bMid, vDir.subVectors(target, pMid));
  }

  const seat = {};
  const api = {
    object,
    hands: [bones.Lhand, bones.Rhand],
    bones,
    update(dt, state = {}, ctx = null) {
      riderSeat(state, ctx, seat);
      const B = bones;
      B.pelvis.position.set(J.pelvis[0], J.pelvis[1] + seat.rise, J.pelvis[2] + seat.forward);
      const lean = seat.lean;
      B.pelvis.rotation.set(lean * 0.4 + seat.sway, 0, seat.roll);
      B.spine.rotation.set(lean * 0.33, 0, 0);
      B.chest.rotation.set(lean * 0.27, 0, 0);
      B.neck.rotation.set(-lean * 0.45, 0, 0);
      B.head.rotation.set(-lean * 0.4 - seat.horsePitch * 0.6, 0, 0);
      B.base.updateMatrixWorld(true);
      invBase.copy(B.base.matrixWorld).invert();
      for (const s of SIDES) {
        const p = s > 0 ? 'L' : 'R';
        // legs: ankle in the stirrup (fixed on the saddle), knee forward/outward
        pTarget.set(J.ankle[0] * s, J.ankle[1], J.ankle[2] + seat.footForward);
        pPole.set(0.6 * s, -0.1, 1.2);
        twoBone(B[`${p}thigh`], B[`${p}shin`], B[`${p}foot`], pTarget, pPole);
        frameOf(B[`${p}shin`], vA, qParent);
        B[`${p}foot`].quaternion.copy(qParent).invert();
        B[`${p}foot`].rotateX(-0.1);
        // arms: hands on the reins above the withers, elbows down/back/outward
        pTarget.set(seat.handX * s, seat.handY, seat.handZ);
        pPole.set(0.5 * s, -0.4, -0.6);
        twoBone(B[`${p}upperArm`], B[`${p}forearm`], B[`${p}hand`], pTarget, pPole);
        frameOf(B[`${p}forearm`], vA, qParent);
        B[`${p}hand`].quaternion.copy(qParent).invert();
        B[`${p}hand`].rotateZ(-0.5 * s);
      }
      void dt;
    },
    setQuality(l) {
      if (l === level) return;
      level = l;
      mesh.geometry.dispose();
      mesh.material.dispose();
      mesh.geometry = buildRiderGeometry(index, level);
      mesh.material = createVertexColorMaterial(level, 0.7);
      mesh.castShadow = level !== 'low';
    },
    dispose() {
      mesh.geometry.dispose();
      mesh.material.dispose();
      skeleton.dispose();
      object.removeFromParent();
    },
  };
  void lerp;
  return api;
}
