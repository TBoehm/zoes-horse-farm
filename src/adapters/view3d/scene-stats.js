// Draw calls and triangles of a scene as three.js would submit them (rule 3: the budget per
// quality level). No GL: the numbers come from the scene graph, so Node tests can compare the
// levels. Frustum culling is ignored, so the numbers are an upper bound of one frame.

function trianglesOf(geometry) {
  const position = geometry?.attributes?.position;
  if (!position) return 0;
  const total = geometry.index ? geometry.index.count : position.count;
  const { start, count } = geometry.drawRange;
  const used = Math.max(0, Math.min(total - start, count));
  return Math.floor(used / 3);
}

/**
 * Counts the visible meshes: draw calls (one per mesh, one per material group) and triangles
 * (times the instance count of instanced meshes). `shadowPass` is what the objects that cast
 * shadows cost again in the shadow pass.
 */
export function sceneStats(root) {
  const stats = { calls: 0, triangles: 0, instances: 0, shadowPass: { calls: 0, triangles: 0 } };
  root.traverseVisible((object) => {
    if (object.isSprite) {
      stats.calls += 1;
      stats.triangles += 2;
      return;
    }
    if (!object.isMesh || !object.geometry) return;
    const copies = object.isInstancedMesh ? object.count : 1;
    const triangles = trianglesOf(object.geometry) * copies;
    if (copies <= 0 || triangles <= 0) return;
    const groups = Array.isArray(object.material) ? Math.max(1, object.geometry.groups.length) : 1;
    stats.calls += groups;
    stats.triangles += triangles;
    if (object.isInstancedMesh) stats.instances += copies;
    if (object.castShadow) {
      stats.shadowPass.calls += groups;
      stats.shadowPass.triangles += triangles;
    }
  });
  return stats;
}
