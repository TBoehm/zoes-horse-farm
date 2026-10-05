package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertNear(
    expected: Double,
    actual: Double,
    eps: Double,
) = assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

/** Frames of a constant rate for `seconds`; returns the first result that is not null. */
private fun run(
    gov: UpgradeGovernor,
    seconds: Double,
    fps: Double,
    measuring: Boolean = true,
    busy: Boolean = false,
): UpgradeStep? {
    val dt = 1 / fps
    repeat((seconds * fps).roundToInt()) {
        val result = gov.frame(dt, measuring, busy)
        if (result != null) return result
    }
    return null
}

/** Governor that is allowed to go to medium; counts how often it was asked for a target. */
private class Setup(
    options: UpgradeOptions = UpgradeOptions(),
) {
    var asked = 0
    val gov =
        UpgradeGovernor(
            chooseTarget = {
                asked += 1
                GraphicsLevel.MEDIUM
            },
            options = options,
        )
}

class FrameWindowSlowFramesTest {
    @Test
    fun countsTheShareOfFramesSlowerThanTheLimitAndForgetsOldOnes() {
        val window = FrameWindow(1.0, 0.025)
        repeat(9) { window.push(0.01) } // 90 ms
        window.push(0.05) // slow
        assertNear(0.1, window.slowShare(), 1e-9)
        repeat(100) { window.push(0.01) } // the slow frame leaves the window
        assertEquals(0.0, window.slowShare())
        window.clear()
        assertEquals(0.0, window.slowShare())
    }

    @Test
    fun countsNoneWithoutALimit() {
        val window = FrameWindow(1.0)
        window.push(5.0)
        assertEquals(0.0, window.slowShare())
    }

    @Test
    fun keepsAWindowOfRecentFramesAndAveragesThem() {
        val window = FrameWindow(1.0)
        assertNull(window.averageFps())
        repeat(100) { window.push(0.01) }
        assertTrue(window.full)
        assertNear(100.0, window.averageFps() ?: 0.0, 1.0)
        // far more frames than the initial capacity: still a moving window of 1 s
        repeat(5000) { window.push(0.001) }
        assertNear(1000.0, window.averageFps() ?: 0.0, 5.0)
    }
}

class UpgradeGovernorTest {
    @Test
    fun stepsUpAfter3sWarmUpAnd10sAt60FpsAndTellsLevelAndFrameRate() {
        val setup = Setup(UpgradeOptions(cooldownS = 0.0))
        assertNull(run(setup.gov, 12.9, 60.0))
        val step = run(setup.gov, 0.3, 60.0)
        assertNotNull(step)
        assertEquals(GraphicsLevel.MEDIUM, step.level)
        assertNear(60.0, step.fps, 0.5)
    }

    @Test
    fun usesTheDocumentedLimits() {
        assertEquals(10.0, UPGRADE_DEFAULTS.windowS)
        assertEquals(57.0, UPGRADE_DEFAULTS.minFps)
        assertEquals(3.0, UPGRADE_DEFAULTS.graceS)
        assertEquals(20.0, UPGRADE_DEFAULTS.cooldownS)
        assertEquals(0.025, UPGRADE_DEFAULTS.slowFrameS)
        assertEquals(0.02, UPGRADE_DEFAULTS.maxSlowShare)
    }

    // frame rate

    @Test
    fun doesNotStepUpBelow57FpsOnAverage() {
        assertNull(run(Setup().gov, 60.0, 56.0))
    }

    @Test
    fun stepsUpAtExactly57Fps() {
        assertEquals(GraphicsLevel.MEDIUM, run(Setup().gov, 20.0, 57.0)?.level)
    }

    @Test
    fun averagesOverThe10sWindowAWeakStretchHoldsTheStepBackUntilItHasSlidOut() {
        val gov = Setup(UpgradeOptions(cooldownS = 0.0)).gov
        run(gov, 3.0, 60.0) // warm-up
        assertNull(run(gov, 5.0, 45.0)) // 22 ms frames: no hitches, but not enough reserve
        assertNull(run(gov, 4.9, 60.0)) // 10 s window at 52 fps on average
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 10.0, 60.0)?.level) // the weak stretch has slid out
    }

    // slow frames

    /** Every `every`-th frame takes `slowS`, the others `baseS`. */
    private fun runWithHitches(
        gov: UpgradeGovernor,
        seconds: Double,
        every: Int,
        slowS: Double,
        baseS: Double = 1.0 / 100,
    ): UpgradeStep? {
        var t = 0.0
        var i = 0
        while (t < seconds) {
            i += 1
            val dt = if (i % every == 0) slowS else baseS
            t += dt
            val result = gov.frame(dt, true, false)
            if (result != null) return result
        }
        return null
    }

    @Test
    fun doesNotStepUpWhenMoreThan2PercentOfTheFramesAreSlowerThan25ms() {
        // every 40th frame (2.5 %) takes 30 ms, the rest 10 ms: the average is above 57 fps
        assertNull(runWithHitches(Setup().gov, 60.0, 40, 0.03))
    }

    @Test
    fun stepsUpWithAFewSlowFramesAtMost2Percent() {
        // every 100th frame (1 %) takes 30 ms
        assertEquals(GraphicsLevel.MEDIUM, runWithHitches(Setup().gov, 60.0, 100, 0.03)?.level)
    }

    @Test
    fun the25msIsTheLimitAFrameOf24Point9msIsNoHitchOneOf25Point1msIs() {
        // every second frame is long, the others 5 ms: the average is above 57 fps either way
        assertEquals(GraphicsLevel.MEDIUM, runWithHitches(Setup().gov, 60.0, 2, 0.0249, 0.005)?.level)
        assertNull(runWithHitches(Setup().gov, 60.0, 2, 0.0251, 0.005))
    }

    // warm-up

    @Test
    fun doesNotCountTheFirst3sAfterTheStart() {
        val gov = Setup(UpgradeOptions(cooldownS = 0.0)).gov
        // 3 s of warm-up at 10 fps would ruin the window otherwise; here they are not measured
        run(gov, 3.0, 10.0)
        assertNull(run(gov, 9.9, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 0.2, 60.0)?.level)
    }

    @Test
    fun anInterruptionPauseMenuHiddenTabClearsTheWindowAndWarmsUpAgain() {
        val gov = Setup().gov
        run(gov, 12.0, 60.0)
        gov.interrupt()
        assertNull(run(gov, 12.9, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 0.3, 60.0)?.level)
    }

    @Test
    fun framesWhileNotMeasuringNeverCount() {
        val gov = Setup().gov
        assertNull(run(gov, 60.0, 60.0, measuring = false))
        assertNull(run(gov, 12.9, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 0.3, 60.0)?.level)
    }

    @Test
    fun aFrameLongerThan2sIsAnInterruption() {
        val gov = Setup().gov
        run(gov, 12.0, 60.0)
        assertNull(gov.frame(2.5, true, false))
        assertNull(run(gov, 12.9, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 0.3, 60.0)?.level)
    }

    @Test
    fun ignoresInvalidFrameTimes() {
        val gov = Setup().gov
        assertNull(gov.frame(Double.NaN, true, false))
        assertNull(gov.frame(-1.0, true, false))
    }

    // cooldown

    @Test
    fun waits20sAfterAStepBeforeTheNextOne() {
        val gov = UpgradeGovernor(chooseTarget = { GraphicsLevel.HIGH })
        var t = 0.0
        val times = mutableListOf<Double>()
        repeat(60 * 80) {
            t += 1.0 / 60
            if (gov.frame(1.0 / 60, true, false) != null) times += t
        }
        // first step after warm-up + window, then every 20 s (the window is refilled by then)
        assertTrue(times[0] > 12.9)
        assertTrue(times[0] < 13.5)
        assertTrue(times[1] - times[0] >= 20 - 1e-6)
        assertTrue(times[1] - times[0] < 21)
    }

    @Test
    fun alsoWaits20sAfterAChangeFromOutsideTheDowngradeAContextLossAManualPick() {
        val gov = Setup().gov
        run(gov, 14.0, 60.0) // the window would be full
        gov.noteChange()
        // 3 s warm-up + 10 s window are fine again after 13 s, but the 20 s are not over yet
        assertNull(run(gov, 19.5, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 1.0, 60.0)?.level)
    }

    @Test
    fun doesNotRunDownWhileNothingIsMeasuredPauseMenus() {
        val gov = Setup().gov
        gov.noteChange()
        run(gov, 100.0, 60.0, measuring = false)
        assertNull(run(gov, 19.0, 60.0))
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 2.0, 60.0)?.level)
    }

    // jump in progress

    @Test
    fun neverStartsAStepWhileBusyItFollowsRightAfterTheJumpWhenTheWindowIsFine() {
        val gov = Setup(UpgradeOptions(cooldownS = 0.0)).gov
        assertNull(run(gov, 30.0, 60.0, busy = true))
        // the jump is over: the window is still full of good frames
        assertEquals(GraphicsLevel.MEDIUM, gov.frame(1.0 / 60, true, false)?.level)
    }

    @Test
    fun aJumpDoesNotResetTheMeasurement() {
        val gov = Setup(UpgradeOptions(cooldownS = 0.0)).gov
        run(gov, 12.8, 60.0) // 9.8 s of the window
        assertNull(run(gov, 0.5, 60.0, busy = true)) // the window fills up in the jump
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 0.1, 60.0)?.level)
    }

    // target

    @Test
    fun asksForTheTargetOnlyWhenEverythingElseSaysGo() {
        val setup = Setup()
        run(setup.gov, 12.0, 60.0)
        assertEquals(0, setup.asked)
        run(setup.gov, 10.0, 60.0, busy = true)
        assertEquals(0, setup.asked)
        assertEquals(GraphicsLevel.MEDIUM, run(setup.gov, 1.0, 60.0)?.level)
        assertEquals(1, setup.asked)
    }

    @Test
    fun noAllowedTargetNoStepAndTheWindowStartsOverNoCheckOnEveryFrame() {
        var asked = 0
        val gov =
            UpgradeGovernor(
                chooseTarget = {
                    asked += 1
                    null
                },
                options = UpgradeOptions(cooldownS = 0.0),
            )
        assertNull(run(gov, 60.0, 60.0))
        // 3 s warm-up, then a check after each full window of 10 s: 13 s, 23 s, 33 s, ...
        assertTrue(asked >= 3)
        assertTrue(asked <= 6)
    }

    @Test
    fun aStepNeedsNoExtraChangeNoticeTheGovernorStartsItsOwnCooldown() {
        val gov = Setup().gov
        assertEquals(GraphicsLevel.MEDIUM, run(gov, 25.0, 60.0)?.level)
        assertNull(run(gov, 19.0, 60.0))
    }
}

class NextUpgradeLevelTest {
    @Test
    fun isTheNextLevelUp() {
        assertEquals(GraphicsLevel.MEDIUM, nextUpgradeLevel(GraphicsLevel.LOW))
        assertEquals(GraphicsLevel.HIGH, nextUpgradeLevel(GraphicsLevel.MEDIUM))
    }

    @Test
    fun neverGoesAboveHigh() {
        assertNull(nextUpgradeLevel(GraphicsLevel.HIGH))
    }

    @Test
    fun neverGoesToABlockedLevelAndDoesNotJumpOverIt() {
        assertNull(nextUpgradeLevel(GraphicsLevel.LOW, blocked = listOf(GraphicsLevel.MEDIUM)))
        assertEquals(GraphicsLevel.MEDIUM, nextUpgradeLevel(GraphicsLevel.LOW, blocked = listOf(GraphicsLevel.HIGH)))
        assertNull(nextUpgradeLevel(GraphicsLevel.MEDIUM, blocked = listOf(GraphicsLevel.HIGH)))
    }

    @Test
    fun neverGoesToALevelTheAutomaticLeftBecauseOfALowFrameRateInThisSession() {
        assertNull(nextUpgradeLevel(GraphicsLevel.LOW, left = setOf(GraphicsLevel.MEDIUM)))
        assertEquals(GraphicsLevel.MEDIUM, nextUpgradeLevel(GraphicsLevel.LOW, left = listOf(GraphicsLevel.HIGH)))
    }

    @Test
    fun neverGoesToALevelThatDoesNotFitTheMemoryBudgetOfTheDevice() {
        val asked = mutableListOf<GraphicsLevel>()
        val fits = { level: GraphicsLevel ->
            asked += level
            level != GraphicsLevel.HIGH
        }
        assertEquals(GraphicsLevel.MEDIUM, nextUpgradeLevel(GraphicsLevel.LOW, fits = fits))
        assertNull(nextUpgradeLevel(GraphicsLevel.MEDIUM, fits = fits))
        assertTrue(GraphicsLevel.HIGH in asked)
    }

    @Test
    fun doesNotAskTheBudgetForALevelThatIsExcludedAnyway() {
        var asked = false
        val fits = { _: GraphicsLevel ->
            asked = true
            true
        }
        assertNull(nextUpgradeLevel(GraphicsLevel.LOW, blocked = listOf(GraphicsLevel.MEDIUM), fits = fits))
        assertTrue(!asked)
    }
}

class UpgradeMeasuringTest {
    @Test
    fun measuresOnlyWhileRidingWithAutomaticOn() {
        assertTrue(upgradeMeasuring(measuring = true, auto = true))
        assertTrue(!upgradeMeasuring(measuring = false, auto = true))
    }

    @Test
    fun neverMeasuresWithAManualLevelHoweverFastTheFramesAre() {
        assertTrue(!upgradeMeasuring(measuring = true, auto = false))
        val setup = Setup()
        // the gate feeds the governor: a manual level never gets a measurement, so it never climbs
        assertNull(run(setup.gov, 60.0, 60.0, measuring = upgradeMeasuring(measuring = true, auto = false)))
        assertEquals(0, setup.asked)
    }

    @Test
    fun letsTheGovernorClimbWhenAutomaticIsOn() {
        val setup = Setup()
        val measuring = upgradeMeasuring(measuring = true, auto = true)
        assertEquals(GraphicsLevel.MEDIUM, run(setup.gov, 60.0, 60.0, measuring = measuring)?.level)
    }
}
