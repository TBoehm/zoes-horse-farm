package app.zoeshorsefarm.scene.math

/** 3x3 matrix, column-major like three.js `Matrix3` (only what normal transforms need). */
class Mat3 {
    /** Column-major elements. */
    val e: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** Arguments are in row-major order, like in three.js. */
    fun set(
        n11: Double,
        n12: Double,
        n13: Double,
        n21: Double,
        n22: Double,
        n23: Double,
        n31: Double,
        n32: Double,
        n33: Double,
    ): Mat3 {
        e[0] = n11
        e[3] = n12
        e[6] = n13
        e[1] = n21
        e[4] = n22
        e[7] = n23
        e[2] = n31
        e[5] = n32
        e[8] = n33
        return this
    }

    fun identity(): Mat3 = set(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun copy(m: Mat3): Mat3 {
        m.e.copyInto(e)
        return this
    }

    fun clone(): Mat3 = Mat3().copy(this)

    fun setFromMatrix4(m: Mat4): Mat3 {
        val me = m.e
        return set(me[0], me[4], me[8], me[1], me[5], me[9], me[2], me[6], me[10])
    }

    fun invert(): Mat3 {
        val n11 = e[0]
        val n21 = e[1]
        val n31 = e[2]
        val n12 = e[3]
        val n22 = e[4]
        val n32 = e[5]
        val n13 = e[6]
        val n23 = e[7]
        val n33 = e[8]
        val t11 = n33 * n22 - n32 * n23
        val t12 = n32 * n13 - n33 * n12
        val t13 = n23 * n12 - n22 * n13
        val det = n11 * t11 + n21 * t12 + n31 * t13
        if (det == 0.0) return set(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val detInv = 1 / det
        e[0] = t11 * detInv
        e[1] = (n31 * n23 - n33 * n21) * detInv
        e[2] = (n32 * n21 - n31 * n22) * detInv
        e[3] = t12 * detInv
        e[4] = (n33 * n11 - n31 * n13) * detInv
        e[5] = (n31 * n12 - n32 * n11) * detInv
        e[6] = t13 * detInv
        e[7] = (n21 * n13 - n23 * n11) * detInv
        e[8] = (n22 * n11 - n21 * n12) * detInv
        return this
    }

    fun transpose(): Mat3 {
        var tmp = e[1]
        e[1] = e[3]
        e[3] = tmp
        tmp = e[2]
        e[2] = e[6]
        e[6] = tmp
        tmp = e[5]
        e[5] = e[7]
        e[7] = tmp
        return this
    }

    /** Inverse transpose of the upper 3x3 of `m`: transforms normals. */
    fun getNormalMatrix(m: Mat4): Mat3 = setFromMatrix4(m).invert().transpose()
}
