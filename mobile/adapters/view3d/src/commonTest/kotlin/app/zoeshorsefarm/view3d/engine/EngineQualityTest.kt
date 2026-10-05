package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.DEFAULT_APPEARANCE
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.quality.STAGE_GAP_FRAMES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Level changes (rule 4): applied at once while nothing is drawn, in stages while a ride draws, and
// the two governors of "Automatic".

class EngineQualityTest {
    /** [seconds] of governor frames at [fps]. */
    private fun measure(
        rig: EngineRig,
        seconds: Double,
        fps: Double,
        busy: Boolean = false,
    ) {
        repeat((seconds * fps).toInt()) { rig.engine.governorFrame(1.0 / fps, measuring = true, busy = busy) }
    }

    @Test
    fun appliesALevelChangeAtOnceWhileNoRideDraws() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.settings.setGraphicsLevel(GraphicsLevel.HIGH)
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
        assertTrue(rig.fake.shadowsEnabled)
        assertEquals(0, rig.engine.diagnostics().stagesPending)
        assertEquals(2, rig.fake.compileCount) // the first level and the switch
    }

    @Test
    fun theNewPixelRatioWaitsForTheFrameThatDrawsAgain() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.settings.setGraphicsLevel(GraphicsLevel.HIGH)
        assertEquals(1.0, rig.fake.pixelRatio) // not yet: resizing clears the drawing buffer
        assertTrue(rig.engine.settling)
        rig.run()
        rig.frames(1)
        assertEquals(2.0, rig.fake.pixelRatio)
        assertFalse(rig.engine.settling)
    }

    @Test
    fun aLevelChangeDuringARideIsSplitIntoStagesSixFramesApart() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        rig.run()
        rig.frames(3)
        rig.settings.setGraphicsLevel(GraphicsLevel.LOW)
        assertTrue(rig.engine.settling)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        val pending = ArrayList<Int>()
        var guardFrames = 0
        while (rig.engine.settling && guardFrames++ < 600) {
            rig.frames(1)
            pending.add(rig.engine.diagnostics().stagesPending)
        }
        assertFalse(rig.engine.settling)
        val steps = pending.indices.filter { it > 0 && pending[it] < pending[it - 1] }
        assertTrue(steps.size >= 3, "stages: $steps")
        for (i in 1 until steps.size) assertTrue(steps[i] - steps[i - 1] >= STAGE_GAP_FRAMES)
        assertFalse(rig.fake.shadowsEnabled)
        assertEquals(1.0, rig.fake.pixelRatio)
    }

    @Test
    fun stagesGoOnWhileTheRideIsPausedAndTheyAreDrawn() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        rig.run()
        rig.frames(3)
        rig.engine.setPaused(true)
        rig.frames(2)
        rig.settings.setGraphicsLevel(GraphicsLevel.LOW)
        var guardFrames = 0
        while (rig.engine.settling && guardFrames++ < 600) rig.frames(1)
        assertFalse(rig.engine.settling)
        assertFalse(rig.fake.shadowsEnabled)
    }

    @Test
    fun aClimbEndsWithTheResolution() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.run()
        rig.frames(3)
        rig.settings.setGraphicsLevel(GraphicsLevel.HIGH)
        rig.frames(1)
        // the pixel ratio is the last stage of a climb: at first nothing sharper is drawn
        assertEquals(1.0, rig.fake.pixelRatio)
        var guardFrames = 0
        while (rig.engine.settling && guardFrames++ < 600) rig.frames(1)
        assertEquals(2.0, rig.fake.pixelRatio)
        assertTrue(rig.fake.shadowsEnabled)
    }

    @Test
    fun aManualPickTurnsTheAutomaticOff() {
        val rig = EngineRig(GraphicsLevel.LOW, auto = true)
        rig.settings.setGraphicsLevel(GraphicsLevel.MEDIUM)
        assertFalse(rig.engine.diagnostics().auto)
    }

    @Test
    fun selectingAutomaticAgainGoesBackToLow() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        rig.settings.setGraphicsAuto()
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        assertTrue(rig.engine.diagnostics().auto)
    }

    @Test
    fun theDowngradeGovernorStepsDownBelowFiftyFpsAndSavesTheLevel() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        measure(rig, 9.0, 30.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        assertEquals(GraphicsLevel.LOW, rig.settings.get().graphicsLevel)
        assertTrue(rig.settings.get().graphicsAuto) // "Automatic" stays on
        val d = rig.engine.diagnostics()
        assertEquals(ChangeKind.DOWN, d.lastChange?.kind)
        assertClose(30.0, d.lastChange?.fps ?: 0.0, 0)
        assertEquals(listOf(GraphicsLevel.MEDIUM), d.leftLevels)
    }

    @Test
    fun aManualLevelIsNeverLowered() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        measure(rig, 30.0, 20.0)
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
    }

    @Test
    fun theUpgradeGovernorClimbsOneLevelWithRoomToSpare() {
        val rig = EngineRig(GraphicsLevel.LOW, auto = true)
        measure(rig, 14.0, 60.0)
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
        assertEquals(GraphicsLevel.MEDIUM, rig.settings.get().graphicsLevel)
        assertEquals(
            ChangeKind.UP,
            rig.engine
                .diagnostics()
                .lastChange
                ?.kind,
        )
        // a cooldown after every change: the next climb takes its time
        measure(rig, 10.0, 60.0)
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
        measure(rig, 40.0, 60.0)
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
    }

    @Test
    fun theUpgradeNeverStartsWhileAJumpIsInProgress() {
        val rig = EngineRig(GraphicsLevel.LOW, auto = true)
        measure(rig, 20.0, 60.0, busy = true)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        measure(rig, 1.0, 60.0)
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
    }

    @Test
    fun aManualLevelIsNeverRaised() {
        val rig = EngineRig(GraphicsLevel.LOW)
        measure(rig, 60.0, 60.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
    }

    @Test
    fun theUpgradeSkipsALevelThatCrashedBefore() {
        val rig = EngineRig(GraphicsLevel.LOW, auto = true)
        rig.crashGuard.blockLevel(GraphicsLevel.MEDIUM)
        measure(rig, 30.0, 60.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        assertEquals(listOf(GraphicsLevel.MEDIUM), rig.engine.diagnostics().blockedLevels)
    }

    @Test
    fun theUpgradeSkipsALevelThatDoesNotFitTheBudget() {
        val rig = EngineRig(GraphicsLevel.LOW, auto = true, budgetMB = 30)
        measure(rig, 30.0, 60.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
    }

    @Test
    fun doesNotClimbBackToALevelItSteppedDownFromUntilAutomaticIsSelectedAnew() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        measure(rig, 9.0, 30.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        measure(rig, 40.0, 60.0)
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        rig.settings.setGraphicsAuto()
        assertTrue(
            rig.engine
                .diagnostics()
                .leftLevels
                .isEmpty(),
        )
        measure(rig, 14.0, 60.0)
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
    }

    @Test
    fun theFrameFeedReplacesTheRealFrameTimesAndCountsTheFedTime() {
        val rig = EngineRig(GraphicsLevel.HIGH, auto = true)
        val feed = FrameFeed(repeat = 2, dt = 1.0 / 60)
        rig.engine.frameFeed = feed
        measure(rig, 20.0, 20.0) // real frames of 20 fps would step down
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
        assertClose(2.0 / 60 * 400, feed.fedSeconds, 6)
    }

    @Test
    fun theLowFrameRateHintFiresOnceForAManualLevelThatIsTooHigh() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        var fired = 0
        repeat(12 * 20) { if (rig.engine.lowFpsHintFrame(1.0 / 20, measuring = true)) fired++ }
        assertEquals(1, fired)
    }

    @Test
    fun aNewRideMayShowTheHintOnceMore() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        repeat(12 * 20) { rig.engine.lowFpsHintFrame(1.0 / 20, measuring = true) }
        rig.engine.restartRide(DEFAULT_APPEARANCE, Horse())
        var fired = 0
        repeat(12 * 20) { if (rig.engine.lowFpsHintFrame(1.0 / 20, measuring = true)) fired++ }
        assertEquals(1, fired)
    }

    @Test
    fun noHintWhileNothingIsMeasured() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        repeat(20 * 20) { assertFalse(rig.engine.lowFpsHintFrame(1.0 / 20, measuring = false)) }
    }

    @Test
    fun interruptingTheMeasurementStartsTheGraceTimeAgain() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        repeat(7 * 20) { rig.engine.lowFpsHintFrame(1.0 / 20, measuring = true) }
        // not fired yet: 3 s grace plus a 5 s window need 8 s
        rig.engine.interruptMeasuring()
        var fired = 0
        repeat(7 * 20) { if (rig.engine.lowFpsHintFrame(1.0 / 20, measuring = true)) fired++ }
        assertEquals(0, fired)
    }

    @Test
    fun theHintOnlyMakesSenseForAManualLevelAboveLow() {
        val high = EngineRig(GraphicsLevel.HIGH).engine
        assertTrue(high.canHintLowerLevel(auto = false))
        assertFalse(high.canHintLowerLevel(auto = true))
        assertFalse(EngineRig(GraphicsLevel.LOW).engine.canHintLowerLevel(auto = false))
    }

    @Test
    fun theThirtyFpsLeverTurnsTheMeasurementOff() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true, capTo30Fps = true)
        measure(rig, 30.0, 30.0)
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
        var fired = false
        repeat(12 * 30) { if (rig.engine.lowFpsHintFrame(1.0 / 30, measuring = true)) fired = true }
        assertFalse(fired)
        assertNull(rig.engine.diagnostics().lastChange)
    }
}
