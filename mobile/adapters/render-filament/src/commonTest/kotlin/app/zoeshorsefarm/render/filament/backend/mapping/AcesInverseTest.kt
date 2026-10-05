package app.zoeshorsefarm.render.filament.backend.mapping

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcesInverseTest {
    private fun near(
        expected: Float,
        actual: Float,
        eps: Float = 0.002f,
    ) = assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun `black stays black and the forward curve of mid grey is about 0 point 8`() {
        val black = AcesInverse.forward(floatArrayOf(0f, 0f, 0f))
        for (c in 0..2) near(0f, black[c], 0.001f)
        // three.js at exposure 1: linear 0.18 comes out as about 0.213 (sRGB 0.5)
        near(0.213f, AcesInverse.forward(floatArrayOf(0.18f, 0.18f, 0.18f))[1], 0.005f)
    }

    @Test
    fun `the inverse undoes the forward curve for the fog colours of the game`() {
        for (hex in listOf(0xcfe2ee, 0xb8c7bf, 0x3f7fcf, 0x5d6e3e, 0x808080)) {
            val linear =
                app.zoeshorsefarm.scene.math.Color(hex).let {
                    floatArrayOf(it.r.toFloat(), it.g.toFloat(), it.b.toFloat())
                }
            val hdr = AcesInverse.inverse(linear)
            val back = AcesInverse.forward(hdr)
            for (c in 0..2) near(linear[c], back[c], 0.004f)
        }
    }

    @Test
    fun `the exposure scales the hdr colour`() {
        val one = AcesInverse.inverse(floatArrayOf(0.4f, 0.5f, 0.6f), 1.0)
        val two = AcesInverse.inverse(floatArrayOf(0.4f, 0.5f, 0.6f), 2.0)
        for (c in 0..2) near(one[c] / 2f, two[c], 0.0005f)
        val back = AcesInverse.forward(two, 2.0)
        near(0.5f, back[1], 0.004f)
    }

    @Test
    fun `white and out of range values stay finite`() {
        val white = AcesInverse.inverse(floatArrayOf(1f, 1f, 1f))
        assertTrue(white.all { it.isFinite() && it > 0f })
        assertEquals(3, AcesInverse.inverse(floatArrayOf(2f, -1f, 0f)).size)
    }
}
