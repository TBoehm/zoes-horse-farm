package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.math.Mat4Ops
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceMatricesTest {
    private fun translation(
        x: Float,
        y: Float,
        z: Float,
    ) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    @Test
    fun `the identity is recognised`() {
        assertTrue(InstanceMatrices.isIdentity(FloatArray(16).also { Mat4Ops.identity(it, 0) }))
        assertFalse(InstanceMatrices.isIdentity(translation(0f, 0f, 0.001f)))
        val scaled = FloatArray(16).also { Mat4Ops.identity(it, 0) }
        scaled[5] = 2f
        assertFalse(InstanceMatrices.isIdentity(scaled))
    }

    @Test
    fun `bake multiplies the world matrix onto every instance`() {
        val instances = translation(1f, 0f, 0f) + translation(0f, 2f, 0f)
        val out = FloatArray(32)
        InstanceMatrices.bake(translation(10f, 10f, 10f), instances, 2, out)
        assertContentEquals(translation(11f, 10f, 10f) + translation(10f, 12f, 10f), out)
    }

    @Test
    fun `bake only touches the requested instances`() {
        val instances = translation(1f, 0f, 0f) + translation(0f, 2f, 0f)
        val out = FloatArray(32) { -7f }
        InstanceMatrices.bake(translation(1f, 1f, 1f), instances, 1, out)
        assertContentEquals(FloatArray(16) { -7f }, out.copyOfRange(16, 32))
    }
}
