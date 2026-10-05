package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashGuardTest {
    // Same rule as the 3D layer's levelAfterContextLoss for a loss in the foreground
    private val decide: (Boolean, GraphicsLevel) -> LevelDecision = { auto, level ->
        if (auto) {
            LevelDecision(GraphicsLevel.LOW, persist = level != GraphicsLevel.LOW, hint = false)
        } else {
            LevelDecision(level, persist = false, hint = level != GraphicsLevel.LOW)
        }
    }

    private class CountingStore(
        settings: Settings,
        crashGuard: CrashGuardState,
    ) : FakeStore(settings = settings, crashGuard = crashGuard) {
        var guardWrites = 0
        var onPlainUpdate: (() -> Unit)? = null

        override fun <T : Any> updateThrough(
            section: Section<T>,
            change: (T) -> T,
        ): T {
            if (section.name == CrashGuardSection.name) guardWrites += 1
            return super.updateThrough(section, change)
        }

        override fun <T : Any> update(
            section: Section<T>,
            change: (T) -> T,
        ): T {
            onPlainUpdate?.invoke()
            return super.update(section, change)
        }
    }

    private inner class Setup(
        saved: CrashGuardState,
        settings: Settings,
        tabId: String?,
    ) {
        val store = CountingStore(settings, saved)
        val clock = ManualClock(1_000_000)
        val guard =
            CrashGuard(
                store = store,
                settings = SettingsService(store),
                clock = clock,
                decide = decide,
                tabId = tabId,
            )
        val state get() = store.crashGuard
        val writes get() = store.guardWrites

        fun advance(ms: Long) = clock.advance(ms)

        /** Replaces the saved guard state, like another tab writing to the storage. */
        fun change(transform: (CrashGuardState) -> CrashGuardState) = store.set(CrashGuardSection, transform(state))
    }

    private fun setup(
        guard: CrashGuardState = CrashGuardState(),
        settings: Settings = Settings(graphicsAuto = true, graphicsLevel = GraphicsLevel.MEDIUM),
        tabId: String? = null,
    ) = Setup(guard, settings, tabId)

    private val mediumAuto = RenderInfo(GraphicsLevel.MEDIUM, auto = true)
    private val graceMs = (FOREGROUND_GRACE_S * 1000).toLong()

    // marking

    @Test
    fun marksRenderingWithLevelAutoFlagAndStartTime() {
        val s = setup()
        s.guard.markRendering(mediumAuto)
        assertTrue(s.state.rendering)
        assertEquals(GraphicsLevel.MEDIUM, s.state.level)
        assertTrue(s.state.auto)
        assertEquals(1_000_000, s.state.since)
        assertEquals(1_000_000, s.state.lastSeen)
    }

    @Test
    fun marksIdleWhenTheLeaseIsReleased() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        lease.release()
        assertFalse(s.state.rendering)
    }

    @Test
    fun staysMarkedWhileAnotherLeaseIsHeldAScreenIsRebuiltNewOneFirst() {
        val s = setup()
        val first = s.guard.markRendering(mediumAuto)
        val second = s.guard.markRendering(mediumAuto)
        first.release()
        assertTrue(s.state.rendering)
        second.release()
        assertFalse(s.state.rendering)
    }

    @Test
    fun releasingALeaseTwiceDoesNothing() {
        val s = setup()
        val first = s.guard.markRendering(mediumAuto)
        val second = s.guard.markRendering(mediumAuto)
        first.release()
        first.release()
        assertTrue(s.state.rendering)
        second.release()
    }

    @Test
    fun doesNotWriteAgainWhenNothingChanged() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        val before = s.writes
        repeat(100) { lease.frame(mediumAuto) }
        assertEquals(before, s.writes)
    }

    @Test
    fun writesALevelChangeRightAwayButKeepsTheStartTime() {
        val s = setup()
        val lease = s.guard.markRendering(RenderInfo(GraphicsLevel.HIGH, auto = true))
        s.advance(2000)
        lease.frame(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        assertEquals(GraphicsLevel.MEDIUM, s.state.level)
        assertTrue(s.state.auto)
        assertEquals(1_000_000, s.state.since)
    }

    @Test
    fun updatesTheHeartbeatAtMostOncePerInterval() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        val before = s.writes
        s.advance(HEARTBEAT_INTERVAL_MS - 1)
        lease.frame(mediumAuto)
        assertEquals(before, s.writes)
        s.advance(1)
        lease.frame(mediumAuto)
        assertEquals(before + 1, s.writes)
        assertEquals(1_000_000 + HEARTBEAT_INTERVAL_MS, s.state.lastSeen)
    }

    @Test
    fun aFrameAfterTheLeaseWasReleasedDoesNotMarkAnything() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        lease.release()
        lease.frame(mediumAuto)
        assertFalse(s.state.rendering)
    }

    // background

    @Test
    fun goesCleanInTheBackgroundAndMarksAgainWhenThePageIsBackAfterTheGrace() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        s.guard.markBackground()
        assertFalse(s.state.rendering)
        s.advance(60_000)
        s.guard.resume()
        s.advance(graceMs)
        lease.frame(mediumAuto)
        assertTrue(s.state.rendering)
        assertEquals(GraphicsLevel.MEDIUM, s.state.level)
        assertEquals(1_060_000 + graceMs, s.state.since)
    }

    @Test
    fun doesNotMarkAgainRightAfterThePageIsBackOnlyOnceTheGraceTimeHasPassed() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        s.guard.markBackground()
        s.advance(60_000)
        s.guard.resume()
        assertFalse(s.state.rendering)
        s.advance(graceMs - 1)
        lease.frame(mediumAuto)
        assertFalse(s.state.rendering)
        s.advance(1)
        lease.frame(mediumAuto)
        assertTrue(s.state.rendering)
    }

    @Test
    fun aSecondResumeDoesNotRestartTheGraceTime() {
        // visibilitychange and pageshow both call resume
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        s.guard.markBackground()
        s.guard.resume()
        s.advance(graceMs - 1)
        s.guard.resume()
        s.advance(1)
        lease.frame(mediumAuto)
        assertTrue(s.state.rendering)
    }

    @Test
    fun goingToTheBackgroundDuringTheGraceTimeKeepsTheMarkClean() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        s.guard.markBackground()
        s.guard.resume()
        s.advance(graceMs - 1)
        s.guard.markBackground()
        s.advance(10_000)
        lease.frame(mediumAuto)
        assertFalse(s.state.rendering)
    }

    @Test
    fun theFirstPageStartHasNoGraceAScreenIsMarkedRightAway() {
        val s = setup()
        s.guard.markRendering(mediumAuto)
        assertTrue(s.state.rendering)
    }

    @Test
    fun resumeWithoutARenderingScreenStaysClean() {
        val s = setup()
        s.guard.markBackground()
        s.guard.resume()
        assertFalse(s.state.rendering)
    }

    @Test
    fun aFrameInTheBackgroundDoesNotMarkRendering() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        s.guard.markBackground()
        lease.frame(mediumAuto)
        assertFalse(s.state.rendering)
    }

    @Test
    fun aScreenOpenedInTheBackgroundIsMarkedWhenThePageIsBackAfterTheGrace() {
        val s = setup()
        s.guard.markBackground()
        val lease = s.guard.markRendering(mediumAuto)
        assertFalse(s.state.rendering)
        s.guard.resume()
        assertFalse(s.state.rendering)
        s.advance(graceMs)
        lease.frame(mediumAuto)
        assertTrue(s.state.rendering)
    }

    // check of the previous run

    private fun crashed(
        level: GraphicsLevel = GraphicsLevel.MEDIUM,
        auto: Boolean = true,
        since: Long = 100_000,
        lastSeen: Long = 107_400,
        tabId: String? = null,
    ) = CrashGuardState(rendering = true, level = level, auto = auto, since = since, lastSeen = lastSeen, tabId = tabId)

    @Test
    fun aCleanStateIsNoCrashAndChangesNothing() {
        val s = setup()
        assertEquals(PreviousRun.Clean, s.guard.checkPreviousRun())
        assertEquals(GraphicsLevel.MEDIUM, s.store.settings.graphicsLevel)
        assertEquals(0, s.writes)
    }

    @Test
    fun reportsLevelModeAndSecondsIntoTheSessionOfACrash() {
        val s = setup(guard = crashed())
        assertEquals(PreviousRun.Crashed(GraphicsLevel.MEDIUM, auto = true, seconds = 7), s.guard.checkPreviousRun())
    }

    @Test
    fun automaticSavesLowAsTheAutoLevelAndKeepsAutomaticOn() {
        val s = setup(guard = crashed(level = GraphicsLevel.HIGH))
        s.guard.checkPreviousRun()
        assertTrue(s.store.settings.graphicsAuto)
        assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
    }

    @Test
    fun automaticAtLowHasNothingToSave() {
        val s =
            setup(
                guard = crashed(level = GraphicsLevel.LOW),
                settings = Settings(graphicsLevel = GraphicsLevel.LOW),
            )
        s.guard.checkPreviousRun()
        assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
        assertFalse(s.store.crashGuard.hintPending)
    }

    @Test
    fun manualAboveLowKeepsTheLevelAndFlagsAHintForTheNextRide() {
        val s =
            setup(
                guard = crashed(auto = false, level = GraphicsLevel.HIGH),
                settings = Settings(graphicsAuto = false, graphicsLevel = GraphicsLevel.HIGH),
            )
        s.guard.checkPreviousRun()
        assertFalse(s.store.settings.graphicsAuto)
        assertEquals(GraphicsLevel.HIGH, s.store.settings.graphicsLevel)
        assertTrue(s.store.crashGuard.hintPending)
    }

    @Test
    fun manualLowNothingHappens() {
        val s =
            setup(
                guard = crashed(auto = false, level = GraphicsLevel.LOW),
                settings = Settings(graphicsAuto = false, graphicsLevel = GraphicsLevel.LOW),
            )
        assertTrue((s.guard.checkPreviousRun() as PreviousRun.Crashed).level == GraphicsLevel.LOW)
        assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
        assertFalse(s.store.crashGuard.hintPending)
    }

    @Test
    fun clearsTheRenderingMarkAndRemembersTheCrashForTheDebugDisplay() {
        val s = setup(guard = crashed())
        s.guard.checkPreviousRun()
        assertFalse(s.state.rendering)
        assertEquals(
            LastCrash(GraphicsLevel.MEDIUM, auto = true, seconds = 7, at = "1970-01-01T00:16:40.000Z"),
            s.state.lastCrash,
        )
        // a second check (e.g. after the next clean exit) finds nothing
        assertEquals(PreviousRun.Clean, s.guard.checkPreviousRun())
    }

    @Test
    fun aCleanRunKeepsTheLastCrashOfTheDebugDisplay() {
        val lastCrash = LastCrash(GraphicsLevel.HIGH, auto = true, seconds = 5, at = "2026-01-01T00:00:00.000Z")
        val s = setup(guard = CrashGuardState(lastCrash = lastCrash))
        s.guard.checkPreviousRun()
        assertEquals(lastCrash, s.state.lastCrash)
    }

    // whose mark it is (tab id)

    private val freshMs = HEARTBEAT_INTERVAL_MS * 2 - 1

    private fun mark(
        tabId: String? = "tab-a",
        lastSeen: Long = 1_000_000,
    ) = crashed(since = 940_000, lastSeen = lastSeen, tabId = tabId)

    @Test
    fun theMarkOfThisVeryTabIsACrashEvenWithAFreshHeartbeat() {
        // the tab came back after the browser killed it
        val s = setup(guard = mark(), tabId = "tab-a")
        val result = s.guard.checkPreviousRun() as PreviousRun.Crashed
        assertEquals(GraphicsLevel.MEDIUM, result.level)
        assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
        assertFalse(s.store.crashGuard.rendering)
    }

    @Test
    fun theMarkOfAnotherTabWithAFreshHeartbeatIsALiveTabNoCrashUntouched() {
        val s = setup(guard = mark(), tabId = "tab-b")
        s.advance(freshMs)
        assertEquals(PreviousRun.Clean, s.guard.checkPreviousRun())
        assertTrue(s.state.rendering)
        assertEquals(GraphicsLevel.MEDIUM, s.state.level)
        assertFalse(s.state.hintPending)
        assertEquals(emptyList(), s.state.blockedLevels)
        assertEquals(GraphicsLevel.MEDIUM, s.store.settings.graphicsLevel)
        assertEquals(0, s.writes)
    }

    @Test
    fun aTabThatDrawsNothingNeverClearsTheMarkOfALiveOtherTab() {
        val s = setup(guard = mark(), tabId = "tab-b")
        s.advance(freshMs)
        s.guard.checkPreviousRun()
        s.guard.markBackground()
        s.guard.resume()
        assertTrue(s.state.rendering)
        assertEquals("tab-a", s.state.tabId)
    }

    @Test
    fun aMarkWithoutATabIdFromAnOlderSaveWithAFreshHeartbeatCountsAsAnotherTab() {
        val s = setup(guard = mark(tabId = null), tabId = "tab-b")
        assertEquals(PreviousRun.Clean, s.guard.checkPreviousRun())
    }

    @Test
    fun theMarkOfAnotherTabWithAnOldHeartbeatIsACrashTheBrowserWasKilled() {
        val s = setup(guard = mark(), tabId = "tab-b")
        s.advance(HEARTBEAT_INTERVAL_MS * 2)
        assertEquals(
            PreviousRun.Crashed(GraphicsLevel.MEDIUM, auto = true, seconds = 60),
            s.guard.checkPreviousRun(),
        )
    }

    @Test
    fun withoutATabIdOfItsOwnTheOwnerIsUnknownAMarkIsACrash() {
        val s = setup(guard = mark(), tabId = null)
        assertTrue(s.guard.checkPreviousRun() is PreviousRun.Crashed)
    }

    @Test
    fun aHeartbeatInTheFutureTheClockWasSetBackDoesNotHideACrash() {
        val s = setup(guard = mark(lastSeen = 2_000_000), tabId = "tab-b")
        assertTrue(s.guard.checkPreviousRun() is PreviousRun.Crashed)
    }

    @Test
    fun theMarksWrittenWhileDrawingCarryTheTabIdStartAndHeartbeat() {
        val s = setup(tabId = "tab-a")
        val lease = s.guard.markRendering(mediumAuto)
        assertEquals("tab-a", s.state.tabId)
        s.change { it.copy(tabId = null) }
        s.advance(HEARTBEAT_INTERVAL_MS)
        lease.frame(mediumAuto)
        assertEquals("tab-a", s.state.tabId)
    }

    @Test
    fun aMissingHeartbeatGivesZeroSeconds() {
        val s = setup(guard = crashed(lastSeen = 0))
        assertEquals(0, (s.guard.checkPreviousRun() as PreviousRun.Crashed).seconds)
    }

    // hint

    @Test
    fun takeHintReturnsTheFlagOnce() {
        val s = setup(guard = CrashGuardState(hintPending = true))
        assertTrue(s.guard.takeHint())
        assertFalse(s.guard.takeHint())
    }

    @Test
    fun takeHintIsFalseWithoutAFlag() {
        assertFalse(setup().guard.takeHint())
    }

    @Test
    fun lastCrashReturnsTheRememberedCrashForTheDebugDisplay() {
        val lastCrash = LastCrash(GraphicsLevel.HIGH, auto = true, seconds = 5, at = "2026-01-01T00:00:00.000Z")
        assertEquals(lastCrash, setup(guard = CrashGuardState(lastCrash = lastCrash)).guard.lastCrash())
        assertNull(setup().guard.lastCrash())
    }

    // the section

    private val section = CrashGuardSection

    @Test
    fun anOldSaveWithoutTheSectionMeansNoCrash() {
        val data = section.sanitize(null)
        assertFalse(data.rendering)
        assertFalse(data.hintPending)
        assertNull(data.lastCrash)
    }

    @Test
    fun sanitizesInvalidFieldsFieldByField() {
        val data =
            section.sanitize(
                mapOf(
                    "rendering" to "yes",
                    "level" to "ultra",
                    "auto" to 1,
                    "since" to -5,
                    "lastSeen" to "x",
                    "lastCrash" to 7,
                ),
            )
        assertEquals(CrashGuardState(), data)
    }

    @Test
    fun keepsAValidTabIdAndDropsAnInvalidOne() {
        assertEquals("tab-a", section.sanitize(mapOf("tabId" to "tab-a")).tabId)
        assertNull(section.sanitize(mapOf("tabId" to 7)).tabId)
        assertNull(section.sanitize(mapOf("tabId" to "x".repeat(65))).tabId)
        assertNull(section.sanitize(emptyMap<String, Any?>()).tabId)
    }

    @Test
    fun dropsAMalformedLastCrashButKeepsAValidOne() {
        val valid = mapOf("level" to "low", "auto" to false, "seconds" to 3, "at" to "2026-01-01T00:00:00.000Z")
        assertEquals(
            LastCrash(GraphicsLevel.LOW, auto = false, seconds = 3, at = "2026-01-01T00:00:00.000Z"),
            section.sanitize(mapOf("lastCrash" to valid)).lastCrash,
        )
        assertNull(section.sanitize(mapOf("lastCrash" to valid + ("level" to "x"))).lastCrash)
        assertNull(section.sanitize(mapOf("lastCrash" to valid + ("seconds" to -1))).lastCrash)
    }

    @Test
    fun theTreeReadsBackToTheSameState() {
        val state =
            CrashGuardState(
                rendering = true,
                level = GraphicsLevel.HIGH,
                auto = false,
                since = 5,
                lastSeen = 9,
                tabId = "t",
                hintPending = true,
                blockedLevels = listOf(GraphicsLevel.LOW, GraphicsLevel.HIGH),
                lastCrash = LastCrash(GraphicsLevel.LOW, auto = true, seconds = 2, at = "x", extra = mapOf("f" to 1)),
                unknown = mapOf("future" to listOf(1)),
            )
        assertEquals(state, section.sanitize(section.toTree(state)))
    }

    // blocked levels (per device)

    private val blockedCrash =
        crashed()

    @Test
    fun addBlockedLevelAddsALevelOnceOrderedLowToHigh() {
        assertEquals(listOf(GraphicsLevel.MEDIUM), addBlockedLevel(emptyList(), GraphicsLevel.MEDIUM))
        assertEquals(
            listOf(GraphicsLevel.LOW, GraphicsLevel.HIGH),
            addBlockedLevel(listOf(GraphicsLevel.HIGH), GraphicsLevel.LOW),
        )
        assertEquals(
            listOf(GraphicsLevel.MEDIUM),
            addBlockedLevel(listOf(GraphicsLevel.MEDIUM), GraphicsLevel.MEDIUM),
        )
    }

    @Test
    fun addBlockedLevelIgnoresNullAndDoesNotChangeItsInput() {
        val blocked = mutableListOf(GraphicsLevel.LOW)
        assertEquals(listOf(GraphicsLevel.LOW), addBlockedLevel(blocked, null))
        addBlockedLevel(blocked, GraphicsLevel.HIGH)
        assertEquals(listOf(GraphicsLevel.LOW), blocked)
    }

    @Test
    fun aDetectedCrashBlocksTheCrashedLevelAutomaticOrManual() {
        val auto = setup(guard = blockedCrash)
        auto.guard.checkPreviousRun()
        assertEquals(listOf(GraphicsLevel.MEDIUM), auto.guard.blockedLevels())
        val manual =
            setup(
                guard =
                    blockedCrash.copy(
                        level = GraphicsLevel.HIGH,
                        auto = false,
                        blockedLevels = listOf(GraphicsLevel.MEDIUM),
                    ),
            )
        manual.guard.checkPreviousRun()
        assertEquals(listOf(GraphicsLevel.MEDIUM, GraphicsLevel.HIGH), manual.guard.blockedLevels())
    }

    @Test
    fun aCleanStartBlocksNothing() {
        val s = setup()
        s.guard.checkPreviousRun()
        assertEquals(emptyList(), s.guard.blockedLevels())
    }

    @Test
    fun blockLevelAddsTheLevelOfARegularContextLoss() {
        val s = setup()
        s.guard.blockLevel(GraphicsLevel.MEDIUM)
        s.guard.blockLevel(GraphicsLevel.MEDIUM)
        assertEquals(listOf(GraphicsLevel.MEDIUM), s.state.blockedLevels)
    }

    @Test
    fun clearBlockedLevelsEmptiesTheSetThePlayerPickedAutomaticAgain() {
        val s = setup(guard = CrashGuardState(blockedLevels = listOf(GraphicsLevel.MEDIUM, GraphicsLevel.HIGH)))
        s.guard.clearBlockedLevels()
        assertEquals(emptyList(), s.guard.blockedLevels())
    }

    @Test
    fun theSectionSanitizesTheSetValidLevelsOnlyNoDuplicates() {
        assertEquals(emptyList(), section.sanitize(emptyMap<String, Any?>()).blockedLevels)
        assertEquals(
            listOf(GraphicsLevel.HIGH, GraphicsLevel.LOW),
            section.sanitize(mapOf("blockedLevels" to listOf("high", "low"))).blockedLevels,
        )
        assertEquals(emptyList(), section.sanitize(mapOf("blockedLevels" to listOf("low", "low"))).blockedLevels)
        assertEquals(emptyList(), section.sanitize(mapOf("blockedLevels" to listOf("x"))).blockedLevels)
        assertEquals(emptyList(), section.sanitize(mapOf("blockedLevels" to "low")).blockedLevels)
    }

    // several tabs

    @Test
    fun writesOnlyItsOwnSectionThroughNeverPlainUpdateWhichSavesEverything() {
        val s = setup(guard = CrashGuardState(hintPending = true))
        s.store.onPlainUpdate = { throw AssertionError("plain update must not be used for guard writes") }
        val lease = s.guard.markRendering(mediumAuto)
        lease.release()
        s.guard.takeHint()
        s.guard.blockLevel(GraphicsLevel.HIGH)
        s.guard.clearBlockedLevels()
        assertEquals(emptyList(), s.store.crashGuard.blockedLevels)
    }

    @Test
    fun theLevelAfterACrashIsWrittenByTheSettingsServiceOnly() {
        // the guard section itself is written through; the one plain update is the settings write
        val s = setup(guard = crashed(level = GraphicsLevel.HIGH))
        var plainUpdates = 0
        s.store.onPlainUpdate = { plainUpdates += 1 }
        s.guard.checkPreviousRun()
        assertEquals(1, plainUpdates)
    }

    @Test
    fun theHeartbeatMarksRenderingAgainWhenAnotherTabClearedTheMark() {
        val s = setup()
        val lease = s.guard.markRendering(mediumAuto)
        // another tab went to the background and wrote "clean"
        s.change { it.copy(rendering = false) }
        s.advance(HEARTBEAT_INTERVAL_MS)
        lease.frame(mediumAuto)
        assertTrue(s.state.rendering)
    }

    @Test
    fun aPlainUpdateThatThrowsReallyBreaksTheTestSetup() {
        // guards the previous test: the hook is wired to the store
        val s = setup()
        s.store.onPlainUpdate = { throw AssertionError("boom") }
        assertFailsWith<AssertionError> { SettingsService(s.store).setCamera(CameraMode.RIDER) }
    }
}
