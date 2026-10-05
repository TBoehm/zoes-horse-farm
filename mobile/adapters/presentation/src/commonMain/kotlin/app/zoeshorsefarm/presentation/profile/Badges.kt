package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.badgeSummary
import app.zoeshorsefarm.application.listBadges
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel

// Overview of all badges (rule 50). Display only: the list comes from application/badge-overview.

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
                        ctx.t("badges.earned", mapOf("date" to formatBadgeDate(date, ctx.i18n, ctx.timeZone)))
                    } else {
                        ctx.t("badges.missing")
                    },
            )
        }

    fun back() = ctx.navigator.go(Route.Menu)
}
