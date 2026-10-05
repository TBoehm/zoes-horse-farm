package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.domain.progress.BADGES
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BadgesTest {
    private val app = TestApp()

    @Test
    fun listsEveryBadgeInOrderWithNameConditionAndEmblem() {
        val model = BadgesModel(app.ctx)
        assertEquals(BADGES.map { it.id }, model.cards.map { it.id })
        assertEquals("First Jump", model.cards[0].name)
        assertEquals("Jump over an obstacle for the first time.", model.cards[0].condition)
        assertTrue(model.cards.all { it.condition.isNotEmpty() && it.icon.isNotEmpty() })
        assertTrue(model.music)
    }

    @Test
    fun anUnearnedBadgeSaysSoAnAnEarnedOneShowsItsDate() {
        app.store.update(ProgressSection) { it.copy(badges = mapOf("firstJump" to "2026-03-05T10:00:00.000Z")) }
        val cards = BadgesModel(app.ctx).cards
        assertTrue(cards[0].earned)
        assertEquals("Earned on 5 March 2026", cards[0].statusText)
        assertFalse(cards[1].earned)
        assertEquals("Not earned yet", cards[1].statusText)
    }

    @Test
    fun theDateFollowsTheLanguage() {
        app.i18n.setLang(Language.DE)
        app.store.update(ProgressSection) { it.copy(badges = mapOf("firstJump" to "2026-03-05T10:00:00.000Z")) }
        assertEquals("Erhalten am 5. März 2026", BadgesModel(app.ctx).cards[0].statusText)
    }

    @Test
    fun countsTheEarnedBadges() {
        assertEquals("0 of ${BADGES.size}", BadgesModel(app.ctx).countText)
        app.store.update(ProgressSection) {
            it.copy(badges = mapOf("firstJump" to "2026-03-05T10:00:00.000Z", "busy" to "2026-03-06T10:00:00.000Z"))
        }
        assertEquals("2 of ${BADGES.size}", BadgesModel(app.ctx).countText)
    }

    @Test
    fun backGoesToTheMenu() {
        app.registerStubs("menu")
        BadgesModel(app.ctx).back()
        assertEquals(Route.Menu, app.navigator.current)
    }

    @Test
    fun everyBadgeHasItsOwnEmblemAndUnknownOnesGetATrophy() {
        val icons = BADGES.map { badgeIcon(it.id) }
        assertEquals(icons.size, icons.toSet().size)
        assertEquals(DEFAULT_BADGE_ICON, badgeIcon("somethingNew"))
        assertTrue(icons.none { it == DEFAULT_BADGE_ICON })
    }
}

class BadgeDateTest {
    @Test
    fun formatsInGermanWithADotAfterTheDay() {
        assertEquals("1. Januar 2026", formatBadgeDate("2026-01-01T00:00:00.000Z", Language.DE))
        assertEquals("31. Dezember 2025", formatBadgeDate("2025-12-31T23:59:59.999Z", Language.DE))
    }

    @Test
    fun formatsInBritishEnglishWithTheMonthName() {
        assertEquals("1 January 2026", formatBadgeDate("2026-01-01T00:00:00.000Z", Language.EN))
        assertEquals("29 February 2024", formatBadgeDate("2024-02-29T12:00:00.000Z", Language.EN))
    }

    @Test
    fun nameAllTwelveMonths() {
        val names =
            (1..12).map { m -> formatBadgeDate("2026-${m.toString().padStart(2, '0')}-10T00:00:00.000Z", Language.EN) }
        assertEquals(12, names.toSet().size)
        assertEquals("10 September 2026", names[8])
    }

    @Test
    fun anInvalidDateGivesAnEmptyText() {
        assertEquals("", formatBadgeDate("not a date", Language.EN))
        assertEquals("", formatBadgeDate("", Language.DE))
        assertEquals("", formatBadgeDate("2026-13-01T00:00:00.000Z", Language.EN))
        assertEquals("", formatBadgeDate("2026-02-30T00:00:00.000Z", Language.EN))
        assertEquals("", formatBadgeDate("2025-02-29T00:00:00.000Z", Language.EN))
    }
}
