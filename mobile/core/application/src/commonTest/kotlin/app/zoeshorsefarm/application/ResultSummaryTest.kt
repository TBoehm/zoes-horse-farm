package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.KNOCKDOWN_FAULTS
import app.zoeshorsefarm.domain.course.REFUSAL_FAULTS
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.progress.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultSummaryTest {
    private fun params(awarded: List<String> = listOf("clean", "unknownBadge")) =
        FinishedParams(
            courseId = 1,
            result =
                RideResult(
                    courseId = 1,
                    timeCs = 4827,
                    faults = Faults(knockdowns = 2, refusals = 1, timeFaults = 3, total = 15),
                    stars = 1,
                    cleanOxer = false,
                    cleanCombination = false,
                ),
            isNewBest = true,
            unlockedCourse = 2,
            awarded = awarded,
        )

    private fun store(unlocked: Int) = FakeStore(progress = Progress(unlocked = unlocked))

    @Test
    fun turnsFaultCountsIntoPenaltyPoints() {
        val rows = summarizeResult(store(2), params()).rows
        assertEquals(FaultRow(count = 2, points = 2 * KNOCKDOWN_FAULTS, each = KNOCKDOWN_FAULTS), rows.knockdowns)
        assertEquals(FaultRow(count = 1, points = REFUSAL_FAULTS, each = REFUSAL_FAULTS), rows.refusals)
        assertEquals(3, rows.timeFaults)
        assertEquals(15, rows.total)
    }

    @Test
    fun passesThroughTimeStarsNewBestAndUnlockedCourse() {
        val summary = summarizeResult(store(2), params())
        assertEquals(1, summary.courseId)
        assertEquals(4827, summary.timeCs)
        assertEquals(1, summary.stars)
        assertTrue(summary.isNewBest)
        assertEquals(2, summary.unlockedCourse)
    }

    @Test
    fun describesTheAwardedBadgesAndSkipsUnknownIds() {
        val badges = summarizeResult(store(2), params()).badges
        assertEquals(listOf(ResultBadge(id = "clean", nameKey = "badge.clean.name")), badges)
    }

    @Test
    fun offersTheNextCourseOnlyWhenItIsOpen() {
        assertEquals(2, summarizeResult(store(2), params()).nextCourse)
        assertNull(summarizeResult(store(1), params()).nextCourse)
    }

    @Test
    fun hasNoBadgesWhenNothingWasAwarded() {
        assertEquals(emptyList(), summarizeResult(store(2), params(awarded = emptyList())).badges)
    }
}
