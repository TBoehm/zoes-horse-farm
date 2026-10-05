package app.zoeshorsefarm.render.filament.context

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PixelPlanTest {
    @Test
    fun `a pixel ratio cap above the device ratio changes nothing`() {
        val plan =
            PixelPlan.compute(
                surfaceWidth = 1080,
                surfaceHeight = 2400,
                devicePixelRatio = 2.5f,
                maxPixelRatio = 3f,
            )
        assertEquals(1.0f, plan.renderScale)
        assertEquals(1080, plan.renderWidth)
        assertEquals(2400, plan.renderHeight)
        assertEquals(2.5f, plan.pixelRatio)
    }

    @Test
    fun `a cap below the device ratio renders at a smaller size and scales up`() {
        // the medium level: pixelRatio 1.5 on a 3x phone
        val plan = PixelPlan.compute(1200, 2400, devicePixelRatio = 3f, maxPixelRatio = 1.5f)
        assertEquals(1.5f, plan.pixelRatio)
        assertEquals(0.5f, plan.renderScale)
        assertEquals(600, plan.renderWidth)
        assertEquals(1200, plan.renderHeight)
    }

    @Test
    fun `the low level at ratio one renders css pixels`() {
        val plan = PixelPlan.compute(1080, 1920, devicePixelRatio = 2.75f, maxPixelRatio = 1f)
        assertTrue(abs(plan.renderScale - 1f / 2.75f) < 1e-6f)
        assertEquals(393, plan.renderWidth)
        assertEquals(698, plan.renderHeight)
    }

    @Test
    fun `the surface size stays the physical size`() {
        val plan = PixelPlan.compute(1080, 1920, 3f, 1f)
        assertEquals(1080, plan.surfaceWidth)
        assertEquals(1920, plan.surfaceHeight)
    }

    @Test
    fun `sizes never drop below one pixel`() {
        val plan = PixelPlan.compute(0, 0, 3f, 1f)
        assertEquals(1, plan.surfaceWidth)
        assertEquals(1, plan.renderWidth)
        assertEquals(1, plan.renderHeight)
    }

    @Test
    fun `an invalid device ratio or cap falls back to one`() {
        val plan = PixelPlan.compute(100, 100, devicePixelRatio = 0f, maxPixelRatio = -1f)
        assertEquals(1f, plan.pixelRatio)
        assertEquals(1f, plan.renderScale)
    }

    @Test
    fun `the render scale never goes below the floor`() {
        val plan = PixelPlan.compute(1000, 1000, devicePixelRatio = 10f, maxPixelRatio = 1f)
        assertEquals(PixelPlan.MIN_RENDER_SCALE, plan.renderScale)
    }

    @Test
    fun `a plan equals another plan with the same numbers`() {
        assertEquals(PixelPlan.compute(100, 200, 2f, 1f), PixelPlan.compute(100, 200, 2f, 1f))
        assertNotEquals(PixelPlan.compute(100, 200, 2f, 1f), PixelPlan.compute(100, 200, 2f, 2f))
    }
}
