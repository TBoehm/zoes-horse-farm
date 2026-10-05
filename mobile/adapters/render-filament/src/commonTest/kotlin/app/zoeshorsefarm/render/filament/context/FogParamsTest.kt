package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FogParamsTest {
    private val horizon = LinearRgb(0.6f, 0.75f, 0.85f)

    private fun opacityAt(
        fog: FogParams,
        distance: Float,
    ): Float = 1f - exp(-fog.density * maxOf(0f, distance - fog.distance))

    @Test
    fun `fog starts at the near distance of the linear fog`() {
        val fog = FogParams.fromLinear(near = 120f, far = 520f, color = horizon)
        assertEquals(120f, fog.distance)
        assertEquals(0f, opacityAt(fog, 120f))
        assertEquals(0f, opacityAt(fog, 50f))
    }

    @Test
    fun `fog is nearly opaque at the far distance of the linear fog`() {
        val fog = FogParams.fromLinear(near = 120f, far = 520f, color = horizon)
        val opacity = opacityAt(fog, 520f)
        assertTrue(abs(opacity - FogParams.OPACITY_AT_FAR) < 1e-3f, "opacity $opacity")
    }

    @Test
    fun `the high level fog is denser than the medium level fog`() {
        val medium = FogParams.fromLinear(120f, 520f, horizon)
        val high = FogParams.fromLinear(90f, 480f, horizon)
        assertTrue(opacityAt(high, 300f) > opacityAt(medium, 300f))
    }

    @Test
    fun `height falloff is off so that the fog depends on distance only`() {
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
