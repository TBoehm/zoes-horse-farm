package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.math.Mat4Ops

/**
 * The instance matrices of an `InstancedMesh` in world space. The scene model gives them relative to
 * the mesh node (three.js: `matrixWorld * instanceMatrix`); the Filament instance data texture holds
 * world matrices, and the renderable keeps an identity transform. A mesh at the origin (the usual
 * case) needs no multiplication.
 */
object InstanceMatrices {
    /** True if the 4x4 matrix (16 floats) is the identity. */
    fun isIdentity(matrix: FloatArray): Boolean {
        for (i in 0 until MATRIX_SIZE) {
            val expected = if (i % DIAGONAL_STEP == 0) 1f else 0f
            if (matrix[i] != expected) return false
        }
        return true
    }

    /** `out[i] = world * instances[i]` for the first `count` instances; `out` may not alias `instances`. */
    fun bake(
        world: FloatArray,
        instances: FloatArray,
        count: Int,
        out: FloatArray,
    ) {
        for (i in 0 until count) Mat4Ops.multiply(world, 0, instances, i * MATRIX_SIZE, out, i * MATRIX_SIZE)
    }

    private const val MATRIX_SIZE = 16
    private const val DIAGONAL_STEP = 5
}
