package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.math.Mat4Ops

/**
 * Bone matrices for Filament's GPU skinning.
 *
 * three.js skins a vertex with `bindInverse * boneWorld * boneInverse * bind * v`, where `bind` is
 * the mesh matrix at bind time and `boneInverse` the inverse of the bone's world matrix at that
 * time. Filament wants one matrix per bone that maps a bind pose vertex of the model to the current
 * pose, so the palette entry is `bindInverse * boneWorld * boneInverse * bind`.
 *
 * One palette is made per skeleton and reused every frame: `compute` allocates nothing.
 */
class SkinPalette(
    val boneCount: Int,
) {
    init {
        require(boneCount in 1..MAX_BONES) { "a skeleton needs 1 to $MAX_BONES bones, not $boneCount" }
    }

    private val scratch = FloatArray(16)

    /**
     * Fills `out` (16 floats per bone, column-major) from the world matrices of the bones
     * (`boneWorlds`) and their inverse bind matrices (`boneInverses`).
     */
    fun compute(
        bindInverse: FloatArray,
        bind: FloatArray,
        boneWorlds: FloatArray,
        boneInverses: FloatArray,
        out: FloatArray,
    ) {
        val needed = boneCount * 16
        require(boneWorlds.size >= needed && boneInverses.size >= needed && out.size >= needed) {
            "bone arrays must hold $boneCount matrices (16 floats each)"
        }
        for (i in 0 until boneCount) {
            val o = i * 16
            Mat4Ops.multiply(boneWorlds, o, boneInverses, o, scratch, 0)
            Mat4Ops.multiply(bindInverse, 0, scratch, 0, scratch, 0)
            Mat4Ops.multiply(scratch, 0, bind, 0, out, o)
        }
    }

    /**
     * Like [compute] for bone matrices that already are `boneWorld * boneInverse` (what the scene model's
     * `Skeleton.update()` leaves in `boneMatrices`): `out[i] = bindInverse * boneMatrices[i] * bind`.
     */
    fun fromBoneMatrices(
        bindInverse: FloatArray,
        bind: FloatArray,
        boneMatrices: FloatArray,
        out: FloatArray,
    ) {
        val needed = boneCount * 16
        require(boneMatrices.size >= needed && out.size >= needed) {
            "bone arrays must hold $boneCount matrices (16 floats each)"
        }
        for (i in 0 until boneCount) {
            val o = i * 16
            Mat4Ops.multiply(bindInverse, 0, boneMatrices, o, scratch, 0)
            Mat4Ops.multiply(scratch, 0, bind, 0, out, o)
        }
    }

    companion object {
        /** The most bones Filament skins one renderable with. */
        const val MAX_BONES = 256

        /** GPU bytes of the bone matrices of a skeleton (a `mat4f` per bone). */
        fun byteSize(boneCount: Int): Long = boneCount * 64L
    }
}
