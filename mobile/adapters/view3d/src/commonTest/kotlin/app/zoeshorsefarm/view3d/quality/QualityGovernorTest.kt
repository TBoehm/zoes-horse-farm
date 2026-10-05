package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertNear(
    expected: Double,
    actual: Double?,
    eps: Double,
) {
    assertNotNull(actual)
    assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")
}

/** Records the level changes the governor reports. */
private class Changes {
    val levels = mutableListOf<GraphicsLevel>()
    val fps = mutableListOf<Double>()
    val callback: (GraphicsLevel, Double) -> Unit = { level, rate ->
        levels += level
        fps += rate
    }
}

/** Runs the governor for `seconds` at a constant frame rate. */
private fun run(
    gov: QualityGovernor,
    seconds: Double,
    fps: Double,
    measuring: Boolean = true,
) {
    val dt = 1 / fps
    val n = (seconds * fps).roundToInt()
    repeat(n) { gov.frame(dt, measuring) }
}

class QualityGovernorTest {
    @Test
    fun downgradesOneLevelAfter3sGracePlus5sBelow50Fps() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, auto = true, onChange = changes.callback)
        run(gov, 3.0, 40.0) // grace period
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 4.9, 40.0)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 0.2, 40.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
        assertEquals(listOf(GraphicsLevel.MEDIUM), changes.levels)
        assertEquals(1, changes.fps.size)
    }

    @Test
    fun staysAt50FpsOrMore() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, onChange = changes.callback)
        run(gov, 60.0, 50.0)
        run(gov, 60.0, 60.0)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        assertTrue(changes.levels.isEmpty())
    }

    @Test
    fun waitsAtLeast10sAfterAnAdjustment() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, onChange = changes.callback)
        var t = 0.0
        val dt = 1.0 / 30
        val times = mutableListOf<Double>()
        repeat(30 * 40) {
            t += dt
            val before = gov.level
            gov.frame(dt, true)
            if (gov.level != before) times += t
        }
        assertEquals(listOf(GraphicsLevel.MEDIUM, GraphicsLevel.LOW), changes.levels)
        assertTrue(times[1] - times[0] >= 10 - 1e-6)
    }

    @Test
    fun neverBelowLow() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.LOW, onChange = changes.callback)
        run(gov, 60.0, 10.0)
        assertEquals(GraphicsLevel.LOW, gov.level)
        assertTrue(changes.levels.isEmpty())
    }

    @Test
    fun doesNotMeasureWithoutMeasuringAndNeeds3sGraceAfterwards() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 30.0, 20.0, measuring = false) // pause/menu: never measure
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 7.9, 20.0) // 3 s grace + 4.9 s measuring
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 0.2, 20.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
    }

    @Test
    fun anInterruptionResetsTheWindowAndTheGracePeriod() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 7.0, 30.0) // 3 s grace + 4 s measured
        gov.frame(1.0 / 30, false)
        run(gov, 7.0, 30.0) // again 3 s grace + only 4 s measured
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 1.1, 30.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
    }

    @Test
    fun aFrameLongerThan2sCountsAsAnInterruption() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 7.0, 30.0)
        gov.frame(2.5, true) // e.g. the machine was suspended
        run(gov, 7.0, 30.0)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 1.1, 30.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
    }

    @Test
    fun verySlowFramesAreAveragedNotTreatedAsInterruptions() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, onChange = changes.callback)
        // 0.6 s per frame is under 2 fps: the device is far too slow and must step down
        run(gov, 3.0, 1 / 0.6) // grace period
        run(gov, 6.0, 1 / 0.6)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
        assertEquals(listOf(GraphicsLevel.MEDIUM), changes.levels)
    }

    @Test
    fun aSingleLongFrameDoesNotResetTheWindowOfASlowDevice() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 3.0, 20.0) // grace
        run(gov, 3.0, 20.0) // 3 s of window collected
        gov.frame(0.8, true) // one hitch
        run(gov, 2.5, 20.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
    }

    @Test
    fun shortDropsAreAveragedOver5s() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, onChange = changes.callback)
        run(gov, 3.0, 60.0)
        // 4 s at 60 fps, 1 s at 30 fps: mean = 270 frames / 5 s = 54 fps
        repeat(6) {
            run(gov, 4.0, 60.0)
            run(gov, 1.0, 30.0)
        }
        assertEquals(GraphicsLevel.HIGH, gov.level)
        assertTrue(changes.levels.isEmpty())
    }

    @Test
    fun neverUpgrades() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 9.0, 30.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
        run(gov, 120.0, 144.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
    }

    @Test
    fun autoFalseNeverChanges() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, auto = false, onChange = changes.callback)
        run(gov, 60.0, 10.0)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        assertTrue(changes.levels.isEmpty())
    }

    @Test
    fun setAutoTrueStartsWithGraceAndSetLevelSetsManually() {
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.HIGH, auto = false, onChange = changes.callback)
        run(gov, 20.0, 20.0)
        gov.setAuto(true)
        run(gov, 7.9, 20.0)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        run(gov, 0.2, 20.0)
        assertEquals(GraphicsLevel.MEDIUM, gov.level)
        gov.setLevel(GraphicsLevel.HIGH)
        assertEquals(GraphicsLevel.HIGH, gov.level)
        assertEquals(1, changes.levels.size)
    }

    @Test
    fun usesTheClockWhenNoDtIsPassed() {
        var ms = 0.0
        val changes = Changes()
        val gov = QualityGovernor(GraphicsLevel.MEDIUM, onChange = changes.callback, now = { ms })
        repeat(400) {
            ms += 40.0 // 25 fps
            gov.frameFromClock(true)
        }
        assertEquals(GraphicsLevel.LOW, gov.level)
        assertEquals(listOf(GraphicsLevel.LOW), changes.levels)
    }

    @Test
    fun withoutAClockFrameFromClockChangesNothing() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        repeat(1000) { gov.frameFromClock(true) }
        assertEquals(GraphicsLevel.HIGH, gov.level)
    }

    @Test
    fun reportsTheMovingAverage() {
        val gov = QualityGovernor(GraphicsLevel.HIGH)
        run(gov, 3.0, 60.0)
        assertNull(gov.averageFps)
        run(gov, 5.0, 60.0)
        assertNear(60.0, gov.averageFps, 0.5)
    }
}

class LowFpsHintTest {
    /** Runs for `seconds`; returns how often the hint fired. */
    private fun runHint(
        hint: LowFpsHint,
        seconds: Double,
        fps: Double,
        measuring: Boolean = true,
    ): Int {
        val dt = 1 / fps
        var fired = 0
        repeat((seconds * fps).roundToInt()) {
            if (hint.frame(dt, measuring)) fired += 1
        }
        return fired
    }

    @Test
    fun firesAfter3sGracePlus5sBelow30Fps() {
        val hint = LowFpsHint()
        assertEquals(0, runHint(hint, 3.0, 20.0)) // grace period
        assertEquals(0, runHint(hint, 4.9, 20.0))
        assertEquals(1, runHint(hint, 0.2, 20.0))
    }

    @Test
    fun firesOnlyOnceHoweverLongTheFrameRateStaysLow() {
        assertEquals(1, runHint(LowFpsHint(), 60.0, 10.0))
    }

    @Test
    fun doesNotFireAt30FpsOrMore() {
        val hint = LowFpsHint()
        assertEquals(0, runHint(hint, 60.0, 30.0))
        assertEquals(0, runHint(hint, 60.0, 60.0))
    }

    @Test
    fun averagesShortDropsOver5s() {
        val hint = LowFpsHint()
        runHint(hint, 3.0, 60.0)
        // 4 s at 40 fps + 1 s at 10 fps: 170 frames / 5 s = 34 fps
        var fired = 0
        repeat(6) {
            fired += runHint(hint, 4.0, 40.0)
            fired += runHint(hint, 1.0, 10.0)
        }
        assertEquals(0, fired)
    }

    @Test
    fun doesNotMeasureWhileNotRidingAndNeedsTheGracePeriodAfterwards() {
        val hint = LowFpsHint()
        assertEquals(0, runHint(hint, 30.0, 10.0, measuring = false))
        assertEquals(0, runHint(hint, 7.9, 10.0)) // 3 s grace + 4.9 s measuring
        assertEquals(1, runHint(hint, 0.2, 10.0))
    }

    @Test
    fun anInterruptionResetsTheWindowAndTheGracePeriod() {
        val hint = LowFpsHint()
        runHint(hint, 7.0, 10.0) // 3 s grace + 4 s measured
        hint.interrupt()
        assertEquals(0, runHint(hint, 7.0, 10.0))
        assertEquals(1, runHint(hint, 1.1, 10.0))
    }

    @Test
    fun resetStartsANewRideItCanFireAgainAndNeedsTheGracePeriodAgain() {
        val hint = LowFpsHint()
        assertEquals(1, runHint(hint, 60.0, 10.0))
        assertEquals(0, runHint(hint, 60.0, 10.0))
        hint.reset()
        assertEquals(0, runHint(hint, 7.9, 10.0))
        assertEquals(1, runHint(hint, 0.2, 10.0))
    }

    @Test
    fun aFrameLongerThan2sCountsAsAnInterruption() {
        val hint = LowFpsHint()
        runHint(hint, 7.0, 10.0)
        hint.frame(2.5, true)
        assertEquals(0, runHint(hint, 7.0, 10.0))
        assertEquals(1, runHint(hint, 1.1, 10.0))
    }

    @Test
    fun showsTheHintAgainForANewRideANewInstance() {
        assertEquals(1, runHint(LowFpsHint(), 20.0, 10.0))
        assertEquals(1, runHint(LowFpsHint(), 20.0, 10.0))
    }

    @Test
    fun ignoresInvalidFrameTimes() {
        val hint = LowFpsHint()
        for (bad in listOf(Double.NaN, -1.0)) assertFalse(hint.frame(bad, true))
    }

    @Test
    fun acceptsOtherThresholdsThroughOptions() {
        val hint = LowFpsHint(LowFpsHintOptions(windowS = 2.0, graceS = 0.0, maxFps = 20.0))
        assertEquals(1, runHint(hint, 2.1, 15.0))
    }
}

class CanHintLowerLevelTest {
    @Test
    fun isTrueOnlyForAManualLevelAboveLow() {
        assertTrue(canHintLowerLevel(auto = false, level = GraphicsLevel.HIGH))
        assertTrue(canHintLowerLevel(auto = false, level = GraphicsLevel.MEDIUM))
    }

    @Test
    fun isFalseAtTheLowestLevelThereIsNothingLowerToPick() {
        assertFalse(canHintLowerLevel(auto = false, level = GraphicsLevel.LOW))
    }

    @Test
    fun isFalseWithAutomaticOnTheGovernorHandlesIt() {
        assertFalse(canHintLowerLevel(auto = true, level = GraphicsLevel.HIGH))
    }
}

class LevelAfterContextLossTest {
    private fun outcome(
        level: GraphicsLevel,
        persist: Boolean,
        hint: Boolean,
        counted: Boolean,
    ) = ContextLossOutcome(level, persist, hint, counted)

    @Test
    fun withAutomaticOnALevelAboveLowGoesToLowAndIsSaved() {
        for (level in listOf(GraphicsLevel.HIGH, GraphicsLevel.MEDIUM)) {
            assertEquals(
                outcome(GraphicsLevel.LOW, persist = true, hint = false, counted = true),
                levelAfterContextLoss(auto = true, level = level),
            )
        }
    }

    @Test
    fun withAutomaticOnAtLowNothingChangesAndNothingIsSaved() {
        assertEquals(
            outcome(GraphicsLevel.LOW, persist = false, hint = false, counted = true),
            levelAfterContextLoss(auto = true, level = GraphicsLevel.LOW),
        )
    }

    @Test
    fun aManualLevelAboveLowStaysAndThePlayerGetsAHint() {
        for (level in listOf(GraphicsLevel.MEDIUM, GraphicsLevel.HIGH)) {
            assertEquals(
                outcome(level, persist = false, hint = true, counted = true),
                levelAfterContextLoss(auto = false, level = level),
            )
        }
    }

    @Test
    fun aManualLowLevelNeedsNoHintThereIsNothingLowerToPick() {
        assertEquals(
            outcome(GraphicsLevel.LOW, persist = false, hint = false, counted = true),
            levelAfterContextLoss(auto = false, level = GraphicsLevel.LOW),
        )
    }

    @Test
    fun aLossWhileThePageIsInTheBackgroundIsNoOverloadNothingChangesNoHint() {
        for (auto in listOf(true, false)) {
            for (level in GRAPHICS_LEVELS) {
                assertEquals(
                    outcome(level, persist = false, hint = false, counted = false),
                    levelAfterContextLoss(auto = auto, level = level, visible = false),
                )
            }
        }
    }

    @Test
    fun aLossRightAfterThePageCameBackToTheForegroundIsNoOverloadEither() {
        val justBack = CONTEXT_LOSS_GRACE_S - 0.1
        assertEquals(
            outcome(GraphicsLevel.HIGH, persist = false, hint = false, counted = false),
            levelAfterContextLoss(
                auto = true,
                level = GraphicsLevel.HIGH,
                visible = true,
                sinceVisibilityChangeS = justBack,
            ),
        )
        assertFalse(
            levelAfterContextLoss(
                auto = false,
                level = GraphicsLevel.HIGH,
                visible = true,
                sinceVisibilityChangeS = justBack,
            ).hint,
        )
    }

    @Test
    fun namesACrashAsTheReasonOnlyWhenTheAutomaticLevelReallyWasLowered() {
        fun crash(
            level: GraphicsLevel?,
            auto: Boolean = true,
        ) = StartupCrash(crashed = true, auto = auto, level = level)
        assertEquals(StartupChange.CRASH, startupCrashChange(crash(GraphicsLevel.MEDIUM)))
        assertEquals(StartupChange.CRASH, startupCrashChange(crash(GraphicsLevel.HIGH)))
        // already at low (or unknown), manual, or no crash: no change was made
        assertNull(startupCrashChange(crash(GraphicsLevel.LOW)))
        assertNull(startupCrashChange(crash(null)))
        assertNull(startupCrashChange(crash(GraphicsLevel.HIGH, auto = false)))
        assertNull(startupCrashChange(StartupCrash(crashed = false)))
        assertNull(startupCrashChange(null))
    }

    @Test
    fun aLossInTheForegroundAfterTheGraceTimeCountsAsBefore() {
        val settled = CONTEXT_LOSS_GRACE_S
        assertEquals(
            outcome(GraphicsLevel.LOW, persist = true, hint = false, counted = true),
            levelAfterContextLoss(
                auto = true,
                level = GraphicsLevel.HIGH,
                visible = true,
                sinceVisibilityChangeS = settled,
            ),
        )
        assertTrue(
            levelAfterContextLoss(
                auto = false,
                level = GraphicsLevel.HIGH,
                visible = true,
                sinceVisibilityChangeS = settled,
            ).hint,
        )
    }

    @Test
    fun withoutVisibilityInformationALossCountsVisibleNoChangeSeen() {
        assertEquals(GraphicsLevel.LOW, levelAfterContextLoss(auto = true, level = GraphicsLevel.MEDIUM).level)
    }

    @Test
    fun theHintFollowsTheSameRuleAsTheLevelTooHighHint() {
        for (auto in listOf(true, false)) {
            for (level in GRAPHICS_LEVELS) {
                assertEquals(
                    canHintLowerLevel(auto, level),
                    levelAfterContextLoss(auto = auto, level = level).hint,
                )
            }
        }
    }
}
