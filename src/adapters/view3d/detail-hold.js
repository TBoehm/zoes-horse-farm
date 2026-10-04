// Holds back the meshes of the optional scenery details (flowers, tufts, birds, bunting, flower
// boxes, props) while a level change is between its material stage and its density stage
// (rule 4). Those meshes belong to the density stage: a downgrade hides them there, an upgrade
// shows them there. But the stage before it (materials: shader type, fog, environment map)
// changes the shader programs of everything that is visible, so a detail that is still shown
// (going down) or already shown (going up) would be compiled with programs that are thrown away
// one stage later. While the two stages are out of step, the details are therefore not drawn at
// all, and nothing is compiled for them. Pure: it only reads and writes `visible` of the meshes it
// is given.

/**
 * createDetailHold() → { sync(meshes, ready), restore(), size }.
 * `sync(meshes, ready)`: with ready = false, every visible mesh is hidden and remembered; with
 * ready = true the remembered ones are shown again.
 * `restore()`: shows the remembered ones again at once. Call it before code that decides the
 * visibility itself (density stage, new obstacles) so that this code sees the true state.
 */
export function createDetailHold() {
  const held = new Set();

  function restore() {
    for (const mesh of held) mesh.visible = true;
    held.clear();
  }

  return {
    sync(meshes, ready) {
      if (ready) {
        restore();
        return;
      }
      for (const mesh of meshes) {
        if (mesh.visible) {
          held.add(mesh);
          mesh.visible = false;
        }
      }
    },
    restore,
    /** Number of meshes that are held back now. */
    get size() {
      return held.size;
    },
  };
}
