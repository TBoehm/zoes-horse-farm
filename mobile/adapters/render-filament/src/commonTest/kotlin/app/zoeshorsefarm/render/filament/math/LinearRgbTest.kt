package app.zoeshorsefarm.render.filament.math

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearRgbTest {
    private fun assertNear(
        expected: Float,
        actual: Float,
        eps: Float = 1e-4f,
    ) {
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")
    }

    @Test
    fun `black and white map to themselves`() {
        assertEquals(LinearRgb(0f, 0f, 0f), LinearRgb.fromSrgbHex(0x000000))
        val white = LinearRgb.fromSrgbHex(0xffffff)
        assertNear(1f, white.r)
        assertNear(1f, white.g)
        assertNear(1f, white.b)
    }

    @Test
    fun `mid grey in sRGB is darker in linear space`() {
        // sRGB 0x80 = 0.50196 -> 0.21586 linear (three.js ColorManagement)
        val grey = LinearRgb.fromSrgbHex(0x808080)
        assertNear(0.21586f, grey.r)
    }

    @Test
    fun `values in the linear segment use the divide by 12 point 92 branch`() {
        assertNear(0.02f / 12.92f, LinearRgb.srgbToLinear(0.02f), 1e-7f)
    }

    @Test
    fun `channels are decoded independently`() {
        val c = LinearRgb.fromSrgbHex(0xff8000)
        assertNear(1f, c.r)
        assertNear(0.21586f, c.g)
        assertNear(0f, c.b)
    }

    @Test
    fun `scaled multiplies every channel`() {
        val c = LinearRgb(0.5f, 0.25f, 1f).scaled(2f)
        assertEquals(LinearRgb(1f, 0.5f, 2f), c)
    }
}
