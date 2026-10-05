package app.zoeshorsefarm.render.filament.math

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mat4OpsTest {
    private fun assertMatrixEquals(
        expected: FloatArray,
        actual: FloatArray,
        eps: Float = 1e-5f,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= eps, "element $i: expected ${expected[i]} but was ${actual[i]}")
        }
    }

    private fun translation(
        x: Float,
        y: Float,
        z: Float,
    ) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    private fun scale(
        x: Float,
        y: Float,
        z: Float,
    ) = floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun `identity writes the identity matrix at the offset`() {
        val out = FloatArray(20) { 7f }
        Mat4Ops.identity(out, 2)
        assertEquals(7f, out[1])
        assertEquals(1f, out[2])
        assertEquals(0f, out[3])
        assertEquals(1f, out[2 + 15])
        assertEquals(7f, out[19])
    }

    @Test
    fun `multiply applies the right matrix first`() {
        val out = FloatArray(16)
        // translate after scaling: a point (1, 1, 1) becomes (2 + 10, 3 + 20, 4 + 30)
        Mat4Ops.multiply(translation(10f, 20f, 30f), 0, scale(2f, 3f, 4f), 0, out, 0)
        val p = Mat4Ops.transformPoint(out, 0, 1f, 1f, 1f, FloatArray(3))
        assertMatrixEquals(floatArrayOf(12f, 23f, 34f), p)
    }

    @Test
    fun `multiply is safe when the output aliases an input`() {
        val a = translation(1f, 2f, 3f)
        val b = scale(2f, 2f, 2f)
        val expected = FloatArray(16)
        Mat4Ops.multiply(a, 0, b, 0, expected, 0)
        Mat4Ops.multiply(a, 0, b, 0, a, 0)
        assertMatrixEquals(expected, a)
    }

    @Test
    fun `multiply reads and writes at offsets`() {
        val packed = FloatArray(48)
        translation(1f, 0f, 0f).copyInto(packed, 16)
        scale(3f, 3f, 3f).copyInto(packed, 32)
        Mat4Ops.multiply(packed, 16, packed, 32, packed, 0)
        val p = Mat4Ops.transformPoint(packed, 0, 1f, 0f, 0f, FloatArray(3))
        assertMatrixEquals(floatArrayOf(4f, 0f, 0f), p)
    }

    @Test
    fun `invert gives the matrix that undoes a transform`() {
        val m = FloatArray(16)
        Mat4Ops.multiply(translation(5f, -2f, 9f), 0, scale(2f, 4f, 0.5f), 0, m, 0)
        val inv = FloatArray(16)
        assertTrue(Mat4Ops.invert(m, 0, inv, 0))
        val product = FloatArray(16)
        Mat4Ops.multiply(m, 0, inv, 0, product, 0)
        val identity = FloatArray(16).also { Mat4Ops.identity(it, 0) }
        assertMatrixEquals(identity, product)
    }

    @Test
    fun `invert of a rotation is its transpose`() {
        // 90 degrees about Y
        val rotation = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val inv = FloatArray(16)
        assertTrue(Mat4Ops.invert(rotation, 0, inv, 0))
        val p = Mat4Ops.transformPoint(inv, 0, 0f, 0f, -1f, FloatArray(3))
        assertMatrixEquals(floatArrayOf(1f, 0f, 0f), p)
    }

    @Test
    fun `invert handles a general matrix with shear and a projective row`() {
        val m =
            floatArrayOf(
                2f,
                0.5f,
                0f,
                0.1f,
                -1f,
                3f,
                0.25f,
                0f,
                0.3f,
                -0.7f,
                1.5f,
                0.2f,
                4f,
                -2f,
                6f,
                1f,
            )
        val inv = FloatArray(16)
        assertTrue(Mat4Ops.invert(m, 0, inv, 0))
        val product = FloatArray(16)
        Mat4Ops.multiply(inv, 0, m, 0, product, 0)
        val identity = FloatArray(16).also { Mat4Ops.identity(it, 0) }
        assertMatrixEquals(identity, product, 1e-4f)
    }

    @Test
    fun `invert reports a singular matrix and leaves the output alone`() {
        val singular = scale(1f, 0f, 1f)
        val out = FloatArray(16) { 9f }
        assertFalse(Mat4Ops.invert(singular, 0, out, 0))
        assertEquals(9f, out[0])
    }

    @Test
    fun `invert is safe when the output aliases the input`() {
        val m = translation(1f, 2f, 3f)
        assertTrue(Mat4Ops.invert(m, 0, m, 0))
        assertMatrixEquals(translation(-1f, -2f, -3f), m)
    }

    @Test
    fun `transformPoint writes the translated point into the output`() {
        val out = FloatArray(3)
        Mat4Ops.transformPoint(translation(1f, 2f, 3f), 0, 1f, 1f, 1f, out)
        assertMatrixEquals(floatArrayOf(2f, 3f, 4f), out)
    }
}
