// Absprung-Hilfe (Regel 42): halbtransparentes Band auf dem Sand vor der Vorderkante.
import * as THREE from 'three';
import { aidPlacement } from './world-layout.js';
import { createSoftRectTexture } from './textures.js';

/** Lage des Bandes, siehe aidPlacement (world-layout.js). */
export const aidTransform = aidPlacement;

export function createAidMarker({ color = 0x2fe6a6 } = {}) {
  const geometry = new THREE.PlaneGeometry(1, 1);
  geometry.rotateX(-Math.PI / 2);
  const material = new THREE.MeshBasicMaterial({
    color,
    alphaMap: createSoftRectTexture(),
    transparent: true,
    opacity: 0.6,
    depthWrite: false,
    toneMapped: false,
    polygonOffset: true,
    polygonOffsetFactor: -2,
    polygonOffsetUnits: -2,
  });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.name = 'aid-marker';
  mesh.position.y = 0.018;
  mesh.renderOrder = 2;
  mesh.visible = false;
  let time = 0;

  return {
    mesh,
    /** Nur Transform ändern, keine neue Geometrie. */
    set(element, dir, zone) {
      const t = aidTransform(element, dir, zone);
      if (!t) {
        mesh.visible = false;
        return;
      }
      mesh.position.set(t.x, 0.018, t.z);
      mesh.rotation.set(0, t.rotY, 0);
      mesh.scale.set(t.width, 1, t.depth);
      mesh.visible = true;
    },
    hide() {
      mesh.visible = false;
    },
    update(dt) {
      if (!mesh.visible) return;
      time += dt;
      material.opacity = 0.5 + 0.15 * Math.sin(time * 3);
    },
    dispose() {
      geometry.dispose();
      material.alphaMap.dispose();
      material.dispose();
    },
  };
}
