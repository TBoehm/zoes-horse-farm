// Neck shape data and the anchors of the mane bones (shared by geometry.js and skeleton.js).
import * as THREE from 'three';
import { REST } from './anatomy.js';
import { curveFrames, nearestU, table } from './loft.js';

const V = (a) => new THREE.Vector3(a[0], a[1], a[2]);

const NECK_PTS = [
  [0, 1.25, 0.46],
  [0, 1.42, 0.74],
  [0, 1.66, 1.0],
  [0, 1.9, 1.245],
  [0, 2.05, 1.41],
];

export const NECK = table([
  // u, half width, crest, throat, top taper
  [0.0, 0.22, 0.32, 0.36, 0.72],
  [0.15, 0.2, 0.28, 0.35, 0.66],
  [0.4, 0.155, 0.21, 0.23, 0.6],
  [0.65, 0.12, 0.165, 0.15, 0.55],
  [0.85, 0.104, 0.135, 0.14, 0.55],
  [1.0, 0.098, 0.11, 0.13, 0.62],
]);

let curve = null;
let joints = null;

/** Centre line of the neck (shared, built once). */
export function neckCurve() {
  curve ||= new THREE.CatmullRomCurve3(NECK_PTS.map(V), false, 'centripetal');
  return curve;
}

/** Curve parameters of the neck joints (neck1, neck2, neck3) and of the poll (head). */
export function neckJoints() {
  joints ||= [...REST.neck, REST.head].map((p) => nearestU(neckCurve(), V(p)));
  return joints;
}

/** Positions of the mane bones along the neck (curve parameter). */
export const MANE_U = [0.14, 0.32, 0.5, 0.68, 0.86];

/** Boundaries between the influence zones of neighbouring mane bones. */
export const MANE_SPLIT = MANE_U.slice(1).map((u, i) => (u + MANE_U[i]) / 2);
/** Half width of the transition between two mane bones. */
export const MANE_BLEND = (MANE_U[1] - MANE_U[0]) / 2;

/** Bone that carries the neck at curve parameter u. */
function neckBoneAt(u) {
  const j = neckJoints();
  const names = ['spineFront', 'neck1', 'neck2', 'neck3', 'head'];
  let k = 0;
  while (k < j.length && u > j[k]) k++;
  return names[k];
}

/**
 * Anchors of the mane bones on the crest: { name, parent, position (model space), quaternion }.
 * The bone frame has z along the neck, y towards the crest and x sideways, so a rotation about z
 * lifts the hair off the neck and a rotation about x swings it along the neck.
 */
export function maneAnchors() {
  const c = neckCurve();
  const frames = curveFrames(c);
  return MANE_U.map((u, i) => {
    const f = frames(u);
    const crest = NECK(u)[1];
    const position = f.o.clone().addScaledVector(f.n, crest * 0.92);
    const m = new THREE.Matrix4().makeBasis(f.b, f.n, f.t);
    return {
      name: `mane${i + 1}`,
      parent: neckBoneAt(u),
      position,
      quaternion: new THREE.Quaternion().setFromRotationMatrix(m),
    };
  });
}
