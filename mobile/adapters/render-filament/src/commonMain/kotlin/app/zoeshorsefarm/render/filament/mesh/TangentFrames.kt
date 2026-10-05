package app.zoeshorsefarm.render.filament.mesh

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Normals to Filament tangent frames.
 *
 * Filament does not read normals: the `TANGENTS` vertex attribute holds the tangent frame
 * (tangent, bitangent, normal) as one unit quaternion that rotates the identity frame, stored as
 * four normalized 16 bit integers. The shader rebuilds the normal as `q * (0, 0, 1)`, the tangent as
 * `q * (1, 0, 0)` and the bitangent as `q * (0, 1, 0) * sign(w)`. A negative `w` therefore marks a
 * mirrored (left handed) frame. `w` is never allowed to reach 0 for that reason.
 *
 * This is pure maths (the filament-kmp `SurfaceOrientation` needs the native library, which unit
 * tests cannot load), and it works the same on every target.
 */
object TangentFrames {
    /** Smallest |w| that still survives a conversion to a signed normalized 16 bit integer. */
    const val MIN_W = 1f / 32767f

    private const val EPSILON = 1e-8f

    /** Writes the frame quaternion (x, y, z, w) of a vertex with this normal into `out` at `offset`. */
    fun fromNormal(
        nx: Float,
        ny: Float,
        nz: Float,
        out: FloatArray,
        offset: Int,
    ) {
        var x = nx
        var y = ny
        var z = nz
        val length = sqrt(x * x + y * y + z * z)
        if (length < EPSILON) {
            x = 0f
            y = 0f
            z = 1f
        } else {
            x /= length
            y /= length
            z /= length
        }
        // "Building an orthonormal basis, revisited" (Duff et al. 2017): branch free and stable
        val sign = if (z >= 0f) 1f else -1f
        val a = -1f / (sign + z)
        val b = x * y * a
        val tx = 1f + sign * x * x * a
        val ty = sign * b
        val tz = -sign * x
        val bx = b
        val by = sign + y * y * a
        val bz = -y
        writeQuaternion(tx, ty, tz, bx, by, bz, x, y, z, mirrored = false, out, offset)
    }

    /** One quaternion per vertex (4 floats each) from tightly packed normals (3 floats each). */
    fun fromNormals(
        normals: FloatArray,
        vertexCount: Int,
    ): FloatArray {
        val out = FloatArray(vertexCount * 4)
        for (v in 0 until vertexCount) {
            fromNormal(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2], out, v * 4)
        }
        return out
    }

    /**
     * Frames whose tangent follows the direction of increasing u (needed by normal maps). The per
     * triangle tangents are accumulated per vertex (Lengyel, "Computing Tangent Space Basis
     * Vectors"), made perpendicular to the normal, and mirrored uvs give a negative `w`. Vertices
     * without usable uvs get an arbitrary frame, which is fine for everything but normal maps.
     * `indices` null means consecutive triangles.
     */
    fun fromNormalsAndUvs(
        positions: FloatArray,
        normals: FloatArray,
        uvs: FloatArray,
        indices: IntArray?,
    ): FloatArray {
        val vertexCount = positions.size / 3
        val tan = FloatArray(vertexCount * 3)
        val bitan = FloatArray(vertexCount * 3)
        val triangleCount = (indices?.size ?: vertexCount) / 3
        for (t in 0 until triangleCount) {
            val i0 = indices?.get(t * 3) ?: (t * 3)
            val i1 = indices?.get(t * 3 + 1) ?: (t * 3 + 1)
            val i2 = indices?.get(t * 3 + 2) ?: (t * 3 + 2)
            accumulateTriangle(positions, uvs, i0, i1, i2, tan, bitan)
        }
        val out = FloatArray(vertexCount * 4)
        for (v in 0 until vertexCount) {
            frameForVertex(normals, tan, bitan, v, out)
        }
        return out
    }

    /** Converts quaternion components in [-1, 1] to signed normalized 16 bit integers. */
    fun packSnorm16(values: FloatArray): ShortArray {
        val out = ShortArray(values.size)
        for (i in values.indices) {
            out[i] = (max(-1f, min(1f, values[i])) * 32767f).roundToInt().toShort()
        }
        return out
    }

    private fun accumulateTriangle(
        p: FloatArray,
        uv: FloatArray,
        i0: Int,
        i1: Int,
        i2: Int,
        tan: FloatArray,
        bitan: FloatArray,
    ) {
        val e1x = p[i1 * 3] - p[i0 * 3]
        val e1y = p[i1 * 3 + 1] - p[i0 * 3 + 1]
        val e1z = p[i1 * 3 + 2] - p[i0 * 3 + 2]
        val e2x = p[i2 * 3] - p[i0 * 3]
        val e2y = p[i2 * 3 + 1] - p[i0 * 3 + 1]
        val e2z = p[i2 * 3 + 2] - p[i0 * 3 + 2]
        val du1 = uv[i1 * 2] - uv[i0 * 2]
        val dv1 = uv[i1 * 2 + 1] - uv[i0 * 2 + 1]
        val du2 = uv[i2 * 2] - uv[i0 * 2]
        val dv2 = uv[i2 * 2 + 1] - uv[i0 * 2 + 1]
        val r = du1 * dv2 - du2 * dv1
        if (r > -EPSILON && r < EPSILON) return
        val f = 1f / r
        val sx = (e1x * dv2 - e2x * dv1) * f
        val sy = (e1y * dv2 - e2y * dv1) * f
        val sz = (e1z * dv2 - e2z * dv1) * f
        val tx = (e2x * du1 - e1x * du2) * f
        val ty = (e2y * du1 - e1y * du2) * f
        val tz = (e2z * du1 - e1z * du2) * f
        for (i in intArrayOf(i0, i1, i2)) {
            tan[i * 3] += sx
            tan[i * 3 + 1] += sy
            tan[i * 3 + 2] += sz
            bitan[i * 3] += tx
            bitan[i * 3 + 1] += ty
            bitan[i * 3 + 2] += tz
        }
    }

    private fun frameForVertex(
        normals: FloatArray,
        tan: FloatArray,
        bitan: FloatArray,
        v: Int,
        out: FloatArray,
    ) {
        var nx = normals[v * 3]
        var ny = normals[v * 3 + 1]
        var nz = normals[v * 3 + 2]
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < EPSILON) {
            fromNormal(0f, 0f, 1f, out, v * 4)
            return
        }
        nx /= nl
        ny /= nl
        nz /= nl
        // Gram-Schmidt: the tangent perpendicular to the normal
        var tx = tan[v * 3]
        var ty = tan[v * 3 + 1]
        var tz = tan[v * 3 + 2]
        val d = nx * tx + ny * ty + nz * tz
        tx -= nx * d
        ty -= ny * d
        tz -= nz * d
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < EPSILON) {
            fromNormal(nx, ny, nz, out, v * 4)
            return
        }
        tx /= tl
        ty /= tl
        tz /= tl
        // right handed bitangent n x t; the accumulated uv bitangent says whether it must flip
        val bx = ny * tz - nz * ty
        val by = nz * tx - nx * tz
        val bz = nx * ty - ny * tx
        val mirrored = bx * bitan[v * 3] + by * bitan[v * 3 + 1] + bz * bitan[v * 3 + 2] < 0f
        writeQuaternion(tx, ty, tz, bx, by, bz, nx, ny, nz, mirrored, out, v * 4)
    }

    /** Quaternion of the rotation whose columns are t, b and n (a right handed frame). */
    @Suppress("LongParameterList")
    private fun writeQuaternion(
        tx: Float,
        ty: Float,
        tz: Float,
        bx: Float,
        by: Float,
        bz: Float,
        nx: Float,
        ny: Float,
        nz: Float,
        mirrored: Boolean,
        out: FloatArray,
        offset: Int,
    ) {
        // rotation matrix entries m[row][col] with columns t, b, n
        val m00 = tx
        val m10 = ty
        val m20 = tz
        val m01 = bx
        val m11 = by
        val m21 = bz
        val m02 = nx
        val m12 = ny
        val m22 = nz
        var x: Float
        var y: Float
        var z: Float
        var w: Float
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = 0.5f / sqrt(trace + 1f)
            w = 0.25f / s
            x = (m21 - m12) * s
            y = (m02 - m20) * s
            z = (m10 - m01) * s
        } else if (m00 > m11 && m00 > m22) {
            val s = 2f * sqrt(1f + m00 - m11 - m22)
            w = (m21 - m12) / s
            x = 0.25f * s
            y = (m01 + m10) / s
            z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = 2f * sqrt(1f + m11 - m00 - m22)
            w = (m02 - m20) / s
            x = (m01 + m10) / s
            y = 0.25f * s
            z = (m12 + m21) / s
        } else {
            val s = 2f * sqrt(1f + m22 - m00 - m11)
            w = (m10 - m01) / s
            x = (m02 + m20) / s
            y = (m12 + m21) / s
            z = 0.25f * s
        }
        if (w < 0f) {
            x = -x
            y = -y
            z = -z
            w = -w
        }
        if (w < MIN_W) {
            // keep the quaternion normalized while lifting w
            val k = sqrt(max(0f, 1f - MIN_W * MIN_W) / max(EPSILON, x * x + y * y + z * z))
            x *= k
            y *= k
            z *= k
            w = MIN_W
        }
        val sign = if (mirrored) -1f else 1f
        out[offset] = x * sign
        out[offset + 1] = y * sign
        out[offset + 2] = z * sign
        out[offset + 3] = w * sign
    }
}
