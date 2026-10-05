package app.zoeshorsefarm.scene.math

import app.zoeshorsefarm.scene.assertNear
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorMathTest {
    private fun rgb(c: Color) = doubleArrayOf(c.r, c.g, c.b)

    @Test
    fun `hex is read as sRGB and stored linear`() {
        val c = Color(0xffd21f)
        assertNear(doubleArrayOf(1.0, 0.6444796819634361, 0.013702083043526807), rgb(c))
        assertEquals(16765471, c.getHex())
    }

    @Test
    fun `setRGB with sRGB converts to linear`() {
        val c = Color().setRGB(0.5, 0.25, 0.75, ColorSpace.SRGB)
        assertNear(doubleArrayOf(0.2140411404715882, 0.050876088164650994, 0.5225215539594343), rgb(c))
    }

    @Test
    fun `setRGB defaults to the linear working space`() {
        assertNear(doubleArrayOf(0.5, 0.25, 0.75), rgb(Color().setRGB(0.5, 0.25, 0.75)))
    }

    @Test
    fun `grey 0x808080 round trips through the transfer function`() {
        assertEquals(8421504, Color(0x808080).getHex())
    }

    @Test
    fun `HSL conversions`() {
        val hsl = Hsl()
        Color(0x3f7fcf).getHSL(hsl)
        assertNear(
            doubleArrayOf(0.6194969935606539, 0.8524298530209641, 0.33683347882244186),
            doubleArrayOf(hsl.h, hsl.s, hsl.l),
        )
        assertNear(
            doubleArrayOf(0.27999999999999997, 0.6000000000000001, 0.19999999999999996),
            rgb(Color().setHSL(0.3, 0.5, 0.4)),
        )
    }

    @Test
    fun `CSS styles`() {
        assertNear(
            doubleArrayOf(0.14731899923456854, 0.35469206148347, 0.05925352110992743),
            rgb(Color("hsla(95, 40%, 45%, 0.5)")),
        )
        assertNear(
            doubleArrayOf(0.0030352698352941175, 0.5775804404214573, 0.012983032338510335),
            rgb(Color("rgb(10, 200, 30)")),
        )
        assertNear(doubleArrayOf(1.0, 0.4019777798219466, 0.0), rgb(Color("#fa0")))
        assertEquals("rgb(63,127,207)", Color(0x3f7fcf).getStyle())
    }

    @Test
    fun `lerp and lerpHSL`() {
        assertNear(doubleArrayOf(0.7, 0.0, 0.3), rgb(Color(0xff0000).lerp(Color(0x0000ff), 0.3)), 1e-12)
        assertNear(
            doubleArrayOf(0.8000000000000005, 1.0, 0.0),
            rgb(Color(0xff0000).lerpHSL(Color(0x0000ff), 0.3)),
            1e-12,
        )
    }

    @Test
    fun `MathUtils helpers`() {
        assertNear(0.216, MathUtils.smoothstep(0.3, 0.0, 1.0))
        assertNear(0.16308000000000003, MathUtils.smootherstep(0.3, 0.0, 1.0))
        assertNear(0.5, MathUtils.euclideanModulo(-1.5, 1.0))
        assertEquals(1024, MathUtils.ceilPowerOfTwo(513))
        assertEquals(512, MathUtils.ceilPowerOfTwo(512))
        assertEquals(512, MathUtils.floorPowerOfTwo(513))
        assertNear(0.7000000000000002, MathUtils.pingpong(1.3, 1.0))
        assertNear(1.8126924692201818, MathUtils.damp(0.0, 10.0, 2.0, 0.1))
        assertNear(150.0, MathUtils.mapLinear(5.0, 0.0, 10.0, 100.0, 200.0))
        assertNear(0.75, MathUtils.inverseLerp(2.0, 4.0, 3.5))
        assertNear(kotlin.math.PI, MathUtils.degToRad(180.0))
        assertNear(90.0, MathUtils.radToDeg(kotlin.math.PI / 2))
    }
}
