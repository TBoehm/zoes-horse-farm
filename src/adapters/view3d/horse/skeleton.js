// Horse bone hierarchy in model coordinates (rest pose from anatomy.js). Bones have no rotation in
// the rest pose; animation = rotation relative to it.
import * as THREE from 'three';

import { REST, SIDES } from './anatomy.js';
import { maneAnchors } from './neck.js';

export { LEG_NAMES, REST, SIDES } from './anatomy.js';

// Head: axis from the poll (head bone) towards the muzzle, tilted ~55° downwards.
const HEAD_ANGLE = (55 * Math.PI) / 180;
export const HEAD = {
  origin: new THREE.Vector3(...REST.head),
  // towards the muzzle
  dir: new THREE.Vector3(0, -Math.sin(HEAD_ANGLE), Math.cos(HEAD_ANGLE)),
  // forehead / nasal bridge
  front: new THREE.Vector3(0, Math.cos(HEAD_ANGLE), Math.sin(HEAD_ANGLE)),
  length: 0.62,
};
/** Point in head coordinates (s along the head, f towards the forehead, x lateral). */
export function headPoint(s, f, x) {
  return HEAD.origin
    .clone()
    .addScaledVector(HEAD.dir, s)
    .addScaledVector(HEAD.front, f)
    .add(new THREE.Vector3(x, 0, 0));
}

export const EAR = {
  base: (side) => headPoint(0.035, 0.085, 0.058 * side),
  dir: (side) => new THREE.Vector3(0.28 * side, 1, 0.12).normalize(),
  length: 0.16,
};

/** Anchor of the forelock bone on the forehead (it swings about this point). */
export const FORELOCK_ANCHOR = () => headPoint(-0.02, 0.08, 0);

/**
 * Eye and eyelid: the eye is an ellipsoid turned about Y by `yaw`; the upper lid is a hemispherical
 * cap whose pole points at `openAngle` (from the gaze towards up and back, so the lid rests above
 * the eye) and is turned about `axis` by −`closeAngle` to cover the eye.
 */
export const EYE = {
  radii: [0.019, 0.018, 0.025],
  yaw: 0.35,
  center: (side) => headPoint(0.168, 0.03, 0.104 * side),
  openAngle: (135 * Math.PI) / 180,
  closeAngle: (150 * Math.PI) / 180,
  lidScale: 1.15,
};
const UP = new THREE.Vector3(0, 1, 0);
/** Gaze direction of the eye on `side` (head frame). */
export function eyeGaze(side) {
  return new THREE.Vector3(side, 0, 0).applyAxisAngle(UP, EYE.yaw * side);
}
/** Hinge axis of the eyelid (head frame); a positive rotation about it lifts the gaze upwards. */
export function lidAxis(side) {
  return eyeGaze(side).cross(UP).normalize();
}
/** Direction of the lid pole at `angle` from the gaze towards up (head frame). */
export function lidPole(side, angle) {
  return eyeGaze(side).multiplyScalar(Math.cos(angle)).addScaledVector(UP, Math.sin(angle));
}

export const SADDLE_SEAT = new THREE.Vector3(0, 1.63, 0.08); // rider's seat point
export const EAR_ANCHOR = () => headPoint(0.03, 0.14, 0); // between the ears

const v = (a) => new THREE.Vector3(a[0], a[1], a[2]);
const mirror = (a, side) => [a[0] * side, a[1], a[2]];

/**
 * Creates the bone hierarchy. Returns { root, bones: name → Bone, list, index: name → i }.
 */
export function createSkeletonBones() {
  const bones = {};
  const list = [];
  const make = (name, restPos, parentName, quaternion = null) => {
    const b = new THREE.Bone();
    b.name = name;
    b.userData.rest = v(restPos);
    if (quaternion) {
      b.quaternion.copy(quaternion);
      b.userData.restQuaternion = quaternion.clone();
    }
    const parent = parentName ? bones[parentName] : null;
    if (parent) {
      b.position.copy(b.userData.rest).sub(parent.userData.rest);
      parent.add(b);
    } else {
      b.position.copy(b.userData.rest);
    }
    bones[name] = b;
    list.push(b);
    return b;
  };
  make('root', REST.root, null);
  make('spineFront', REST.spineFront, 'root');
  make('spineRear', REST.spineRear, 'root');
  make('belly', REST.belly, 'root');
  SIDES.forEach((side, i) => {
    const p = i === 0 ? 'L' : 'R';
    const F = REST.front;
    make(`${p}scapula`, mirror(F.scapula, side), 'spineFront');
    make(`${p}humerus`, mirror(F.shoulder, side), `${p}scapula`);
    make(`${p}forearm`, mirror(F.elbow, side), `${p}humerus`);
    make(`${p}fcannon`, mirror(F.knee, side), `${p}forearm`);
    make(`${p}fpastern`, mirror(F.fetlock, side), `${p}fcannon`);
    const H = REST.hind;
    make(`${p}femur`, mirror(H.hip, side), 'spineRear');
    make(`${p}tibia`, mirror(H.stifle, side), `${p}femur`);
    make(`${p}hcannon`, mirror(H.hock, side), `${p}tibia`);
    make(`${p}hpastern`, mirror(H.fetlock, side), `${p}hcannon`);
  });
  make('neck1', REST.neck[0], 'spineFront');
  make('neck2', REST.neck[1], 'neck1');
  make('neck3', REST.neck[2], 'neck2');
  make('head', REST.head, 'neck3');
  SIDES.forEach((side, i) => {
    const p = i === 0 ? 'L' : 'R';
    const base = EAR.base(side);
    make(`${p}ear`, [base.x, base.y, base.z], 'head');
  });
  REST.tail.forEach((t, i) => make(`tail${i + 1}`, t, i === 0 ? 'spineRear' : `tail${i}`));
  // mane (a bone per anchor on the crest) and forelock: they swing on their own (life.js)
  for (const a of maneAnchors()) make(a.name, a.position.toArray(), a.parent, a.quaternion);
  make('forelock', FORELOCK_ANCHOR().toArray(), 'head');
  // eyelids
  SIDES.forEach((side, i) => {
    const b = make(`${i === 0 ? 'L' : 'R'}lid`, EYE.center(side).toArray(), 'head');
    b.userData.axis = lidAxis(side);
  });
  const index = {};
  list.forEach((b, i) => (index[b.name] = i));
  return { root: bones.root, bones, list, index };
}
