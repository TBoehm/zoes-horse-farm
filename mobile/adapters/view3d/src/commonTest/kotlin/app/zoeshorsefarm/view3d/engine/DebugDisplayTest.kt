package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.LastCrash
import app.zoeshorsefarm.view3d.quality.PixelRatioCap
import app.zoeshorsefarm.view3d.quality.ShadowMapCap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of debug-display.test.js: the text of the debug box. Every translated piece is marked so that
// the tests see that no visible word is built in code.

private val t: Translate = { key, params ->
    val entries = params.orEmpty().entries.joinToString(" ") { (k, v) -> "$k=$v" }
    "[$key${if (entries.isNotEmpty()) " $entries" else ""}]"
}

private fun base() =
    EngineDiagnostics().also {
        it.gpu = "ANGLE (Mali-G52)"
        it.budgetGpu = "ANGLE (Mali-G52)"
        it.level = GraphicsLevel.MEDIUM
        it.auto = true
        it.devicePixelRatio = 2.625
        it.pixelRatio = 1.5
        it.bufferWidth = 1200
        it.bufferHeight = 750
        it.maxTextureSize = 4096
        it.gpuEstimateMB = 123.4
        it.gpuBudgetMB = 160.0
        it.antialias = true
    }

private fun lines(
    info: EngineDiagnostics,
    errors: List<DebugError> = emptyList(),
    lastCrash: LastCrash? = null,
): List<String> = formatDebugText(info, errors, t, lastCrash).split("\n")

private fun EngineDiagnostics.with(change: EngineDiagnostics.() -> Unit): EngineDiagnostics = apply(change)

class DebugDisplayTest {
    @Test
    fun showsTheGpuLevelWithTheAutomaticFlagPixelRatiosBufferAndTextureSize() {
        val out = lines(base())
        assertEquals("[debug.gpu gpu=ANGLE (Mali-G52)]", out[0])
        assertEquals("[debug.levelAuto level=[graphics.medium]]", out[1])
        assertEquals("[debug.pixels device=2.63 renderer=1.5]", out[2])
        assertEquals("[debug.buffer width=1200 height=750]", out[3])
        assertEquals("[debug.maxTexture size=4096]", out[4])
    }

    @Test
    fun showsLevelModeSecondsAndTimeOfTheLastDetectedCrash() {
        val crash = LastCrash(GraphicsLevel.MEDIUM, auto = true, seconds = 5, at = "2026-10-04T13:05:07.123Z")
        assertTrue(
            "[debug.crash level=[graphics.medium] mode=[debug.crashAuto] s=5 at=2026-10-04 13:05]" in
                lines(base(), lastCrash = crash),
        )
        val manual = crash.copy(auto = false, level = GraphicsLevel.HIGH)
        assertTrue(
            "[debug.crash level=[graphics.high] mode=[debug.crashManual] s=5 at=2026-10-04 13:05]" in
                lines(base(), lastCrash = manual),
        )
    }

    @Test
    fun saysSoWhenNoCrashWasDetected() {
        assertTrue("[debug.noCrash]" in lines(base()))
    }

    @Test
    fun listsTheBlockedLevelsWithTheirNamesOrSaysThereAreNone() {
        val blocked = base().with { blockedLevels = listOf(GraphicsLevel.MEDIUM, GraphicsLevel.HIGH) }
        assertTrue("[debug.blocked levels=[graphics.medium], [graphics.high]]" in lines(blocked))
        assertTrue("[debug.noBlocked]" in lines(base()))
        assertTrue("[debug.noBlocked]" in lines(base().with { blockedLevels = emptyList() }))
    }

    @Test
    fun listsTheLevelsLeftBecauseOfALowFrameRateOnlyWhenThereAreAny() {
        assertTrue(
            "[debug.left levels=[graphics.high]]" in lines(base().with { leftLevels = listOf(GraphicsLevel.HIGH) }),
        )
        assertFalse(lines(base()).any { it.startsWith("[debug.left") })
    }

    @Test
    fun showsTheReasonOfTheLastAutomaticChange() {
        fun reason(change: LevelChange) = lines(base().with { lastChange = change })
        assertTrue("[debug.lastChange reason=[debug.reasonUp fps=58]]" in reason(LevelChange(ChangeKind.UP, 58.4)))
        assertTrue("[debug.lastChange reason=[debug.reasonDown fps=42]]" in reason(LevelChange(ChangeKind.DOWN, 41.6)))
        assertTrue("[debug.lastChange reason=[debug.reasonLoss]]" in reason(LevelChange(ChangeKind.LOSS)))
        assertTrue("[debug.lastChange reason=[debug.reasonCrash]]" in reason(LevelChange(ChangeKind.CRASH)))
    }

    @Test
    fun saysSoWhenThereWasNoAutomaticChangeYet() {
        assertTrue("[debug.noChange]" in lines(base()))
        assertTrue("[debug.noChange]" in lines(base().with { lastChange = null }))
    }

    @Test
    fun showsTheGpuMemoryEstimateAgainstTheBudget() {
        assertTrue("[debug.gpuMemory estimate=123 budget=160]" in lines(base()))
    }

    @Test
    fun putsACappedPixelRatioOnTheMemoryLine() {
        val out = lines(base().with { ratioCap = PixelRatioCap(from = 2.0, to = 1.25) })
        assertTrue("[debug.gpuMemoryCapped estimate=123 budget=160 from=2 to=1.25]" in out)
        assertFalse(out.any { it.startsWith("[debug.gpuMemory ") })
    }

    @Test
    fun listsACappedShadowMapAndReducedSceneryOnTheirOwnLines() {
        val out =
            lines(
                base().with {
                    shadowCap = ShadowMapCap(from = 2048, to = 1024)
                    sceneryCapped = true
                },
            )
        assertTrue("[debug.capShadow from=2048 to=1024]" in out)
        assertTrue("[debug.capScenery]" in out)
        assertFalse(lines(base()).any { it.contains("debug.cap") })
    }

    @Test
    fun saysWhetherAntialiasingIsOnAndWhenItWasDroppedForTheBudget() {
        assertTrue("[debug.antialias state=[debug.on]]" in lines(base()))
        assertTrue("[debug.antialias state=[debug.off]]" in lines(base().with { antialias = false }))
        assertTrue(
            "[debug.antialiasDropped]" in
                lines(
                    base().with {
                        antialias = false
                        antialiasDropped = true
                    },
                ),
        )
    }

    @Test
    fun leavesOutTheAutomaticFlagForAManualLevel() {
        assertEquals("[debug.level level=[graphics.medium]]", lines(base().with { auto = false })[1])
    }

    @Test
    fun saysSoWhenTheGpuNameIsNotKnown() {
        val unknown =
            base().with {
                gpu = ""
                budgetGpu = ""
            }
        assertEquals("[debug.gpu gpu=[debug.none]]", lines(unknown)[0])
    }

    @Test
    fun showsTheGpuTheBudgetWasBasedOnOnlyWhenItDiffersFromTheRenderer() {
        assertFalse(lines(base()).any { it.startsWith("[debug.gpuBudget") })
        assertFalse(lines(base().with { budgetGpu = "" }).any { it.startsWith("[debug.gpuBudget") })
        val out = lines(base().with { budgetGpu = "Intel(R) UHD Graphics" })
        assertEquals("[debug.gpu gpu=ANGLE (Mali-G52)]", out[0])
        assertEquals("[debug.gpuBudget gpu=Intel(R) UHD Graphics]", out[1])
    }

    @Test
    fun fallsBackToTheBudgetGpuWhenTheRendererNameIsNotAvailableLostDevice() {
        assertEquals("[debug.gpu gpu=ANGLE (Mali-G52)]", lines(base().with { gpu = "" })[0])
    }

    @Test
    fun countsContextLossesAndRestoresWithTheTimeSinceTheStart() {
        val out =
            lines(
                base().with {
                    contextLost = 2
                    contextRestored = 1
                    lostAtS = 31.04
                    restoredAtS = 12.3
                },
            )
        assertTrue(
            "[debug.context lost=2 restored=1 lostAt=[debug.atSeconds s=31] restoredAt=[debug.atSeconds s=12]]" in out,
        )
    }

    @Test
    fun showsADashInsteadOfATimeWhenNothingHappenedYet() {
        assertTrue("[debug.context lost=0 restored=0 lostAt=[debug.none] restoredAt=[debug.none]]" in lines(base()))
    }

    @Test
    fun showsPendingQualityStagesOnlyWhileThereAreSome() {
        assertFalse(lines(base()).any { it.contains("debug.stages") })
        assertTrue("[debug.stages count=3]" in lines(base().with { stagesPending = 3 }))
    }

    @Test
    fun saysThereAreNoErrorsOrListsThemNewestFirst() {
        assertEquals("[debug.noErrors]", lines(base()).last())
        val out = lines(base(), listOf(DebugError(5.2, "old"), DebugError(9.8, "new")))
        val at = out.indexOf("[debug.errors count=2]")
        assertTrue(at > 0)
        assertEquals(listOf("[debug.error s=10 message=new]", "[debug.error s=5 message=old]"), out.drop(at + 1))
    }

    @Test
    fun startsWithTheBuildVersionWhenOneIsGivenAndOmitsTheLineOtherwise() {
        val withVersion = formatDebugText(base(), emptyList(), t, null, "2026-10-05 · 3fdf19e").split("\n")
        assertEquals("[debug.version version=2026-10-05 · 3fdf19e]", withVersion[0])
        assertEquals("[debug.gpu gpu=ANGLE (Mali-G52)]", withVersion[1])
        assertFalse(lines(base()).any { it.startsWith("[debug.version") })
    }

    @Test
    fun buildsNoTextOfItsOwnEveryLineStartsWithATranslatedPiece() {
        for (line in lines(base(), listOf(DebugError(1.0, "x")))) assertTrue(line.startsWith("["), line)
    }

    @Test
    fun copesWithDefaultValues() {
        formatDebugText(EngineDiagnostics(), emptyList(), t)
    }

    @Test
    fun writesWholePixelRatiosWithoutADecimalPoint() {
        val out = lines(base().with { devicePixelRatio = 2.0 })
        assertEquals("[debug.pixels device=2 renderer=1.5]", out[2])
    }
}
