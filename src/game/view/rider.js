// Platzhalter – wird ersetzt.
import * as THREE from 'three';
export function createRider() {
  const object = new THREE.Group();
  const hands = [new THREE.Object3D(), new THREE.Object3D()];
  hands[0].position.set(0.09, 0.2, 0.36);
  hands[1].position.set(-0.09, 0.2, 0.36);
  object.add(...hands);
  return { object, hands, update() {}, setQuality() {}, dispose() {} };
}
