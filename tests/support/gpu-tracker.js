// A stand-in for what three.js keeps on the GPU, for Node tests (rule 4: the peak of live shader
// programs and objects during a level change). No GL: it follows the same bookkeeping rules as
// WebGLRenderer in r186:
//   - a material gets one program per distinct program key it is drawn with, and keeps ALL of them
//     until it is disposed (materialProperties.programs is only emptied by onMaterialDispose), so a
//     material that switches variant without a dispose holds the old programs as well;
//   - programs are shared between materials with the same key (WebGLPrograms.acquireProgram), a
//     program is freed when the last material releases it;
//   - geometries and instanced meshes (their instance buffers) are uploaded when they are drawn and
//     freed by dispose().
// The key is an approximation of WebGLPrograms.getProgramCacheKey: the values that differ between
// the materials of this game (type, patch key, instancing, vertex colours, textures, side, skinning,
// fog, environment map, shadows). It is exact enough to compare levels and to see leaks.

/** Approximation of the program cache key of `material` drawn on `object` in the given state. */
export function programKey(
  object,
  material,
  { fog = false, environment = false, shadows = false },
) {
  const flags = [
    material.type,
    material.customProgramCacheKey?.() ?? '',
    object.isInstancedMesh ? 'inst' : '',
    object.isInstancedMesh && object.instanceColor ? 'instColor' : '',
    object.isSkinnedMesh ? 'skin' : '',
    object.isPoints ? 'points' : '',
    material.vertexColors ? 'vc' : '',
    material.map ? 'map' : '',
    material.normalMap ? 'nmap' : '',
    material.alphaMap ? 'amap' : '',
    material.side === 2 ? 'double' : '',
    material.transparent ? 'transp' : '',
    fog && material.fog !== false ? 'fog' : '',
    environment && material.isMeshStandardMaterial ? 'env' : '',
    shadows && object.receiveShadow ? 'shadow' : '',
  ];
  return flags.join('|');
}

/**
 * Tracks what is on the (imaginary) GPU. `compile(root)` is a draw of everything the root yields
 * through `traverse` (the world's compile root yields the visible objects only); it uploads
 * what is new. `peak` is the highest number of live programs, materials, geometries and instanced
 * meshes seen since the last `resetPeak()`, measured after every upload and every dispose.
 */
export function createGpuTracker({ scene, renderer }) {
  const materials = new Map(); // material → Set of program keys it holds
  const programRefs = new Map(); // program key → number of materials holding it
  const geometries = new Set();
  const instanced = new Set();
  let peak = { programs: 0, materials: 0, geometries: 0, instanced: 0 };

  const snapshot = () => ({
    programs: programRefs.size,
    materials: materials.size,
    geometries: geometries.size,
    instanced: instanced.size,
  });
  const bumpPeak = () => {
    const now = snapshot();
    for (const key of Object.keys(peak)) peak[key] = Math.max(peak[key], now[key]);
  };

  function releaseMaterial(material) {
    const keys = materials.get(material);
    if (!keys) return;
    materials.delete(material);
    for (const key of keys) {
      const n = (programRefs.get(key) ?? 1) - 1;
      if (n <= 0) programRefs.delete(key);
      else programRefs.set(key, n);
    }
  }

  function uploadMaterial(object, material) {
    const state = {
      fog: Boolean(scene.fog),
      environment: Boolean(scene.environment),
      shadows: Boolean(renderer.shadowMap?.enabled),
    };
    const key = programKey(object, material, state);
    let keys = materials.get(material);
    if (!keys) {
      keys = new Set();
      materials.set(material, keys);
      material.addEventListener('dispose', function onDispose() {
        material.removeEventListener('dispose', onDispose);
        releaseMaterial(material);
        bumpPeak();
      });
    }
    if (!keys.has(key)) {
      keys.add(key);
      programRefs.set(key, (programRefs.get(key) ?? 0) + 1);
    }
  }

  function uploadGeometry(set, object) {
    if (!object || set.has(object)) return;
    set.add(object);
    object.addEventListener('dispose', function onDispose() {
      object.removeEventListener('dispose', onDispose);
      set.delete(object);
    });
  }

  return {
    compile(root) {
      root.traverse((object) => {
        if (!(object.isMesh || object.isPoints) || !object.geometry) return;
        const list = Array.isArray(object.material) ? object.material : [object.material];
        for (const material of list) if (material) uploadMaterial(object, material);
        uploadGeometry(geometries, object.geometry);
        if (object.isInstancedMesh) uploadGeometry(instanced, object);
      });
      bumpPeak();
    },
    snapshot,
    get peak() {
      return { ...peak };
    },
    resetPeak() {
      peak = snapshot();
    },
    /** Is this geometry, instanced mesh or material uploaded now? */
    holds(object) {
      return geometries.has(object) || instanced.has(object) || materials.has(object);
    },
    /** Keys of the live programs (for messages). */
    programKeys() {
      return [...programRefs.keys()].sort();
    },
  };
}
