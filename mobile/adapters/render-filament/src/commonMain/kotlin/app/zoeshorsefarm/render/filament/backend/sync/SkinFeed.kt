package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.mesh.SkinPalette
import app.zoeshorsefarm.scene.graph.SkinnedMesh

/**
 * The bone matrices of one `SkinnedMesh` in the form Filament skins with. `Skeleton.update()` leaves
 * `boneWorld * boneInverse` in `boneMatrices`; the palette adds the bind matrices
 * (`bindInverse * boneMatrix * bind`, see [SkinPalette]). Reuses its arrays, nothing is allocated
 * per frame.
 */
internal class SkinFeed(
    private val mesh: SkinnedMesh,
) {
    private var palette: SkinPalette? = null
    private var out = FloatArray(0)
    private val bind = FloatArray(MATRIX_SIZE)
    private val bindInverse = FloatArray(MATRIX_SIZE)

    /** Bones the renderable skins with; 0 if the mesh has no usable skeleton. */
    val boneCount: Int get() = MaterialMapping.boneCountOf(mesh)

    /** The palette for the current pose, or null without a skeleton. */
    fun compute(): FloatArray? {
        val skeleton = mesh.skeleton ?: return null
        val bones = boneCount
        if (bones == 0) return null
        var current = palette
        if (current == null || current.boneCount != bones) {
            current = SkinPalette(bones)
            palette = current
            out = FloatArray(bones * MATRIX_SIZE)
        }
        mesh.bindMatrix.toFloatArray(bind)
        mesh.bindMatrixInverse.toFloatArray(bindInverse)
        current.fromBoneMatrices(bindInverse, bind, skeleton.boneMatrices, out)
        return out
    }

    private companion object {
        const val MATRIX_SIZE = 16
    }
}
