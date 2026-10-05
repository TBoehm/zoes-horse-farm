package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.FLOWER_COLORS
import app.zoeshorsefarm.view3d.Patch
import app.zoeshorsefarm.view3d.buildFlowerGeometry
import app.zoeshorsefarm.view3d.jsRound
import app.zoeshorsefarm.view3d.patchBlossoms
import app.zoeshorsefarm.view3d.planMeadow
import app.zoeshorsefarm.view3d.terrainHeight
import kotlin.math.max
import kotlin.math.min

// Meadow flowers: one instanced mesh of small flowers in patches, bending in the wind. Only the
// levels with `flowers` > 0 draw it (the mesh is hidden otherwise and never uploaded to the GPU).

/** `userData` key of an instanced mesh: how many instances it has in total (`count` is the visible share). */
const val USER_TOTAL = "total"

/**
 * The flowers of the meadow. [setDensity] takes the share of the flowers that is drawn (the first
 * ones, which are spread over all patches).
 */
class Meadow(
    materialFactory: MaterialFactory,
    wind: Wind,
    seed: Int = 11,
) {
    val mats: MaterialPair
    val mesh: InstancedMesh
    val patches: List<Patch>

    init {
        val rng = createRng(seed + 7)
        val plan = planMeadow(rng)
        patches = plan.patches
        mats = materialFactory("flowers", MaterialParams(vertexColors = true, roughness = 1.0, side = Side.DOUBLE))
        patchBlossoms(mats.standard, wind)
        patchBlossoms(mats.lambert, wind)

        val flowers = plan.flowers
        mesh = InstancedMesh(buildFlowerGeometry(), mats.standard, max(1, flowers.size))
        mesh.name = "flowers"
        val m = Mat4()
        val q = Quat()
        val s = Vec3()
        val p = Vec3()
        val c = Color()
        flowers.forEachIndexed { i, f ->
            p.set(f.x, terrainHeight(f.x, f.z), f.z)
            q.setFromAxisAngle(Node.DEFAULT_UP, f.yaw)
            s.setScalar(f.scale)
            mesh.setMatrixAt(i, m.compose(p, q, s))
            // a little brightness variation, so a patch is not one flat colour
            c.set(FLOWER_COLORS[f.color].hex).multiplyScalar(0.9 + rng() * 0.2)
            mesh.setColorAt(i, c)
        }
        mesh.instanceMatrix.needsUpdate = true
        mesh.instanceColor?.needsUpdate = true
        mesh.computeBoundingSphere()
        mesh.userData[USER_TOTAL] = flowers.size
        mesh.count = 0
        mesh.visible = false
    }

    /** [share] in [0, 1]: the share of the flowers that is drawn. */
    fun setDensity(share: Double) {
        val total = mesh.userData[USER_TOTAL] as Int
        mesh.count = jsRound(total * min(1.0, max(0.0, share)))
        mesh.visible = mesh.count > 0
    }
}
