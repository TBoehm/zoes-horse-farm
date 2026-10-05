package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.graph.Traversable
import kotlin.math.max

/** Draw calls and triangles of one pass. */
data class PassStats(
    val calls: Int,
    val triangles: Int,
)

/**
 * Draw calls and triangles of a scene as a renderer would submit them (rule 3: the budget per
 * quality level). No GPU: the numbers come from the scene graph, so JVM tests can compare the
 * levels. Frustum culling is ignored, so the numbers are an upper bound of one frame.
 *
 * [shadowPass] is what the objects that cast shadows cost again in the shadow pass.
 */
data class SceneStats(
    val calls: Int,
    val triangles: Int,
    val instances: Int,
    val shadowPass: PassStats,
) {
    companion object {
        /**
         * Counts the visible meshes and point clouds: draw calls (one per mesh, one per material group
         * of a mesh with a material list), triangles (times the instance count of instanced meshes),
         * sprites (a quad) and points (one call, no triangles).
         */
        fun of(root: Traversable): SceneStats {
            var calls = 0
            var triangles = 0
            var instances = 0
            var shadowCalls = 0
            var shadowTriangles = 0
            root.traverseVisible { node ->
                if (node is Sprite) {
                    calls += 1
                    triangles += 2
                } else if (node is Points) {
                    // points are one draw call without triangles (hoof dust)
                    calls += 1
                } else if (node is Mesh) {
                    val copies = if (node is InstancedMesh) node.count else 1
                    val tris = node.geometry.drawnTriangles.toLong() * copies
                    if (copies > 0 && tris > 0) {
                        val groups = if (node.materialArray != null) max(1, node.geometry.groups.size) else 1
                        calls += groups
                        triangles += tris.toInt()
                        if (node is InstancedMesh) instances += copies
                        if (node.castShadow) {
                            shadowCalls += groups
                            shadowTriangles += tris.toInt()
                        }
                    }
                }
            }
            return SceneStats(calls, triangles, instances, PassStats(shadowCalls, shadowTriangles))
        }
    }
}
