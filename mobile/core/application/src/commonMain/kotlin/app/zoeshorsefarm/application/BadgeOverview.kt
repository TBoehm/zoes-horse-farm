package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.progress.BADGES

// Badge overview (rule 50): all badges merged with the earned dates from the progress.

/** The text keys of one badge (toasts, results). */
data class BadgeKeys(
    val id: String,
    val nameKey: String,
    val conditionKey: String,
)

/** A badge with the ISO date it was earned ([earnedAt] null = not yet). */
data class BadgeEntry(
    val id: String,
    val nameKey: String,
    val conditionKey: String,
    val earnedAt: String?,
)

data class BadgeSummary(
    val earned: Int,
    val total: Int,
)

fun listBadges(store: Store): List<BadgeEntry> {
    val earned = store.get(ProgressSection).badges
    return BADGES.map { BadgeEntry(it.id, it.nameKey, it.conditionKey, earned[it.id]) }
}

fun badgeSummary(store: Store): BadgeSummary {
    val list = listBadges(store)
    return BadgeSummary(earned = list.count { it.earnedAt != null }, total = list.size)
}

/** Text keys of one badge (toasts, results), or null for an unknown id. */
fun describeBadge(id: String): BadgeKeys? =
    BADGES.firstOrNull { it.id == id }?.let { BadgeKeys(it.id, it.nameKey, it.conditionKey) }
