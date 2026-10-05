package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.badgeSummary
import app.zoeshorsefarm.application.listBadges
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel

// Overview of all badges (rule 50). Display only: the list comes from application/badge-overview.

private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T.*)?$""")

private val MONTHS_DE =
    listOf(
        "Januar",
        "Februar",
        "März",
        "April",
        "Mai",
        "Juni",
        "Juli",
        "August",
        "September",
        "Oktober",
        "November",
        "Dezember",
    )
private val MONTHS_EN =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

private val DAYS_IN_MONTH = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

@Suppress("MagicNumber") // Gregorian leap-year rule
private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

/** A calendar date: year, month 1..12 and day. */
private data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
)

/** The date part of an ISO timestamp, or null when it is no valid calendar date. */
private fun parseIsoDate(iso: String): CalendarDate? {
    val (year, month, day) =
        ISO_DATE
            .matchEntire(iso)
            ?.destructured
            ?.toList()
            ?.map { it.toInt() } ?: return null
    val days = DAYS_IN_MONTH.getOrNull(month - 1)?.plus(if (month == 2 && isLeap(year)) 1 else 0)
    return if (days != null && day in 1..days) CalendarDate(year, month, day) else null
}

/**
 * The calendar date of an ISO timestamp as long text: "5. März 2026" in German, "5 March 2026" in
 * English (web: `toLocaleDateString` with de-DE / en-GB). The date is the one of the UTC timestamp
 * (the web shows the date in the local time zone). An invalid date gives "".
 */
fun formatBadgeDate(
    iso: String,
    lang: Language,
): String {
    val date = parseIsoDate(iso) ?: return ""
    return if (lang == Language.DE) {
        "${date.day}. ${MONTHS_DE[date.month - 1]} ${date.year}"
    } else {
        "${date.day} ${MONTHS_EN[date.month - 1]} ${date.year}"
    }
}

/** One badge of the overview; [earned] says whether it has a date. */
class BadgeCard(
    val id: String,
    val name: String,
    val condition: String,
    val icon: String,
    val earned: Boolean,
    /** "Earned on {date}" or "Not earned yet". */
    val statusText: String,
)

class BadgesModel(
    private val ctx: AppContext,
) : ScreenModel {
    override val music = true

    val title: String get() = ctx.t("badges.title")
    val backLabel: String get() = ctx.t("common.back")

    val countText: String =
        badgeSummary(ctx.store).let { ctx.t("badges.count", mapOf("count" to it.earned, "total" to it.total)) }

    val cards: List<BadgeCard> =
        listBadges(ctx.store).map { badge ->
            val date = badge.earnedAt
            BadgeCard(
                id = badge.id,
                name = ctx.t(badge.nameKey),
                condition = ctx.t(badge.conditionKey),
                icon = badgeIcon(badge.id),
                earned = date != null,
                statusText =
                    if (date != null) {
                        ctx.t("badges.earned", mapOf("date" to formatBadgeDate(date, ctx.i18n.lang)))
                    } else {
                        ctx.t("badges.missing")
                    },
            )
        }

    fun back() = ctx.navigator.go(Route.Menu)
}
