// Meadow flowers: one instanced mesh of small flowers in patches, bending in the wind. Only the
// levels with `flowers` > 0 draw it (the mesh is hidden otherwise and never uploaded to the GPU).
import * as THREE from 'three';
import { terrainHeight } from './world-layout.js';
import { createRng } from './textures.js';
import { FLOWER_COLORS, planMeadow } from './meadow-plan.js';
import { buildFlowerGeometry } from './flower-geometry.js';
import { patchBlossoms } from './plant-shaders.js';

/**
 * materialFactory(kind, params) → { standard, lambert }; wind from createWind().
 * Returns { mesh, mats, patches, setDensity(share) }; `share` ∈ [0, 1] is the share of
 * the flowers that is drawn (the first ones, which are spread over all patches).
 */
export function createMeadow({ materialFactory, wind, seed = 11 }) {
  const rng = createRng(seed + 7);
  const { patches, flowers } = planMeadow(rng);
  const mats = materialFactory('flowers', {
    vertexColors: true,
    roughness: 1,
    side: THREE.DoubleSide,
  });
  patchBlossoms(mats.standard, wind);
  patchBlossoms(mats.lambert, wind);

  const geometry = buildFlowerGeometry();
  const mesh = new THREE.InstancedMesh(geometry, mats.standard, Math.max(1, flowers.length));
  mesh.name = 'flowers';
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const s = new THREE.Vector3();
  const p = new THREE.Vector3();
  const c = new THREE.Color();
  flowers.forEach((f, i) => {
    p.set(f.x, terrainHeight(f.x, f.z), f.z);
    q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, f.yaw);
    s.setScalar(f.scale);
    m.compose(p, q, s);
    mesh.setMatrixAt(i, m);
    // a little brightness variation, so a patch is not one flat colour
    c.set(FLOWER_COLORS[f.color].hex).multiplyScalar(0.9 + rng() * 0.2);
    mesh.setColorAt(i, c);
  });
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  mesh.computeBoundingSphere();
  mesh.userData.total = flowers.length;
  mesh.count = 0;
  mesh.visible = false;

  return {
    mesh,
    mats,
    patches,
    setDensity(share) {
      mesh.count = Math.round(mesh.userData.total * Math.min(1, Math.max(0, share)));
      mesh.visible = mesh.count > 0;
    },
  };
}
