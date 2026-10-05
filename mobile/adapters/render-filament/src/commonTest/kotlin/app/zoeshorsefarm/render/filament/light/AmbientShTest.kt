package app.zoeshorsefarm.render.filament.light

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientShTest {
    private val sky = LinearRgb(0.6f, 0.8f, 1.0f)
    private val ground = LinearRgb(0.2f, 0.1f, 0.05f)

    private fun assertNear(
        expected: Float,
        actual: Float,
        eps: Float = 1e-5f,
    ) {
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")
    }

    @Test
    fun `a hemisphere light uses two bands and four coefficients of three channels`() {
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        assertEquals(2, AmbientSh.BANDS)
        assertEquals(12, sh.size)
        assertEquals(12, AmbientSh.FLOAT_COUNT)
    }

    @Test
    fun `facing up the diffuse term equals the sky colour over pi like three js`() {
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        val up = AmbientSh.diffuseAt(sh, 0f, 1f, 0f)
        assertNear(sky.r / PI.toFloat(), up.r)
        assertNear(sky.g / PI.toFloat(), up.g)
        assertNear(sky.b / PI.toFloat(), up.b)
    }

    @Test
    fun `facing down the diffuse term equals the ground colour over pi`() {
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        val down = AmbientSh.diffuseAt(sh, 0f, -1f, 0f)
        assertNear(ground.r / PI.toFloat(), down.r)
        assertNear(ground.b / PI.toFloat(), down.b)
    }

    @Test
    fun `facing sideways the term is the mean of sky and ground`() {
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        val side = AmbientSh.diffuseAt(sh, 1f, 0f, 0f)
        assertNear((sky.g + ground.g) / 2f / PI.toFloat(), side.g)
    }

    @Test
    fun `the term blends linearly with the up component of the normal`() {
        // three.js: mix(ground, sky, 0.5 + 0.5 * dot(n, up))
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        val ny = 0.3f
        val nx = kotlin.math.sqrt(1f - ny * ny)
        val v = AmbientSh.diffuseAt(sh, nx, ny, 0f)
        val w = 0.5f + 0.5f * ny
        assertNear((ground.r + (sky.r - ground.r) * w) / PI.toFloat(), v.r)
    }

    @Test
    fun `intensity scales every coefficient`() {
        val one = AmbientSh.hemisphere(sky, ground, 1f)
        val half = AmbientSh.hemisphere(sky, ground, 0.5f)
        for (i in one.indices) assertNear(one[i] * 0.5f, half[i])
    }

    @Test
    fun `the horizontal basis terms are zero for a hemisphere`() {
        val sh = AmbientSh.hemisphere(sky, ground, 1f)
        // coefficient order of Filament: constant, y, z, x; three channels each
        for (i in 6 until 12) assertEquals(0f, sh[i])
    }

    @Test
    fun `an environment term is a cosine convolved radiance gradient without the 1 over pi`() {
        // a constant radiance L gives a diffuse term of exactly L (Lambert albedo times radiance)
        val flat = AmbientSh.environment(sky, sky, 1f)
        val any = AmbientSh.diffuseAt(flat, 0.3f, 0.5f, 0.8f)
        assertNear(sky.r, any.r)
        // the gradient is attenuated by the cosine lobe: 2/3 of the radiance difference
        val gradient = AmbientSh.environment(sky, ground, 1f)
        val up = AmbientSh.diffuseAt(gradient, 0f, 1f, 0f)
        val mean = (sky.r + ground.r) / 2f
        assertNear(mean + (sky.r - ground.r) / 2f * (2f / 3f), up.r)
    }

    @Test
    fun `terms add up`() {
        val a = AmbientSh.hemisphere(sky, ground, 0.6f)
        val b = AmbientSh.environment(sky, ground, 0.8f)
        val sum = AmbientSh.sum(a, b)
        for (i in sum.indices) assertNear(a[i] + b[i], sum[i])
    }

    @Test
    fun `sum of nothing is zero`() {
        val sum = AmbientSh.sum()
        assertEquals(12, sum.size)
        assertTrue(sum.all { it == 0f })
    }
}
