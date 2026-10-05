package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QualityPresetsTest {
    @Test
    fun lowerLevelGoesDownOneLevelNeverBelowLow() {
        assertEquals(GraphicsLevel.MEDIUM, lowerLevel(GraphicsLevel.HIGH))
        assertEquals(GraphicsLevel.LOW, lowerLevel(GraphicsLevel.MEDIUM))
        assertEquals(GraphicsLevel.LOW, lowerLevel(GraphicsLevel.LOW))
    }

    @Test
    fun presetsExistForAllLevelsWithPixelRatioAndShadowValues() {
        assertEquals(GRAPHICS_LEVELS, QUALITY_PRESETS.keys.toList())
        assertEquals(1.0, LOW_PRESET.pixelRatio)
        assertEquals(1.5, MEDIUM_PRESET.pixelRatio)
        assertEquals(2.0, HIGH_PRESET.pixelRatio)
        assertFalse(LOW_PRESET.shadows)
        assertEquals(1024, MEDIUM_PRESET.shadowMapSize)
        assertEquals(2048, HIGH_PRESET.shadowMapSize)
        assertEquals(MaterialKind.LAMBERT, LOW_PRESET.material)
    }

    @Test
    fun addsTheDetailsLevelByLevelNoneOnLowTheCheapOnesOnMediumAllOnHigh() {
        val shares =
            listOf<(QualityPreset) -> Double>(
                { it.grassTufts },
                { it.flowers },
                { it.decor },
                { it.birds },
                { it.butterflies },
                { it.grazingHorses.toDouble() },
            )
        for (share in shares) {
            assertEquals(0.0, share(LOW_PRESET))
            assertTrue(share(MEDIUM_PRESET) <= share(HIGH_PRESET))
            assertTrue(share(HIGH_PRESET) > 0.0)
        }
        for (flag in listOf<(QualityPreset) -> Boolean>({ it.planters }, { it.hoofDust }, { it.wind })) {
            assertFalse(flag(LOW_PRESET))
            assertTrue(flag(HIGH_PRESET))
        }
    }

    @Test
    fun keepsTheExpensiveDetailsOnHighOnly() {
        val medium = MEDIUM_PRESET
        assertFalse(medium.wind)
        assertEquals(0.0, medium.grassTufts)
        assertEquals(0.0, medium.flowers)
        assertEquals(0.0, medium.birds)
        assertEquals(0.0, medium.butterflies)
        assertEquals(0, medium.grazingHorses)
        assertFalse(medium.hoofDust)
        assertFalse(medium.planters)
        // what medium keeps is the cheap static decoration: the paddock and every second pennant
        assertTrue(medium.decor > 0.0)
        assertTrue(medium.decor < 1.0)
    }

    @Test
    fun antialiasingIsOffOnLowAndOnAbove() {
        assertFalse(LOW_PRESET.antialias)
        assertTrue(MEDIUM_PRESET.antialias)
        assertTrue(HIGH_PRESET.antialias)
    }

    @Test
    fun presetForMapsLevelsToPresets() {
        assertSame(MEDIUM_PRESET, presetFor(GraphicsLevel.MEDIUM))
        assertNull(GraphicsLevel.fromId("nope")?.let { presetFor(it) })
    }

    @Test
    fun everyPresetKnowsItsLevel() {
        for (level in GRAPHICS_LEVELS) assertEquals(level, presetFor(level).level)
    }
}
