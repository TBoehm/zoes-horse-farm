package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.domain.progress.BADGES
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.LocalTimeZone
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.UtcTimeZone
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
    private val i18n = I18n()

    private fun format(
        iso: String,
        lang: Language,
        zone: LocalTimeZone = UtcTimeZone,
    ): String {
        i18n.setLang(lang)
        return formatBadgeDate(iso, i18n, zone)
    }

    @Test
    fun formatsInGermanWithADotAfterTheDay() {
        assertEquals("1. Januar 2026", format("2026-01-01T00:00:00.000Z", Language.DE))
        assertEquals("31. Dezember 2025", format("2025-12-31T23:59:59.999Z", Language.DE))
    }

    @Test
    fun formatsInBritishEnglishWithTheMonthName() {
        assertEquals("1 January 2026", format("2026-01-01T00:00:00.000Z", Language.EN))
        assertEquals("29 February 2024", format("2024-02-29T12:00:00.000Z", Language.EN))
    }

    @Test
    fun nameAllTwelveMonths() {
        val names =
            (1..12).map { m -> format("2026-${m.toString().padStart(2, '0')}-10T00:00:00.000Z", Language.EN) }
        assertEquals(12, names.toSet().size)
        assertEquals("10 September 2026", names[8])
    }

    @Test
    fun anInvalidDateGivesAnEmptyText() {
        assertEquals("", format("not a date", Language.EN))
        assertEquals("", format("", Language.DE))
        assertEquals("", format("2026-13-01T00:00:00.000Z", Language.EN))
        assertEquals("", format("2026-02-30T00:00:00.000Z", Language.EN))
        assertEquals("", format("2025-02-29T00:00:00.000Z", Language.EN))
        assertEquals("", format("2026-01-01T24:00:00.000Z", Language.EN))
    }

    @Test
    fun aDateWithoutATimeIsTheDayItself() {
        assertEquals("5 March 2026", format("2026-03-05", Language.EN))
    }

    @Test
    fun theLocalDayIsShownNotTheUtcDay() {
        val berlinWinter = LocalTimeZone { 60 }
        // 23:30 UTC on 4 March is 00:30 on 5 March in CET
        assertEquals("4 March 2026", format("2026-03-04T23:30:00.000Z", Language.EN))
        assertEquals("5 March 2026", format("2026-03-04T23:30:00.000Z", Language.EN, berlinWinter))
        // west of Greenwich the day can go back: 00:30 UTC on 5 March is 19:30 on 4 March in New York
        val newYork = LocalTimeZone { -300 }
        assertEquals("4 March 2026", format("2026-03-05T00:30:00.000Z", Language.EN, newYork))
    }

    @Test
    fun theOffsetIsAskedForTheMomentOfTheBadgeSoDaylightSavingTimeCounts() {
        val asked = mutableListOf<Long>()
        val zone =
            LocalTimeZone {
                asked += it
                120
            }
        format("2026-07-01T00:00:00.000Z", Language.EN, zone)
        assertEquals(listOf(1_782_864_000_000L), asked)
    }

    @Test
    fun aTimestampWithItsOwnZoneIsNormalisedToTheInstantFirst() {
        assertEquals("4 March 2026", format("2026-03-05T00:30:00+02:00", Language.EN))
        assertEquals("5 March 2026", format("2026-03-04T23:30:00-01:00", Language.EN))
    }

    @Test
    fun theBadgeCardsUseTheZoneOfTheContext() {
        val app = TestApp()
        app.store.update(ProgressSection) { it.copy(badges = mapOf("firstJump" to "2026-03-04T23:30:00.000Z")) }
        val zoned =
            AppContext(
                store = app.store,
                settings = app.settings,
                inputMode = app.inputMode,
                clock = app.clock,
                i18n = app.i18n,
                navigator = app.navigator,
                scheduler = app.scheduler.ui,
                lifecycle = app.lifecycle,
                badgeToasts = app.badgeToasts,
                version = "x",
                timeZone = LocalTimeZone { 60 },
            )
        assertEquals("Earned on 5 March 2026", BadgesModel(zoned).cards[0].statusText)
    }
}
