package app.zoeshorsefarm.render.filament.mesh

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TangentFramesTest {
    private val eps = 1e-4f

    private fun assertNear(
        expected: Float,
        actual: Float,
        message: String = "",
    ) {
        assertTrue(abs(expected - actual) <= eps, "$message expected $expected but was $actual")
    }

    /** What Filament's `toTangentFrame` does with a stored quaternion: the frame's normal axis. */
    private fun normalOf(
        q: FloatArray,
        o: Int = 0,
    ): FloatArray {
        val (x, y, z, w) = floatArrayOf(q[o], q[o + 1], q[o + 2], q[o + 3])
        return floatArrayOf(2f * (x * z + w * y), 2f * (y * z - w * x), 1f - 2f * (x * x + y * y))
    }

    private fun tangentOf(
        q: FloatArray,
        o: Int = 0,
    ): FloatArray {
        val (x, y, z, w) = floatArrayOf(q[o], q[o + 1], q[o + 2], q[o + 3])
        return floatArrayOf(1f - 2f * (y * y + z * z), 2f * (x * y + z * w), 2f * (x * z - y * w))
    }

    /** Bitangent as the Filament shader builds it: the rotated Y axis times the sign of w. */
    private fun bitangentOf(
        q: FloatArray,
        o: Int = 0,
    ): FloatArray {
        val (x, y, z, w) = floatArrayOf(q[o], q[o + 1], q[o + 2], q[o + 3])
        val s = if (w < 0f) -1f else 1f
        return floatArrayOf(
            2f * (x * y - w * z) * s,
            (1f - 2f * (x * x + z * z)) * s,
            2f * (y * z + w * x) * s,
        )
    }

    private fun normalized(
        x: Float,
        y: Float,
        z: Float,
    ): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private val sampleNormals =
        listOf(
            normalized(0f, 0f, 1f),
            normalized(0f, 0f, -1f),
            normalized(0f, 1f, 0f),
            normalized(0f, -1f, 0f),
            normalized(1f, 0f, 0f),
            normalized(-1f, 0f, 0f),
            normalized(1f, 2f, 3f),
            normalized(-0.3f, 0.9f, -0.2f),
            normalized(0.5f, -0.5f, 0.7f),
        )

    @Test
    fun `the quaternion rotates the Z axis onto the normal`() {
        for (n in sampleNormals) {
            val q = FloatArray(4)
            TangentFrames.fromNormal(n[0], n[1], n[2], q, 0)
            val back = normalOf(q)
            for (i in 0..2) assertNear(n[i], back[i], "normal ${n.toList()}")
        }
    }

    @Test
    fun `the quaternion has unit length`() {
        for (n in sampleNormals) {
            val q = FloatArray(4)
            TangentFrames.fromNormal(n[0], n[1], n[2], q, 0)
            assertNear(1f, sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]))
        }
    }

    @Test
    fun `a right handed frame is stored with a positive w`() {
        for (n in sampleNormals) {
            val q = FloatArray(4)
            TangentFrames.fromNormal(n[0], n[1], n[2], q, 0)
            assertTrue(q[3] > 0f, "w of ${n.toList()} was ${q[3]}")
        }
    }

    @Test
    fun `tangent and bitangent are perpendicular to the normal and to each other`() {
        for (n in sampleNormals) {
            val q = FloatArray(4)
            TangentFrames.fromNormal(n[0], n[1], n[2], q, 0)
            val t = tangentOf(q)
            val b = bitangentOf(q)
            assertNear(0f, dot(t, n))
            assertNear(0f, dot(b, n))
            assertNear(0f, dot(t, b))
        }
    }

    @Test
    fun `the frame is right handed so that tangent cross bitangent is the normal`() {
        val n = normalized(1f, 2f, 3f)
        val q = FloatArray(4)
        TangentFrames.fromNormal(n[0], n[1], n[2], q, 0)
        val t = tangentOf(q)
        val b = bitangentOf(q)
        val cross = floatArrayOf(t[1] * b[2] - t[2] * b[1], t[2] * b[0] - t[0] * b[2], t[0] * b[1] - t[1] * b[0])
        for (i in 0..2) assertNear(n[i], cross[i])
    }

    @Test
    fun `fromNormals fills one quaternion per vertex`() {
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f)
        val q = TangentFrames.fromNormals(normals, 3)
        assertEquals(12, q.size)
        val n1 = normalOf(q, 4)
        assertNear(1f, n1[1])
        val n2 = normalOf(q, 8)
        assertNear(1f, n2[0])
    }

    @Test
    fun `a missing normal falls back to the Z axis`() {
        val q = FloatArray(4)
        TangentFrames.fromNormal(0f, 0f, 0f, q, 0)
        val n = normalOf(q)
        assertNear(1f, n[2])
    }

    @Test
    fun `uv derived tangents follow the direction of increasing u`() {
        // a quad in the XY plane, u grows along +X
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val q = TangentFrames.fromNormalsAndUvs(positions, normals, uvs, indices)
        for (v in 0 until 4) {
            val t = tangentOf(q, v * 4)
            val b = bitangentOf(q, v * 4)
            assertNear(1f, t[0], "tangent x of vertex $v")
            assertNear(1f, b[1], "bitangent y of vertex $v")
        }
    }

    @Test
    fun `mirrored uvs store a negative w and keep the bitangent`() {
        // v grows along -Y: the frame (T, B, N) is left handed
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val q = TangentFrames.fromNormalsAndUvs(positions, normals, uvs, indices)
        assertTrue(q[3] < 0f, "w ${q[3]}")
        val b = bitangentOf(q, 0)
        assertNear(-1f, b[1])
        val n = normalOf(q, 0)
        assertNear(1f, n[2])
    }

    @Test
    fun `degenerate uvs fall back to an arbitrary valid frame`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val uvs = FloatArray(6) // every vertex at (0, 0)
        val q = TangentFrames.fromNormalsAndUvs(positions, normals, uvs, intArrayOf(0, 1, 2))
        val n = normalOf(q, 0)
        assertNear(1f, n[2])
        assertNear(1f, sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]))
    }

    @Test
    fun `packSnorm16 scales to the signed 16 bit range and clamps`() {
        val packed = TangentFrames.packSnorm16(floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -2f))
        assertEquals(listOf<Short>(0, 32767, -32767, 16384, 32767, -32767), packed.toList())
    }

    @Test
    fun `w never reaches zero so the handedness bit survives snorm packing`() {
        // normal pointing along -Z gives a rotation of 180 degrees: w would be 0 without the bias
        val q = FloatArray(4)
        TangentFrames.fromNormal(0f, 0f, -1f, q, 0)
        assertTrue(q[3] >= TangentFrames.MIN_W - 1e-7f, "w ${q[3]}")
        val packed = TangentFrames.packSnorm16(q)
        assertTrue(packed[3] > 0, "packed w ${packed[3]}")
    }
}
