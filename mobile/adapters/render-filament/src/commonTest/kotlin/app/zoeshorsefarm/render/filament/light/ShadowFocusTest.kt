package app.zoeshorsefarm.render.filament.light

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadowFocusTest {
    // the web sun: direction towards the sun, normalized
    private val sunLength = sqrt(0.52f * 0.52f + 0.74f * 0.74f + 0.42f * 0.42f)
    private val sun = floatArrayOf(-0.52f / sunLength, 0.74f / sunLength, -0.42f / sunLength)

    private fun focus(
        mapSize: Int = 2048,
        halfExtent: Float = 24f,
    ) = ShadowFocus(sun[0], sun[1], sun[2], halfExtent, mapSize)

    private fun assertNear(
        expected: Float,
        actual: Float,
        eps: Float = 1e-3f,
    ) {
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")
    }

    /** Position of a world point along the light's right axis, in texels. */
    private fun texelsAlongRight(
        f: ShadowFocus,
        x: Float,
        y: Float,
        z: Float,
    ) = (x * f.rightX + y * f.rightY + z * f.rightZ) / f.texelSize

    private fun texelsAlongUp(
        f: ShadowFocus,
        x: Float,
        y: Float,
        z: Float,
    ) = (x * f.upX + y * f.upY + z * f.upZ) / f.texelSize

    @Test
    fun `a texel is the shadow extent divided by the map size`() {
        assertNear(48f / 2048f, focus(2048).texelSize, 1e-7f)
        assertNear(48f / 1024f, focus(1024).texelSize, 1e-7f)
    }

    @Test
    fun `the light axes are perpendicular to each other and to the light direction`() {
        val f = focus()
        val fwd = floatArrayOf(-sun[0], -sun[1], -sun[2])
        val right = floatArrayOf(f.rightX, f.rightY, f.rightZ)
        val up = floatArrayOf(f.upX, f.upY, f.upZ)

        fun dot(
            a: FloatArray,
            b: FloatArray,
        ) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        assertNear(0f, dot(right, up), 1e-5f)
        assertNear(0f, dot(right, fwd), 1e-5f)
        assertNear(0f, dot(up, fwd), 1e-5f)
        assertNear(1f, dot(right, right), 1e-5f)
        assertNear(1f, dot(up, up), 1e-5f)
    }

    @Test
    fun `the focus snaps to whole texels along both light axes`() {
        val f = focus()
        f.moveTo(1.2345f, 0f, 5.6789f)
        val r = texelsAlongRight(f, f.x, f.y, f.z)
        val u = texelsAlongUp(f, f.x, f.y, f.z)
        assertNear(0f, r - floor(r + 0.5f), 1e-2f)
        assertNear(0f, u - floor(u + 0.5f), 1e-2f)
    }

    @Test
    fun `snapping moves the focus by less than one texel`() {
        val f = focus()
        f.moveTo(1.2345f, 0f, 5.6789f)
        val d = sqrt((f.x - 1.2345f) * (f.x - 1.2345f) + f.y * f.y + (f.z - 5.6789f) * (f.z - 5.6789f))
        assertTrue(d <= f.texelSize * 1.5f, "moved by $d")
    }

    @Test
    fun `moving by less than a texel does not change the snapped focus`() {
        val f = focus()
        f.moveTo(10f, 0f, 10f)
        val x = f.x
        val z = f.z
        // a nudge of one fifth of a texel along the world x axis stays within the same cell most of the time
        f.moveTo(10f + f.texelSize * 0.05f, 0f, 10f)
        assertTrue(abs(f.x - x) <= f.texelSize * 1.5f)
        assertTrue(abs(f.z - z) <= f.texelSize * 1.5f)
    }

    @Test
    fun `snapping is idempotent`() {
        val f = focus()
        f.moveTo(3.3f, 0f, -7.7f)
        val x = f.x
        val y = f.y
        val z = f.z
        f.moveTo(x, y, z)
        assertNear(x, f.x, 1e-4f)
        assertNear(y, f.y, 1e-4f)
        assertNear(z, f.z, 1e-4f)
    }

    @Test
    fun `the sun sits at the web distance along the direction to the sun`() {
        val f = focus()
        f.moveTo(0f, 0f, 0f)
        assertNear(f.x + sun[0] * ShadowFocus.SUN_DISTANCE, f.sunX, 1e-3f)
        assertNear(f.y + sun[1] * ShadowFocus.SUN_DISTANCE, f.sunY, 1e-3f)
        assertNear(f.z + sun[2] * ShadowFocus.SUN_DISTANCE, f.sunZ, 1e-3f)
    }

    @Test
    fun `shadow far reaches the far side of the focus area from the camera`() {
        val f = focus()
        f.moveTo(0f, 0f, 0f)
        // camera 10 m away: the 24 m half extent is added to the distance
        assertNear(34f, f.shadowFar(10f, 0f, 0f, step = 1f), 1e-3f)
    }

    @Test
    fun `shadow far is rounded up to the step so the options change rarely`() {
        val f = focus()
        f.moveTo(0f, 0f, 0f)
        assertEquals(36f, f.shadowFar(10.4f, 0f, 0f, step = 4f))
        assertEquals(36f, f.shadowFar(11.9f, 0f, 0f, step = 4f))
    }

    @Test
    fun `shadow far never falls below the minimum`() {
        val f = focus(halfExtent = 1f)
        f.moveTo(0f, 0f, 0f)
        assertEquals(ShadowFocus.MIN_SHADOW_FAR, f.shadowFar(0f, 0f, 0f, step = 1f))
    }
}
