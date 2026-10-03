// Ruhepose (Bind-Pose) des Pferdes in Modell-Koordinaten: Meter, Y oben, Blick nach +Z,
// Ursprung am Boden unter der Körpermitte. Warmblut, Widerrist ≈ 1,65 m.
// Alle Knochen haben in der Ruhepose keine Rotation; Animation = Rotation relativ dazu.
import * as THREE from 'three';

import { REST, SIDES } from './anatomy.js';

export { LEG_NAMES, REST, SIDES } from './anatomy.js';

// Kopf: Achse von Genick (Kopfknochen) Richtung Maul, ca. 55° nach unten geneigt.
const HEAD_ANGLE = (55 * Math.PI) / 180;
export const HEAD = {
  origin: new THREE.Vector3(...REST.head),
  dir: new THREE.Vector3(0, -Math.sin(HEAD_ANGLE), Math.cos(HEAD_ANGLE)), // Richtung Maul
  front: new THREE.Vector3(0, Math.cos(HEAD_ANGLE), Math.sin(HEAD_ANGLE)), // Stirn/Nasenrücken
  length: 0.62,
};
/** Punkt in Kopf-Koordinaten (s entlang Kopf, f Richtung Stirn, x lateral). */
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

export const SADDLE_SEAT = new THREE.Vector3(0, 1.63, 0.08); // Sitzpunkt des Reiters
export const EAR_ANCHOR = () => headPoint(0.03, 0.14, 0); // zwischen den Ohren

const v = (a) => new THREE.Vector3(a[0], a[1], a[2]);
const mirror = (a, side) => [a[0] * side, a[1], a[2]];

/**
 * Erzeugt die Knochen-Hierarchie. Rückgabe: { root, bones: name → Bone, list, index: name → i }.
 */
export function createSkeletonBones() {
  const bones = {};
  const list = [];
  const make = (name, restPos, parentName) => {
    const b = new THREE.Bone();
    b.name = name;
    b.userData.rest = v(restPos);
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
  const index = {};
  list.forEach((b, i) => (index[b.name] = i));
  return { root: bones.root, bones, list, index };
}
