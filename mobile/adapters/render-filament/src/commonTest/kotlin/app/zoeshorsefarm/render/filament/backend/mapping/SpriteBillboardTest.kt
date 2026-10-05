package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.math.Mat4Ops
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteBillboardTest {
    private val identity = FloatArray(16).also { Mat4Ops.identity(it, 0) }

    private fun at(
        x: Float,
        y: Float,
        z: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) = floatArrayOf(scaleX, 0f, 0f, 0f, 0f, scaleY, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    private fun transform(
        matrix: FloatArray,
        x: Float,
        y: Float,
    ): List<Float> {
        val out = FloatArray(3)
        Mat4Ops.transformPoint(matrix, 0, x, y, 0f, out)
        return out.toList()
    }

    private fun assertClose(
        expected: List<Float>,
        actual: List<Float>,
    ) {
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) < 1e-5f, "$expected vs $actual")
    }

    @Test
    fun `the quad is a unit square with uvs`() {
        val quad = SpriteBillboard.quad()
        assertEquals(4, quad.vertexCount)
        assertEquals(6, quad.indices?.size)
        assertEquals(8, quad.uvs?.size)
    }

    @Test
    fun `a centred sprite facing the camera sits on its node`() {
        val out = FloatArray(16)
        SpriteBillboard.compose(at(1f, 2f, 3f), identity, 0.5f, 0.5f, 0f, out)
        assertClose(listOf(1f, 2f, 3f), transform(out, 0f, 0f))
        assertClose(listOf(1.5f, 2.5f, 3f), transform(out, 0.5f, 0.5f))
    }

    @Test
    fun `the node scale sizes the quad`() {
        val out = FloatArray(16)
        SpriteBillboard.compose(at(0f, 0f, 0f, scaleX = 2f, scaleY = 4f), identity, 0.5f, 0.5f, 0f, out)
        assertClose(listOf(1f, 2f, 0f), transform(out, 0.5f, 0.5f))
    }

    @Test
    fun `a pivot at the bottom puts the node position on the lower edge`() {
        val out = FloatArray(16)
        SpriteBillboard.compose(at(0f, 1f, 0f, scaleX = 2f, scaleY = 2f), identity, 0.5f, 0f, 0f, out)
        // the bottom centre of the quad is at the node; the top is two units up
        assertClose(listOf(0f, 1f, 0f), transform(out, 0f, -0.5f))
        assertClose(listOf(0f, 3f, 0f), transform(out, 0f, 0.5f))
    }

    @Test
    fun `the quad is turned to the camera right and up axes`() {
        // a camera turned a quarter around y: its right axis is world -z
        val camera = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val out = FloatArray(16)
        SpriteBillboard.compose(at(0f, 0f, 0f), camera, 0.5f, 0.5f, 0f, out)
        assertClose(listOf(0f, 0f, -0.5f), transform(out, 0.5f, 0f))
        assertClose(listOf(0f, 0.5f, 0f), transform(out, 0f, 0.5f))
    }

    @Test
    fun `the rotation turns the quad around the pivot`() {
        val out = FloatArray(16)
        SpriteBillboard.compose(at(0f, 0f, 0f), identity, 0.5f, 0.5f, (PI / 2).toFloat(), out)
        assertClose(listOf(0f, 0.5f, 0f), transform(out, 0.5f, 0f))
    }

    @Test
    fun `the matrix is affine`() {
        val out = FloatArray(16)
        SpriteBillboard.compose(at(1f, 1f, 1f), identity, 0.5f, 0f, 0.3f, out)
        assertEquals(listOf(0f, 0f, 0f, 1f), listOf(out[3], out[7], out[11], out[15]))
    }
}
