package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.math.Mat4

/** The last transform sent to Filament, to send a new one only when the node moved. */
internal class TransformCache {
    /** The matrix as floats after the last [refresh]. */
    val current = FloatArray(MATRIX_SIZE)
    private val last = FloatArray(MATRIX_SIZE)
    private var valid = false

    /** Converts `matrix` into [current]; true if it differs from the matrix of the last call that returned true. */
    fun refresh(matrix: Mat4): Boolean {
        matrix.toFloatArray(current)
        return commit()
    }

    /** Like [refresh] for a matrix that is already in [current]. */
    fun commit(): Boolean {
        if (valid && current.contentEquals(last)) return false
        current.copyInto(last)
        valid = true
        return true
    }

    /** The next [refresh] reports a change. */
    fun invalidate() {
        valid = false
    }

    private companion object {
        const val MATRIX_SIZE = 16
    }
}
