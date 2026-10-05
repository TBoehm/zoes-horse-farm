package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.domain.progress.BADGES
import app.zoeshorsefarm.domain.progress.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BadgeOverviewTest {
    private val date = "2026-01-02T03:04:05.000Z"

    // listBadges

    @Test
    fun listsEveryBadgeInTheOrderOfRule49WithTextKeys() {
        val list = listBadges(FakeStore())
        assertEquals(BADGES.map { it.id }, list.map { it.id })
        assertEquals(
            BadgeEntry(
                id = "firstJump",
                nameKey = "badge.firstJump.name",
                conditionKey = "badge.firstJump.condition",
                earnedAt = null,
            ),
            list[0],
        )
    }

    @Test
    fun mergesTheEarnedDateFromTheProgress() {
        val list = listBadges(FakeStore(progress = Progress(badges = mapOf("clean" to date))))
        assertEquals(date, list.first { it.id == "clean" }.earnedAt)
        assertNull(list.first { it.id == "firstJump" }.earnedAt)
    }

    // badgeSummary

    @Test
    fun countsEarnedAndTotalBadges() {
        val store = FakeStore(progress = Progress(badges = mapOf("clean" to date, "firstJump" to date)))
        assertEquals(BadgeSummary(earned = 2, total = BADGES.size), badgeSummary(store))
    }

    // describeBadge

    @Test
    fun returnsTheTextKeysOfOneBadgeNullForUnknownIds() {
        assertEquals(
            BadgeKeys(id = "clean", nameKey = "badge.clean.name", conditionKey = "badge.clean.condition"),
            describeBadge("clean"),
        )
        assertNull(describeBadge("nope"))
    }
}
