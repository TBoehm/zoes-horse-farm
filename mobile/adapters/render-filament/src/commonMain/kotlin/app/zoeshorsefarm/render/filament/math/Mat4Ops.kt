package app.zoeshorsefarm.render.filament.math

import kotlin.math.abs

/**
 * 4x4 matrix helpers on plain `FloatArray`s in column-major order (the layout of three.js
 * `Matrix4.elements` and of Filament's `mat4f`): element (row, col) is at `offset + col * 4 + row`.
 * Nothing here allocates, so the functions are safe in per-frame paths.
 */
object Mat4Ops {
    fun identity(
        out: FloatArray,
        offset: Int,
    ) {
        out.fill(0f, offset, offset + 16)
        out[offset] = 1f
        out[offset + 5] = 1f
        out[offset + 10] = 1f
        out[offset + 15] = 1f
    }

    /** `out = a * b` (b is applied first). `out` may alias `a` or `b`. */
    fun multiply(
        a: FloatArray,
        ao: Int,
        b: FloatArray,
        bo: Int,
        out: FloatArray,
        oo: Int,
    ) {
        val a00 = a[ao]
        val a10 = a[ao + 1]
        val a20 = a[ao + 2]
        val a30 = a[ao + 3]
        val a01 = a[ao + 4]
        val a11 = a[ao + 5]
        val a21 = a[ao + 6]
        val a31 = a[ao + 7]
        val a02 = a[ao + 8]
        val a12 = a[ao + 9]
        val a22 = a[ao + 10]
        val a32 = a[ao + 11]
        val a03 = a[ao + 12]
        val a13 = a[ao + 13]
        val a23 = a[ao + 14]
        val a33 = a[ao + 15]
        // Both inputs are fully read into locals before `out` is written: aliasing is safe.
        val b00 = b[bo]
        val b10 = b[bo + 1]
        val b20 = b[bo + 2]
        val b30 = b[bo + 3]
        val b01 = b[bo + 4]
        val b11 = b[bo + 5]
        val b21 = b[bo + 6]
        val b31 = b[bo + 7]
        val b02 = b[bo + 8]
        val b12 = b[bo + 9]
        val b22 = b[bo + 10]
        val b32 = b[bo + 11]
        val b03 = b[bo + 12]
        val b13 = b[bo + 13]
        val b23 = b[bo + 14]
        val b33 = b[bo + 15]
        out[oo] = a00 * b00 + a01 * b10 + a02 * b20 + a03 * b30
        out[oo + 1] = a10 * b00 + a11 * b10 + a12 * b20 + a13 * b30
        out[oo + 2] = a20 * b00 + a21 * b10 + a22 * b20 + a23 * b30
        out[oo + 3] = a30 * b00 + a31 * b10 + a32 * b20 + a33 * b30
        out[oo + 4] = a00 * b01 + a01 * b11 + a02 * b21 + a03 * b31
        out[oo + 5] = a10 * b01 + a11 * b11 + a12 * b21 + a13 * b31
        out[oo + 6] = a20 * b01 + a21 * b11 + a22 * b21 + a23 * b31
        out[oo + 7] = a30 * b01 + a31 * b11 + a32 * b21 + a33 * b31
        out[oo + 8] = a00 * b02 + a01 * b12 + a02 * b22 + a03 * b32
        out[oo + 9] = a10 * b02 + a11 * b12 + a12 * b22 + a13 * b32
        out[oo + 10] = a20 * b02 + a21 * b12 + a22 * b22 + a23 * b32
        out[oo + 11] = a30 * b02 + a31 * b12 + a32 * b22 + a33 * b32
        out[oo + 12] = a00 * b03 + a01 * b13 + a02 * b23 + a03 * b33
        out[oo + 13] = a10 * b03 + a11 * b13 + a12 * b23 + a13 * b33
        out[oo + 14] = a20 * b03 + a21 * b13 + a22 * b23 + a23 * b33
        out[oo + 15] = a30 * b03 + a31 * b13 + a32 * b23 + a33 * b33
    }

    /**
     * `out = inverse(m)` by 2x2 sub-determinants (Laplace expansion). Returns false for a singular
     * matrix and then leaves `out` untouched. `out` may alias `m`.
     */
    fun invert(
        m: FloatArray,
        mo: Int,
        out: FloatArray,
        oo: Int,
    ): Boolean {
        val a00 = m[mo]
        val a10 = m[mo + 1]
        val a20 = m[mo + 2]
        val a30 = m[mo + 3]
        val a01 = m[mo + 4]
        val a11 = m[mo + 5]
        val a21 = m[mo + 6]
        val a31 = m[mo + 7]
        val a02 = m[mo + 8]
        val a12 = m[mo + 9]
        val a22 = m[mo + 10]
        val a32 = m[mo + 11]
        val a03 = m[mo + 12]
        val a13 = m[mo + 13]
        val a23 = m[mo + 14]
        val a33 = m[mo + 15]
        // 2x2 minors of the top two rows (s) and of the bottom two rows (c)
        val s0 = a00 * a11 - a10 * a01
        val s1 = a00 * a12 - a10 * a02
        val s2 = a00 * a13 - a10 * a03
        val s3 = a01 * a12 - a11 * a02
        val s4 = a01 * a13 - a11 * a03
        val s5 = a02 * a13 - a12 * a03
        val c5 = a22 * a33 - a32 * a23
        val c4 = a21 * a33 - a31 * a23
        val c3 = a21 * a32 - a31 * a22
        val c2 = a20 * a33 - a30 * a23
        val c1 = a20 * a32 - a30 * a22
        val c0 = a20 * a31 - a30 * a21
        val det = s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0
        if (abs(det) < SINGULAR_EPSILON) return false
        val d = 1f / det
        // out(row, col) = out[oo + col * 4 + row]
        out[oo] = (a11 * c5 - a12 * c4 + a13 * c3) * d
        out[oo + 4] = (-a01 * c5 + a02 * c4 - a03 * c3) * d
        out[oo + 8] = (a31 * s5 - a32 * s4 + a33 * s3) * d
        out[oo + 12] = (-a21 * s5 + a22 * s4 - a23 * s3) * d
        out[oo + 1] = (-a10 * c5 + a12 * c2 - a13 * c1) * d
        out[oo + 5] = (a00 * c5 - a02 * c2 + a03 * c1) * d
        out[oo + 9] = (-a30 * s5 + a32 * s2 - a33 * s1) * d
        out[oo + 13] = (a20 * s5 - a22 * s2 + a23 * s1) * d
        out[oo + 2] = (a10 * c4 - a11 * c2 + a13 * c0) * d
        out[oo + 6] = (-a00 * c4 + a01 * c2 - a03 * c0) * d
        out[oo + 10] = (a30 * s4 - a31 * s2 + a33 * s0) * d
        out[oo + 14] = (-a20 * s4 + a21 * s2 - a23 * s0) * d
        out[oo + 3] = (-a10 * c3 + a11 * c1 - a12 * c0) * d
        out[oo + 7] = (a00 * c3 - a01 * c1 + a02 * c0) * d
        out[oo + 11] = (-a30 * s3 + a31 * s1 - a32 * s0) * d
        out[oo + 15] = (a20 * s3 - a21 * s1 + a22 * s0) * d
        return true
    }

    /** Transforms the point (x, y, z) by the affine part of `m` (no perspective divide). */
    fun transformPoint(
        m: FloatArray,
        mo: Int,
        x: Float,
        y: Float,
        z: Float,
        out: FloatArray,
    ): FloatArray {
        out[0] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12]
        out[1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13]
        out[2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14]
        return out
    }

    private const val SINGULAR_EPSILON = 1e-12f
}
