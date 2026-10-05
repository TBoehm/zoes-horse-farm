package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ids(plan: List<QualityStage>): List<String> = plan.map { it.id }

/** The state of an engine whose stages all are at `preset`. */
private fun applied(preset: QualityPreset): Map<QualityStageId, QualityPreset?> =
    QUALITY_STAGE_IDS.associateWith { preset }

/** Stages for a change from one preset to another, as the engine plans it. */
private fun planChange(
    from: QualityPreset,
    to: QualityPreset?,
): List<QualityStage> = planQualityStagesFromState(applied(from), to)

class PlanQualityStagesTest {
    @Test
    fun plansNothingWhenTheLevelStaysTheSame() {
        for (level in GRAPHICS_LEVELS) assertEquals(emptyList(), planChange(presetFor(level), presetFor(level)))
    }

    @Test
    fun stepsDownResolutionFirstThenShadowsAndMaterialsInOneStageCharactersAndScenery() {
        assertEquals(
            listOf("pixelRatio", MERGED_STAGE_ID, "characters", "density"),
            ids(planChange(MEDIUM_PRESET, LOW_PRESET)),
        )
        assertEquals(ids(planChange(MEDIUM_PRESET, LOW_PRESET)), ids(planChange(HIGH_PRESET, LOW_PRESET)))
    }

    @Test
    fun stepsUpInTheOppositeOrderResolutionComesLast() {
        assertEquals(
            listOf("density", "characters", MERGED_STAGE_ID, "pixelRatio"),
            ids(planChange(LOW_PRESET, MEDIUM_PRESET)),
        )
    }

    @Test
    fun leavesOutStagesWhoseValuesDoNotDiffer() {
        // the wind code of high is part of the materials stage, the shadow map size of the shadows stage
        assertEquals(
            listOf("pixelRatio", MERGED_STAGE_ID, "characters", "density"),
            ids(planChange(HIGH_PRESET, MEDIUM_PRESET)),
        )
        val calm = HIGH_PRESET.copy(wind = false)
        assertEquals(listOf("materials"), ids(planChange(HIGH_PRESET, calm.copy(shadowMapSize = 2048))))
        assertEquals(listOf(MERGED_STAGE_ID), ids(planChange(HIGH_PRESET, calm.copy(shadowMapSize = 1024))))
    }

    @Test
    fun doesShadowsAndMaterialsInOneStageOnlyWhenBothAreNeeded() {
        val noShadows = MEDIUM_PRESET.copy(shadows = false)
        assertEquals(listOf("shadows"), ids(planChange(MEDIUM_PRESET, noShadows)))
        val lambert = MEDIUM_PRESET.copy(material = MaterialKind.LAMBERT)
        assertEquals(listOf("materials"), ids(planChange(MEDIUM_PRESET, lambert)))
        val both = noShadows.copy(material = MaterialKind.LAMBERT)
        val plan = planChange(MEDIUM_PRESET, both)
        assertEquals(listOf(MERGED_STAGE_ID), ids(plan))
        // the merged stage records the level for both stages it covers
        assertEquals(listOf(QualityStageId.SHADOWS, QualityStageId.MATERIALS), plan[0].covers)
        assertTrue(plan[0].compile)
    }

    @Test
    fun plansAPresetFittedToTheBudgetLikeItsLevelTheCopyKeepsTheDirection() {
        val capped = HIGH_PRESET.copy(pixelRatio = 1.25, shadowMapSize = 1024)
        assertEquals(ids(planChange(LOW_PRESET, HIGH_PRESET)), ids(planChange(LOW_PRESET, capped)))
        assertEquals(ids(planChange(HIGH_PRESET, LOW_PRESET)), ids(planChange(capped, LOW_PRESET)))
    }

    @Test
    fun aCappedPixelRatioIsAStageOfItsOwnAlsoAtTheSameLevel() {
        val capped = HIGH_PRESET.copy(pixelRatio = 1.25)
        assertEquals(listOf("pixelRatio"), ids(planChange(HIGH_PRESET, capped)))
        assertEquals(listOf("pixelRatio"), ids(planChange(capped, HIGH_PRESET)))
    }

    @Test
    fun marksTheStagesThatChangeShadersForAPrecompile() {
        val byId = planChange(MEDIUM_PRESET, LOW_PRESET).associateBy { it.id }
        assertFalse(byId.getValue("pixelRatio").compile)
        assertTrue(byId.getValue(MERGED_STAGE_ID).compile)
        assertTrue(byId.getValue("characters").compile)
        assertTrue(byId.getValue("density").compile)
    }

    @Test
    fun plansTheDensityStageAloneWhenOnlyADetailOfTheSceneryDiffers() {
        val changes =
            mapOf<String, QualityPreset>(
                "grassTufts" to MEDIUM_PRESET.copy(grassTufts = 0.9),
                "flowers" to MEDIUM_PRESET.copy(flowers = 0.9),
                "decor" to MEDIUM_PRESET.copy(decor = 0.9),
                "birds" to MEDIUM_PRESET.copy(birds = 0.9),
                "butterflies" to MEDIUM_PRESET.copy(butterflies = 0.9),
                "planters" to MEDIUM_PRESET.copy(planters = true),
                // grazing horses and hoof dust belong to the scenery stage
                "grazingHorses" to MEDIUM_PRESET.copy(grazingHorses = 2),
                "hoofDust" to MEDIUM_PRESET.copy(hoofDust = true),
            )
        for ((key, changed) in changes) {
            assertEquals(listOf("density"), ids(planChange(MEDIUM_PRESET, changed)), key)
        }
        // the wind code changes the shader programs of the scenery: the materials stage
        assertEquals(listOf("materials"), ids(planChange(MEDIUM_PRESET, MEDIUM_PRESET.copy(wind = true))))
    }

    @Test
    fun neverTouchesTheTexturesThereIsNoAnisotropyStage() {
        val known = QUALITY_STAGE_IDS.map { it.id } + MERGED_STAGE_ID
        for (from in GRAPHICS_LEVELS) {
            for (to in GRAPHICS_LEVELS) {
                for (id in ids(planChange(presetFor(from), presetFor(to)))) {
                    assertTrue(id in known, id)
                }
            }
        }
    }

    @Test
    fun plansEveryStageAtMostOnceAndNoneForAnUnknownTarget() {
        val plan = ids(planChange(HIGH_PRESET, LOW_PRESET))
        assertEquals(plan.size, plan.toSet().size)
        assertEquals(emptyList(), planChange(LOW_PRESET, null))
    }
}

class PlanQualityStagesFromStateTest {
    @Test
    fun plansOnlyTheStagesThatAreStillBehindAfterAnInterruptedSwitch() {
        val state =
            applied(MEDIUM_PRESET) +
                mapOf(QualityStageId.PIXEL_RATIO to LOW_PRESET, QualityStageId.SHADOWS to LOW_PRESET)
        assertEquals(
            listOf("materials", "characters", "density"),
            ids(planQualityStagesFromState(state, LOW_PRESET)),
        )
        // both shader stages still to do: one stage
        val early = applied(MEDIUM_PRESET) + (QualityStageId.PIXEL_RATIO to LOW_PRESET)
        assertEquals(
            listOf(MERGED_STAGE_ID, "characters", "density"),
            ids(planQualityStagesFromState(early, LOW_PRESET)),
        )
    }

    @Test
    fun turnsAroundWhenTheTargetChangesBackDuringASwitchPendingStagesFallAway() {
        val state = applied(MEDIUM_PRESET) + (QualityStageId.PIXEL_RATIO to LOW_PRESET)
        // target is medium again: only the resolution has to go back
        assertEquals(listOf("pixelRatio"), ids(planQualityStagesFromState(state, MEDIUM_PRESET)))
    }

    @Test
    fun plansNothingWhenEverythingAlreadyIsAtTheTarget() {
        assertEquals(emptyList(), planQualityStagesFromState(applied(LOW_PRESET), LOW_PRESET))
    }

    @Test
    fun usesTheUpwardOrderWhenEveryStageThatDiffersHasToGoUp() {
        assertEquals(
            listOf("density", "characters", MERGED_STAGE_ID, "pixelRatio"),
            ids(planQualityStagesFromState(applied(LOW_PRESET), HIGH_PRESET)),
        )
    }

    @Test
    fun aMissingEntryCountsAsNotAppliedYet() {
        assertTrue(planQualityStagesFromState(emptyMap(), LOW_PRESET).isNotEmpty())
    }
}

class StageQueueTest {
    private fun stage(id: String) = QualityStage(id, compile = false, covers = emptyList())

    @Test
    fun handsOutTheFirstStageOnTheNextFrameAndTheOthersAfterTheGap() {
        val queue = StageQueue(gapFrames = 3)
        queue.plan(listOf(stage("a"), stage("b"), stage("c")))
        assertEquals(3, queue.pending)
        assertEquals("a", queue.tick()?.id)
        assertNull(queue.tick())
        assertNull(queue.tick())
        assertEquals("b", queue.tick()?.id)
        assertNull(queue.tick())
        assertNull(queue.tick())
        assertEquals("c", queue.tick()?.id)
        assertEquals(0, queue.pending)
        assertNull(queue.tick())
    }

    @Test
    fun doesNothingWhileItIsEmpty() {
        val queue = StageQueue(gapFrames = 2)
        repeat(5) { assertNull(queue.tick()) }
        assertEquals(0, queue.pending)
    }

    @Test
    fun aNewPlanReplacesThePendingStagesButKeepsTheGapSinceTheLastStage() {
        val queue = StageQueue(gapFrames = 3)
        queue.plan(listOf(stage("a"), stage("b")))
        assertEquals("a", queue.tick()?.id)
        queue.plan(listOf(stage("x"), stage("y")))
        assertEquals(2, queue.pending)
        assertNull(queue.tick()) // the gap after "a" is still running
        assertNull(queue.tick())
        assertEquals("x", queue.tick()?.id)
    }

    @Test
    fun aPlanOnAnIdleQueueStartsAtOnceHoweverLongAgoTheLastStageRan() {
        val queue = StageQueue(gapFrames = 4)
        queue.plan(listOf(stage("a")))
        assertEquals("a", queue.tick()?.id)
        repeat(10) { queue.tick() }
        queue.plan(listOf(stage("b")))
        assertEquals("b", queue.tick()?.id)
    }

    @Test
    fun clearDropsEverythingThatIsPending() {
        val queue = StageQueue(gapFrames = 1)
        queue.plan(listOf(stage("a"), stage("b")))
        queue.clear()
        assertEquals(0, queue.pending)
        assertNull(queue.tick())
    }

    @Test
    fun worksWithAGapOf0OneStagePerFrame() {
        val queue = StageQueue(gapFrames = 0)
        queue.plan(listOf(stage("a"), stage("b")))
        assertEquals("a", queue.tick()?.id)
        assertEquals("b", queue.tick()?.id)
    }

    @Test
    fun copiesThePlanChangingTheListAfterwardsDoesNotChangeTheQueue() {
        val queue = StageQueue(gapFrames = 0)
        val plan = mutableListOf(stage("a"))
        queue.plan(plan)
        plan.clear()
        assertEquals(1, queue.pending)
        assertNotNull(queue.tick())
    }

    @Test
    fun usesTheDocumentedGapByDefault() {
        val queue = StageQueue()
        queue.plan(listOf(stage("a"), stage("b")))
        assertEquals("a", queue.tick()?.id)
        repeat(STAGE_GAP_FRAMES - 1) { assertNull(queue.tick()) }
        assertEquals("b", queue.tick()?.id)
    }
}

class SameMaterialStageTest {
    @Test
    fun tellsWhetherTwoPresetsNeedTheSameShaderPrograms() {
        // the wind code of high is a shader variant
        assertFalse(sameMaterialStage(MEDIUM_PRESET, HIGH_PRESET))
        assertTrue(sameMaterialStage(MEDIUM_PRESET, HIGH_PRESET.copy(wind = false)))
        assertFalse(sameMaterialStage(LOW_PRESET, MEDIUM_PRESET))
        assertFalse(sameMaterialStage(HIGH_PRESET, LOW_PRESET))
    }

    @Test
    fun agreesWithThePlanTheMaterialsStageIsPlannedExactlyWhenItIsFalse() {
        for (from in GRAPHICS_LEVELS) {
            for (to in GRAPHICS_LEVELS) {
                val plan = planChange(presetFor(from), presetFor(to))
                val planned = plan.any { QualityStageId.MATERIALS in it.covers }
                assertEquals(!sameMaterialStage(presetFor(from), presetFor(to)), planned, "$from -> $to")
            }
        }
    }

    @Test
    fun isNotTiedToTheFogDistancesWhichAreAUniformOfTheDensityStage() {
        val farther = MEDIUM_PRESET.copy(fog = FogRange(near = 100.0, far = 600.0))
        assertTrue(sameMaterialStage(MEDIUM_PRESET, farther))
    }
}
