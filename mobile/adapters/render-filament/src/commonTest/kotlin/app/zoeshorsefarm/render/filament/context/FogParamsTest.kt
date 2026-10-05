package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FogParamsTest {
    private val horizon = LinearRgb(0.6f, 0.75f, 0.85f)

    /** Filament's linear fog with the height falloff off: opacity = density * (distance - start), clamped. */
    private fun opacityAt(
        fog: FogParams,
        distance: Float,
    ): Float = min(fog.maximumOpacity, max(0f, fog.density * (distance - fog.distance)))

    /** three.js `Fog`: `smoothstep(near, far, depth)` of the fog colour. */
    private fun threeOpacityAt(
        near: Float,
        far: Float,
        distance: Float,
    ): Float {
        val t = min(1f, max(0f, (distance - near) / (far - near)))
        return t * t * (3f - 2f * t)
    }

    @Test
    fun `fog starts at the near distance of the linear fog`() {
        val fog = FogParams.fromLinear(near = 120f, far = 520f, color = horizon)
        assertEquals(120f, fog.distance)
        assertEquals(0f, opacityAt(fog, 120f))
        assertEquals(0f, opacityAt(fog, 50f))
    }

    @Test
    fun `fog is fully opaque at the far distance of the linear fog`() {
        val fog = FogParams.fromLinear(near = 120f, far = 520f, color = horizon)
        assertTrue(abs(opacityAt(fog, 520f) - 1f) < 1e-5f)
    }

    @Test
    fun `the fog meets three js at near and in the middle and at far`() {
        // three.js eases in and out with smoothstep, Filament is linear: the ends and the middle agree
        for ((near, far) in listOf(120f to 520f, 90f to 480f)) {
            val fog = FogParams.fromLinear(near, far, horizon)
            for (d in listOf(near - 10f, near, (near + far) / 2f, far, far + 100f)) {
                assertTrue(abs(opacityAt(fog, d) - threeOpacityAt(near, far, d)) < 1e-4f, "near $near far $far at $d")
            }
        }
    }

    @Test
    fun `between the ends the linear fog is within a tenth of the three js curve`() {
        val fog = FogParams.fromLinear(120f, 520f, horizon)
        for (d in 120..520 step 10) {
            val difference = abs(opacityAt(fog, d.toFloat()) - threeOpacityAt(120f, 520f, d.toFloat()))
            assertTrue(difference < 0.1f, "at $d the fogs differ by $difference")
        }
    }

    @Test
    fun `height falloff is off so that the density is the slope of the fog`() {
        assertEquals(0f, FogParams.fromLinear(10f, 100f, horizon).heightFalloff)
    }

    @Test
    fun `the fog colour is passed through in linear space`() {
        assertEquals(horizon, FogParams.fromLinear(10f, 100f, horizon).color)
    }

    @Test
    fun `a far distance at or below the near distance gets a minimal range`() {
        val fog = FogParams.fromLinear(100f, 100f, horizon)
        assertTrue(fog.density.isFinite() && fog.density > 0f)
    }

    @Test
    fun `no fog is represented by null`() {
        assertNull(FogParams.fromLinearOrNull(near = null, far = null, color = horizon))
        assertEquals(
            FogParams.fromLinear(1f, 2f, horizon),
            FogParams.fromLinearOrNull(1f, 2f, horizon),
        )
    }
}
