package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.graph.Node

/**
 * Holds back the meshes of the optional scenery details (flowers, tufts, birds, bunting, flower
 * boxes, props) while a level change is between its material stage and its density stage
 * (rule 4). Those meshes belong to the density stage: a downgrade hides them there, an upgrade
 * shows them there. But the stage before it (materials: shader type, fog, environment map)
 * changes the shader programs of everything that is visible, so a detail that is still shown
 * (going down) or already shown (going up) would be compiled with programs that are thrown away
 * one stage later. While the two stages are out of step, the details are therefore not drawn at
 * all, and nothing is compiled for them. Pure: it only reads and writes `visible` of the meshes it
 * is given.
 */
class DetailHold {
    private val held = LinkedHashSet<Node>()

    /** Number of meshes that are held back now. */
    val size: Int get() = held.size

    /**
     * With `ready = false`, every visible mesh is hidden and remembered; with `ready = true` the
     * remembered ones are shown again.
     */
    fun sync(
        meshes: Iterable<Node>,
        ready: Boolean,
    ) {
        if (ready) {
            restore()
            return
        }
        for (mesh in meshes) {
            if (mesh.visible) {
                held.add(mesh)
                mesh.visible = false
            }
        }
    }

    /**
     * Shows the remembered ones again at once. Call it before code that decides the visibility
     * itself (density stage, new obstacles) so that this code sees the true state.
     */
    fun restore() {
        for (mesh in held) mesh.visible = true
        held.clear()
    }

    /** Is this mesh held back now? (Hidden for a moment, not for good.) */
    fun has(mesh: Node): Boolean = mesh in held
}
