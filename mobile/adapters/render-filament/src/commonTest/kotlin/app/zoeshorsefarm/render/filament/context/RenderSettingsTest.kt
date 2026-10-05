package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderSettingsTest {
    @Test
    fun `the defaults match the low level of the web app`() {
        val settings = RenderSettings()
        assertEquals(1f, settings.maxPixelRatio)
        assertEquals(0, settings.msaaSamples)
        assertNull(settings.shadows)
        assertNull(settings.fog)
        assertEquals(ToneMapping.ACES_LEGACY, settings.toneMapping)
        assertEquals(1f, settings.exposure)
    }

    @Test
    fun `the shadow map size must be a power of two of at least 8`() {
        ShadowSettings(mapSize = 1024)
        ShadowSettings(mapSize = 8)
        assertFailsWith<IllegalArgumentException> { ShadowSettings(mapSize = 1000) }
        assertFailsWith<IllegalArgumentException> { ShadowSettings(mapSize = 4) }
        assertFailsWith<IllegalArgumentException> { ShadowSettings(mapSize = 0) }
    }

    @Test
    fun `msaa takes none, two or four samples`() {
        RenderSettings(msaaSamples = 0)
        RenderSettings(msaaSamples = 2)
        RenderSettings(msaaSamples = 4)
        assertFailsWith<IllegalArgumentException> { RenderSettings(msaaSamples = 3) }
        assertFailsWith<IllegalArgumentException> { RenderSettings(msaaSamples = 16) }
    }

    @Test
    fun `the pixel ratio cap and the exposure must be positive`() {
        assertFailsWith<IllegalArgumentException> { RenderSettings(maxPixelRatio = 0f) }
        assertFailsWith<IllegalArgumentException> { RenderSettings(exposure = -1f) }
    }

    @Test
    fun `equal settings have no changes`() {
        assertEquals(emptySet(), RenderSettings().changesTo(RenderSettings()))
    }

    @Test
    fun `each setting reports its own change`() {
        val base = RenderSettings()
        assertEquals(setOf(RenderChange.PIXEL_RATIO), base.changesTo(base.copy(maxPixelRatio = 2f)))
        assertEquals(setOf(RenderChange.ANTI_ALIASING), base.changesTo(base.copy(msaaSamples = 4)))
        assertEquals(setOf(RenderChange.SHADOWS), base.changesTo(base.copy(shadows = ShadowSettings(1024))))
        assertEquals(
            setOf(RenderChange.FOG),
            base.changesTo(base.copy(fog = FogParams.fromLinear(1f, 2f, LinearRgb(1f, 1f, 1f)))),
        )
        assertEquals(setOf(RenderChange.TONE_MAPPING), base.changesTo(base.copy(toneMapping = ToneMapping.LINEAR)))
        assertEquals(setOf(RenderChange.TONE_MAPPING), base.changesTo(base.copy(exposure = 2f)))
        assertEquals(setOf(RenderChange.CLEAR_COLOR), base.changesTo(base.copy(clearColor = LinearRgb(1f, 0f, 0f))))
    }

    @Test
    fun `a shadow map size change is a shadow change`() {
        val a = RenderSettings(shadows = ShadowSettings(1024))
        val b = RenderSettings(shadows = ShadowSettings(2048))
        assertEquals(setOf(RenderChange.SHADOWS), a.changesTo(b))
    }

    @Test
    fun `several changes are all reported`() {
        val a = RenderSettings()
        val b = RenderSettings(msaaSamples = 4, maxPixelRatio = 2f)
        assertTrue(a.changesTo(b).containsAll(listOf(RenderChange.ANTI_ALIASING, RenderChange.PIXEL_RATIO)))
    }
}
