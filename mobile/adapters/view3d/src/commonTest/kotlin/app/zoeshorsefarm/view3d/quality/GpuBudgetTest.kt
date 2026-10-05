package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val MIB = 1024.0 * 1024.0

/** A 4 GiB tablet: 1280 x 800 CSS pixels at a device pixel ratio of 2. */
private val TABLET = GpuMemoryContext(cssWidth = 1280.0, cssHeight = 800.0, devicePixelRatio = 2.0, antialias = true)

private fun assertClose(
    expected: Double,
    actual: Double,
    eps: Double,
) = assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual (eps $eps)")

class EstimateGpuMemoryTest {
    private fun estimate(
        level: QualityPreset,
        ctx: GpuMemoryContext = TABLET,
    ) = estimateGpuMemoryMB(level, ctx)

    @Test
    fun addsDrawingBufferShadowMapEnvironmentMapTexturesAndSceneryHighOnATablet() {
        // 2560 x 1600 px x 40 B (MSAA x4 colour+depth, two colour buffers) = 156.25 MiB; shadow map
        // 2048^2 x 8 B = 32 MiB; environment map 6 MiB; 3 textures with mipmaps 4 MiB; baseline 16 MiB;
        // scenery 2 MiB, grass tufts 2, flowers 0.5, decoration 0.5, animals 0.1, two grazing horses
        // 0.86, hoof dust 0.01, extra vertices of horse and rider 0.22
        assertClose(220.44, estimate(HIGH_PRESET), 0.05)
    }

    @Test
    fun countsTheDetailsOfTheSceneryAndScalesThemWithThePreset() {
        val base = HIGH_PRESET
        val full = estimateGpuMemoryMB(base, TABLET)

        fun cost(other: QualityPreset) = full - estimateGpuMemoryMB(other, TABLET)
        assertClose(2.0, cost(base.copy(grassTufts = 0.0)), 1e-5)
        assertClose(0.5, cost(base.copy(flowers = 0.0)), 1e-5)
        assertClose(0.5, cost(base.copy(decor = 0.0)), 1e-5)
        // birds and butterflies share one small cost: only both off saves it
        assertEquals(0.0, cost(base.copy(birds = 0.0)))
        assertClose(0.1, cost(base.copy(birds = 0.0, butterflies = 0.0)), 1e-5)
        // half the flowers cost half
        assertClose(0.25, cost(base.copy(flowers = 0.5)), 1e-5)
    }

    @Test
    fun countsTheGrazingHorsesByTheirModelTheDustAndTheVerticesOfHorseAndRider() {
        fun cost(
            preset: QualityPreset,
            off: QualityPreset,
        ) = estimateGpuMemoryMB(preset, TABLET) - estimateGpuMemoryMB(off, TABLET)

        // two horses of the low model (167 KB) on a medium with horses, of the medium model (430 KB) on high
        val withHorses = MEDIUM_PRESET.copy(grazingHorses = 2)
        assertClose(2 * 167 / 1024.0, cost(withHorses, withHorses.copy(grazingHorses = 0)), 1e-5)
        assertClose(2 * 430 / 1024.0, cost(HIGH_PRESET, HIGH_PRESET.copy(grazingHorses = 0)), 1e-5)
        assertClose(430 / 1024.0, cost(HIGH_PRESET, HIGH_PRESET.copy(grazingHorses = 1)), 1e-5)
        assertClose(0.01, cost(HIGH_PRESET, HIGH_PRESET.copy(hoofDust = false)), 1e-5)

        // rider +16 / +80 / +150 KB, horse +0 / +36 / +80 KB per character detail
        fun withDetail(detail: Detail) = estimateGpuMemoryMB(LOW_PRESET.copy(characterDetail = detail), TABLET) * 1024
        assertClose(16.0, GpuMemoryModel.characterMB.getValue(Detail.LOW) * 1024, 1e-3)
        assertClose(116.0, GpuMemoryModel.characterMB.getValue(Detail.MEDIUM) * 1024, 1e-3)
        assertClose(230.0, GpuMemoryModel.characterMB.getValue(Detail.HIGH) * 1024, 1e-3)
        assertClose(100.0, withDetail(Detail.MEDIUM) - withDetail(Detail.LOW), 1e-3)
        assertClose(214.0, withDetail(Detail.HIGH) - withDetail(Detail.LOW), 1e-3)
    }

    @Test
    fun everyDetailThatALevelShowsIsPartOfItsEstimateLowCarriesNone() {
        val low = LOW_PRESET
        val stripped =
            low.copy(
                grassTufts = 0.0,
                flowers = 0.0,
                decor = 0.0,
                birds = 0.0,
                butterflies = 0.0,
                grazingHorses = 0,
                hoofDust = false,
            )
        assertClose(estimateGpuMemoryMB(low, TABLET), estimateGpuMemoryMB(stripped, TABLET), 1e-9)
        for (preset in listOf(MEDIUM_PRESET, HIGH_PRESET)) {
            val without =
                preset.copy(
                    flowers = 0.0,
                    decor = 0.0,
                    birds = 0.0,
                    butterflies = 0.0,
                    grassTufts = 0.0,
                    grazingHorses = 0,
                    hoofDust = false,
                )
            assertTrue(estimateGpuMemoryMB(preset, TABLET) > estimateGpuMemoryMB(without, TABLET))
        }
    }

    @Test
    fun mediumCostsOnlyALittleMoreThanBeforeTheDetails() {
        val beforeMediumMB = 122.99 // the same estimate computed by the web code at commit 5e240fc
        val now = estimateGpuMemoryMB(MEDIUM_PRESET, TABLET)
        assertTrue(now >= beforeMediumMB - 0.01)
        assertTrue(now - beforeMediumMB < 1)
    }

    @Test
    fun growsWithTheSquareOfThePixelRatio() {
        fun at(ratio: Double) = estimate(HIGH_PRESET, TABLET.copy(pixelRatio = ratio))
        // 4x the pixels at ratio 2 vs 1: the difference is 3 x the buffer at ratio 1
        val buffer1 = 1280 * 800 * 40 / MIB
        assertClose(3 * buffer1, at(2.0) - at(1.0), 0.05)
    }

    @Test
    fun isCappedByTheDevicePixelRatio() {
        assertClose(
            estimate(HIGH_PRESET, TABLET.copy(pixelRatio = 1.0)),
            estimate(HIGH_PRESET, TABLET.copy(devicePixelRatio = 1.0)),
            1e-5,
        )
    }

    @Test
    fun antialiasingCostsALotMultisampledColourAndDepthOnTop() {
        val aa = estimate(HIGH_PRESET, TABLET.copy(antialias = true))
        val plain = estimate(HIGH_PRESET, TABLET.copy(antialias = false))
        assertClose(2560 * 1600 * (4 * 8 + 8 - 12) / MIB, aa - plain, 0.05)
    }

    @Test
    fun takesTheContextAttributeNotThePresetForAntialiasing() {
        val preset = LOW_PRESET // wants no antialiasing
        assertTrue(
            estimateGpuMemoryMB(preset, TABLET.copy(antialias = true)) >
                estimateGpuMemoryMB(preset, TABLET.copy(antialias = false)),
        )
    }

    @Test
    fun usesThePresetsAntialiasingWhenTheContextDoesNotSayAnything() {
        val noContextChoice = TABLET.copy(antialias = null)
        assertEquals(
            estimateGpuMemoryMB(LOW_PRESET, TABLET.copy(antialias = false)),
            estimateGpuMemoryMB(LOW_PRESET, noContextChoice),
        )
        assertEquals(
            estimateGpuMemoryMB(HIGH_PRESET, TABLET.copy(antialias = true)),
            estimateGpuMemoryMB(HIGH_PRESET, noContextChoice),
        )
    }

    @Test
    fun aShadowMapCostsSizeSquaredTimesEightBytesNoneWithoutShadows() {
        val noShadow = estimateGpuMemoryMB(MEDIUM_PRESET.copy(shadows = false), TABLET)
        assertClose(8.0, estimate(MEDIUM_PRESET) - noShadow, 1e-5) // 1024^2 x 8 B
        val big = estimateGpuMemoryMB(MEDIUM_PRESET.copy(shadowMapSize = 2048), TABLET)
        assertClose(24.0, big - estimate(MEDIUM_PRESET), 1e-5)
    }

    @Test
    fun theEnvironmentMapAndTheNormalMapsOnlyCountWhenTheLevelUsesThem() {
        val noEnv = estimateGpuMemoryMB(MEDIUM_PRESET.copy(envMap = false), TABLET)
        assertClose(6.0, estimate(MEDIUM_PRESET) - noEnv, 1e-5)
        val noNormal = estimateGpuMemoryMB(MEDIUM_PRESET.copy(normalMaps = false), TABLET)
        assertClose(512 * 512 * 4 * (4.0 / 3) / MIB, estimate(MEDIUM_PRESET) - noNormal, 1e-5)
    }

    @Test
    fun countsTheTexturesItIsGiven() {
        val none = estimate(MEDIUM_PRESET, TABLET.copy(textures = emptyList()))
        val one =
            estimate(MEDIUM_PRESET, TABLET.copy(textures = listOf(TextureInfo(1024, 1024, normal = false))))
        assertClose(1024 * 1024 * 4 * (4.0 / 3) / MIB, one - none, 1e-5)
    }

    @Test
    fun ordersTheLevelsLowMediumHigh() {
        val ctx = TABLET.copy(antialias = true)
        val (low, medium, high) = GRAPHICS_LEVELS.map { estimate(presetFor(it), ctx) }
        assertTrue(low < medium)
        assertTrue(medium < high)
    }
}

class GpuBudgetTest {
    @Test
    fun isSmallerOnTouchDevicesThanOnDesktops() {
        assertTrue(
            gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0)) <
                gpuBudgetMB(DeviceInfo(isTouch = false, totalMemoryGiB = 4.0)),
        )
        assertTrue(gpuBudgetMB(DeviceInfo(isTouch = true)) < gpuBudgetMB(DeviceInfo(isTouch = false)))
    }

    @Test
    fun followsTheDeviceMemoryWithinTheClassLimits() {
        assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0)))
        assertEquals(320, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 8.0)))
        assertEquals(96, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 2.0))) // floor
        assertEquals(96, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 0.5)))
        assertEquals(512, gpuBudgetMB(DeviceInfo(isTouch = false, totalMemoryGiB = 8.0)))
        assertEquals(1024, gpuBudgetMB(DeviceInfo(isTouch = false, totalMemoryGiB = 16.0))) // ceiling
    }

    @Test
    fun usesAConservativeValueWhenTheMemoryIsUnknown() {
        assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true)))
        assertEquals(512, gpuBudgetMB(DeviceInfo(isTouch = false)))
        assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = null)))
        assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 0.0)))
        assertEquals(512, gpuBudgetMB(DeviceInfo()))
    }

    @Test
    fun givesAWeakGpuAQuarterLess() {
        assertEquals(120, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0, rendererName = "Mali-G52")))
        assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0, rendererName = "Apple GPU")))
    }

    @Test
    fun recognisesTheWeakGpuFamilies() {
        for (name in listOf(
            "Intel(R) UHD Graphics 620",
            "Adreno (TM) 305",
            "PowerVR Rogue",
            "VideoCore IV",
            "mali-t76",
        )) {
            assertEquals(120, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0, rendererName = name)), name)
        }
        for (name in listOf("Adreno (TM) 650", "Mali-G710", "NVIDIA GeForce RTX 3060")) {
            assertEquals(160, gpuBudgetMB(DeviceInfo(isTouch = true, totalMemoryGiB = 4.0, rendererName = name)), name)
        }
    }
}

class FitPresetToBudgetTest {
    private fun fit(
        preset: QualityPreset,
        budget: Double,
        ctx: GpuMemoryContext = TABLET,
    ) = fitPresetToBudget(preset, ctx, budget)

    private fun fitHigh(
        budget: Double,
        ctx: GpuMemoryContext = TABLET,
    ) = fit(HIGH_PRESET, budget, ctx)

    @Test
    fun changesNothingWhenTheLevelFitsTheVerySamePresetObject() {
        val result = fit(MEDIUM_PRESET, 160.0)
        assertSame(MEDIUM_PRESET, result.preset)
        assertTrue(result.fits)
        assertEquals(CappedBy(null, null, scenery = false), result.capped)
        assertClose(result.requestedMB, result.estimateMB, 1e-9)
    }

    @Test
    fun lowersOnlyThePixelRatioFirstInStepsOfPointZeroFiveAndKeepsEveryEffect() {
        val result = fitHigh(160.0)
        assertTrue(result.fits)
        assertTrue(result.estimateMB <= 160.0)
        assertEquals(1.55, result.preset.pixelRatio)
        assertEquals(PixelRatioCap(from = 2.0, to = 1.55), result.capped.pixelRatio)
        assertNull(result.capped.shadowMapSize)
        assertFalse(result.capped.scenery)
        assertEquals(HIGH_PRESET, result.preset.copy(pixelRatio = 2.0))
    }

    @Test
    fun takesTheBiggestRatioThatFitsABitMoreWouldNot() {
        val result = fitHigh(160.0)
        val next = estimateGpuMemoryMB(result.preset, TABLET.copy(pixelRatio = result.preset.pixelRatio + 0.05))
        assertTrue(next > 160.0)
    }

    @Test
    fun goesDownToTheRatio1ThenHalvesTheShadowMap() {
        val atRatio1 = estimateGpuMemoryMB(HIGH_PRESET, TABLET.copy(pixelRatio = 1.0))
        val result = fitHigh(atRatio1 - 5)
        assertEquals(1.0, result.preset.pixelRatio)
        assertEquals(1024, result.preset.shadowMapSize)
        assertEquals(ShadowMapCap(from = 2048, to = 1024), result.capped.shadowMapSize)
        assertFalse(result.capped.scenery)
        assertTrue(result.fits)
    }

    @Test
    fun takesTheTuftsAndThenTheDensityOfTheSceneryAsTheLastLever() {
        val base = fitHigh(1000.0)
        assertSame(HIGH_PRESET, base.preset)
        val noShadowGain = estimateGpuMemoryMB(HIGH_PRESET.copy(pixelRatio = 1.0, shadowMapSize = 1024), TABLET)
        val result = fitHigh(noShadowGain - 0.5)
        assertEquals(0.0, result.preset.grassTufts)
        assertTrue(result.capped.scenery)
        assertEquals(1.0, result.preset.envDensity) // the tufts were enough
        val tiny = fitHigh(20.0)
        assertEquals(0.0, tiny.preset.grassTufts)
        assertEquals(0.0, tiny.preset.flowers)
        assertEquals(0.0, tiny.preset.butterflies)
        assertEquals(0.0, tiny.preset.birds)
        assertEquals(0.0, tiny.preset.decor)
        assertFalse(tiny.preset.planters)
        assertEquals(0.55, tiny.preset.envDensity)
        assertEquals(1.0, tiny.preset.pixelRatio)
        assertEquals(1024, tiny.preset.shadowMapSize)
        assertFalse(tiny.fits) // best effort: the level still plays
    }

    private val atRatio1 = TABLET.copy(pixelRatio = 1.0)

    private fun cheapest(p: QualityPreset) = estimateGpuMemoryMB(p.copy(shadowMapSize = 1024), atRatio1)

    @Test
    fun dropsTheFlowersAfterTheTuftsAndBeforeTheTreesAndBushes() {
        val justWithout = cheapest(HIGH_PRESET.copy(grassTufts = 0.0))
        val result = fitHigh(justWithout - 0.1)
        assertEquals(0.0, result.preset.grassTufts)
        assertEquals(0.0, result.preset.flowers)
        assertEquals(1.0, result.preset.envDensity) // the flowers were enough
        assertEquals(1.0, result.preset.decor) // the decoration and the animals stay
        assertEquals(1.0, result.preset.birds)
        assertTrue(result.capped.scenery)
    }

    @Test
    fun dropsTheGrazingHorsesAfterTheFlowersAndBeforeTheTreesAndBushes() {
        val withoutFlowers = HIGH_PRESET.copy(grassTufts = 0.0, flowers = 0.0)
        val result = fitHigh(cheapest(withoutFlowers) - 0.1)
        assertEquals(0.0, result.preset.flowers)
        assertEquals(0, result.preset.grazingHorses)
        assertEquals(1.0, result.preset.envDensity) // the horses were enough
        assertTrue(result.preset.hoofDust)
        assertTrue(result.capped.scenery)
        // the horses are the last of the "grass and surroundings" group, after the resolution
        assertEquals(1.0, result.preset.pixelRatio)
        assertEquals(2, fitHigh(160.0).preset.grazingHorses)
    }

    @Test
    fun takesTheButterfliesWithTheFlowersTheyHoverOver() {
        val result = fitHigh(cheapest(HIGH_PRESET.copy(grassTufts = 0.0)) - 0.1)
        assertEquals(0.0, result.preset.flowers)
        assertEquals(0.0, result.preset.butterflies)
        assertEquals(1.0, result.preset.birds)
    }

    @Test
    fun thenTakesTheBirdsAndTheDecorationBeforeTheTreesAndBushes() {
        val bare = HIGH_PRESET.copy(grassTufts = 0.0, flowers = 0.0, butterflies = 0.0, grazingHorses = 0)
        val birdsGone = fitHigh(cheapest(bare) - 0.01)
        assertEquals(0.0, birdsGone.preset.birds)
        assertEquals(1.0, birdsGone.preset.decor)
        assertEquals(1.0, birdsGone.preset.envDensity)
        val decorGone = fitHigh(cheapest(bare.copy(birds = 0.0)) - 0.01)
        assertEquals(0.0, decorGone.preset.decor)
        assertEquals(1.0, decorGone.preset.envDensity)
        assertTrue(decorGone.capped.scenery)
        // only now do the trees and bushes get thinner
        val treesThinner = fitHigh(cheapest(bare.copy(birds = 0.0, decor = 0.0)) - 0.01)
        assertEquals(0.55, treesThinner.preset.envDensity)
    }

    @Test
    fun isMonotonicABiggerBudgetNeverGivesALowerRatioOrFewerFeatures() {
        val contexts = listOf(TABLET, TABLET.copy(devicePixelRatio = 3.0), TABLET.copy(antialias = false))
        for (level in GRAPHICS_LEVELS) {
            for (ctx in contexts) {
                var previous: QualityPreset? = null
                var budget = 5.0
                while (budget <= 600.0) {
                    val preset = fit(presetFor(level), budget, ctx).preset
                    previous?.let { assertNotWorse(preset, it) }
                    previous = preset
                    budget += 5.0
                }
            }
        }
    }

    private fun assertNotWorse(
        preset: QualityPreset,
        previous: QualityPreset,
    ) {
        assertTrue(preset.pixelRatio >= previous.pixelRatio)
        assertTrue(preset.shadowMapSize >= previous.shadowMapSize)
        assertTrue(preset.grassTufts >= previous.grassTufts)
        assertTrue(preset.flowers >= previous.flowers)
        assertTrue(preset.grazingHorses >= previous.grazingHorses)
        assertTrue(preset.butterflies >= previous.butterflies)
        assertTrue(preset.birds >= previous.birds)
        assertTrue(preset.decor >= previous.decor)
        assertTrue(preset.envDensity >= previous.envDensity)
    }

    @Test
    fun neverGoesBelowRatio1ALowerDeviceRatioIsSimplyUsedAsItIs() {
        assertEquals(1.0, fitHigh(20.0).preset.pixelRatio)
        val slow = fitHigh(20.0, TABLET.copy(devicePixelRatio = 0.75))
        assertEquals(0.75, minOf(0.75, slow.preset.pixelRatio))
        assertNull(slow.capped.pixelRatio)
    }

    @Test
    fun doesNotTouchWhatIsAlreadySmallMediumKeepsItsShadowMap() {
        val result = fit(MEDIUM_PRESET, 20.0)
        assertEquals(1024, result.preset.shadowMapSize)
        assertNull(result.capped.shadowMapSize)
        assertEquals(1.0, result.preset.pixelRatio)
    }

    @Test
    fun doesNotCapTheRatioWhenTheDeviceRatioIsBelowTheLevelCapAlready() {
        val result = fitHigh(1000.0, TABLET.copy(devicePixelRatio = 1.25))
        assertSame(HIGH_PRESET, result.preset)
    }

    @Test
    fun doesNotChangeThePresetsItWorksOnCopies() {
        val before = QUALITY_PRESETS.toMap()
        val result = fitHigh(100.0)
        assertEquals(before, QUALITY_PRESETS)
        assertNotEquals(HIGH_PRESET, result.preset)
        assertEquals(GraphicsLevel.HIGH, result.preset.level)
    }

    @Test
    fun reportsTheEstimateOfTheFittedPresetAndTheRequestedOne() {
        val result = fitHigh(160.0)
        assertClose(220.44, result.requestedMB, 0.05)
        assertClose(estimateGpuMemoryMB(result.preset, TABLET), result.estimateMB, 1e-9)
        assertEquals(160.0, result.budgetMB)
    }
}

class ChooseAntialiasTest {
    private val ctx = GpuMemoryContext(cssWidth = 1280.0, cssHeight = 800.0, devicePixelRatio = 2.0)

    @Test
    fun keepsAntialiasingWhenTheBudgetCarriesItWithTheOtherLeversUsedUp() {
        assertTrue(chooseAntialias(HIGH_PRESET, ctx, 160.0))
        assertTrue(chooseAntialias(MEDIUM_PRESET, ctx, 160.0))
    }

    @Test
    fun dropsItWhenTheMultisampledBuffersAloneWouldNotFit() {
        assertFalse(chooseAntialias(HIGH_PRESET, ctx, 40.0))
    }

    @Test
    fun isOffForALevelThatDoesNotWantIt() {
        assertFalse(chooseAntialias(LOW_PRESET, ctx, 1000.0))
    }
}
