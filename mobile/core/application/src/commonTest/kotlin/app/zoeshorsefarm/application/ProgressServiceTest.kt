package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.domain.progress.Progress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressServiceTest {
    private val clock = FixedClock()

    private fun clean(
        courseId: Int,
        timeCs: Int = 5000,
    ) = RideResult(
        courseId = courseId,
        timeCs = timeCs,
        faults = Faults(knockdowns = 0, refusals = 0, timeFaults = 0, total = 0),
        stars = 3,
        cleanOxer = false,
        cleanCombination = false,
    )

    // recordJump

    @Test
    fun recordJumpCountsTheJumpSavesItAtOnceAndAwardsFirstJump() {
        val store = FakeStore()
        assertEquals(listOf("firstJump"), recordJump(store, clock))
        assertEquals(1, store.progress.jumps)
        assertEquals(FIXED_ISO, store.progress.badges["firstJump"])
    }

    @Test
    fun recordJumpAwardsEachBadgeOnlyOnce() {
        val store = FakeStore()
        recordJump(store, clock)
        assertEquals(emptyList(), recordJump(store, clock))
        assertEquals(2, store.progress.jumps)
    }

    @Test
    fun recordJumpAwardsTheJumpMouseWithThe100thJump() {
        val store = FakeStore(progress = Progress(jumps = 99, badges = mapOf("firstJump" to FIXED_ISO)))
        assertEquals(listOf("jumpMouse"), recordJump(store, clock))
    }

    // finishRide

    @Test
    fun finishRideSavesTheResultReportsANewBestAndUnlocksTheNextCourse() {
        val store = FakeStore()
        val out = finishRide(store, clock, clean(1))
        assertTrue(out.isNewBest)
        assertEquals(2, out.unlockedCourse)
        assertEquals(2, store.progress.unlocked)
        assertEquals(1, store.progress.finishedRides)
        assertEquals(CourseBest(faults = 0, timeCs = 5000, stars = 3), store.progress.courses["1"])
    }

    @Test
    fun finishRideAwardsEndOfRideBadgesWithTheClockTime() {
        val store = FakeStore()
        val out = finishRide(store, clock, clean(1))
        assertContains(out.awarded, "clean")
        for (id in out.awarded) assertEquals(FIXED_ISO, store.progress.badges[id])
    }

    @Test
    fun finishRideKeepsTheBetterOldResultAndReportsNoNewBest() {
        val store =
            FakeStore(
                progress =
                    Progress(
                        unlocked = 2,
                        courses =
                            mapOf("1" to CourseBest(faults = 0, timeCs = 4000, stars = 3)),
                    ),
            )
        val out = finishRide(store, clock, clean(1, 4500))
        assertFalse(out.isNewBest)
        assertNull(out.unlockedCourse)
        assertEquals(4000, store.progress.courses["1"]?.timeCs)
    }

    // resetProgress

    @Test
    fun resetProgressDeletesOnlyTheProgressFieldsAndLeavesOtherSectionsAlone() {
        // rule 48
        val store =
            FakeStore(
                horse = HorseProfile(name = "Blitz", nameAnswered = true),
                progress =
                    Progress(
                        unlocked = 3,
                        jumps = 12,
                        finishedRides = 4,
                        badges = mapOf("firstJump" to FIXED_ISO),
                    ),
            )
        resetProgress(store)
        assertEquals(Progress(), store.progress)
        assertEquals("Blitz", store.horse.name)
    }
}
