package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.math.Mat4Ops

/** Axis aligned bounding box; `min > max` on any axis means empty. */
class Aabb(
    val min: FloatArray = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    val max: FloatArray = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
) {
    val isEmpty: Boolean get() = min[0] > max[0] || min[1] > max[1] || min[2] > max[2]

    fun expand(
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (x < min[0]) min[0] = x
        if (y < min[1]) min[1] = y
        if (z < min[2]) min[2] = z
        if (x > max[0]) max[0] = x
        if (y > max[1]) max[1] = y
        if (z > max[2]) max[2] = z
    }

    /** Makes the box empty again. */
    fun reset() {
        for (i in 0..2) {
            min[i] = Float.POSITIVE_INFINITY
            max[i] = Float.NEGATIVE_INFINITY
        }
    }

    fun copyFrom(other: Aabb) {
        for (i in 0..2) {
            min[i] = other.min[i]
            max[i] = other.max[i]
        }
    }

    /** Refits the box to the first `vertexCount` positions (xyz each) without allocating. */
    fun setFromPositions(
        positions: FloatArray,
        vertexCount: Int,
    ) {
        reset()
        for (v in 0 until vertexCount) expand(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
    }

    /** Grows the box by `amount` on every side. */
    fun inflate(amount: Float) {
        for (i in 0..2) {
            min[i] -= amount
            max[i] += amount
        }
    }

    /** The box around a sphere. */
    fun setFromSphere(
        x: Float,
        y: Float,
        z: Float,
        radius: Float,
    ) {
        min[0] = x - radius
        min[1] = y - radius
        min[2] = z - radius
        max[0] = x + radius
        max[1] = y + radius
        max[2] = z + radius
    }

    fun center(out: FloatArray): FloatArray {
        for (i in 0..2) out[i] = (min[i] + max[i]) * 0.5f
        return out
    }

    fun halfExtent(out: FloatArray): FloatArray {
        for (i in 0..2) out[i] = (max[i] - min[i]) * 0.5f
        return out
    }

    companion object {
        fun ofPositions(
            positions: FloatArray,
            vertexCount: Int = positions.size / 3,
        ): Aabb {
            val box = Aabb()
            for (v in 0 until vertexCount) {
                box.expand(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
            }
            return box
        }

        /**
         * The box that holds `geometry` at every instance matrix (column-major, 16 floats each).
         * Filament culls all instances of a renderable with one box, so this is what the instanced
         * renderable must be given.
         */
        fun ofInstances(
            geometry: Aabb,
            matrices: FloatArray,
            count: Int,
        ): Aabb {
            val box = Aabb()
            if (geometry.isEmpty) return box
            val corner = FloatArray(3)
            for (i in 0 until count) {
                for (c in 0 until 8) {
                    val x = if (c and 1 == 0) geometry.min[0] else geometry.max[0]
                    val y = if (c and 2 == 0) geometry.min[1] else geometry.max[1]
                    val z = if (c and 4 == 0) geometry.min[2] else geometry.max[2]
                    Mat4Ops.transformPoint(matrices, i * 16, x, y, z, corner)
                    box.expand(corner[0], corner[1], corner[2])
                }
            }
            return box
        }
    }
}
