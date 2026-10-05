package app.zoeshorsefarm.domain.progress

import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.sim.TUNING

// Badges (concept rule 49): pure check logic, immutable.
// The takeoff assist has no influence on awarding (rule 42).

const val COURSE_COUNT = 5

/** Course ids as save-file keys: "1".."5". */
val COURSE_IDS: List<String> = List(COURSE_COUNT) { (it + 1).toString() }

/** When a badge is awarded: right after a counted jump, or at the end of a finished ride. */
enum class BadgeAward {
    INSTANT,
    RIDE_END,
}

/** A badge: its stable [id] (save file) and the i18n keys of its name and condition. */
data class Badge(
    val id: String,
    val award: BadgeAward,
    val nameKey: String,
    val conditionKey: String,
)

private fun badge(
    id: String,
    award: BadgeAward,
) = Badge(id, award, nameKey = "badge.$id.name", conditionKey = "badge.$id.condition")

/** Order as in rule 49. */
val BADGES: List<Badge> =
    listOf(
        badge("firstJump", BadgeAward.INSTANT),
        badge("jumpMouse", BadgeAward.INSTANT),
        badge("clean", BadgeAward.RIDE_END),
        badge("oxerPro", BadgeAward.RIDE_END),
        badge("comboPro", BadgeAward.RIDE_END),
        badge("allOpen", BadgeAward.RIDE_END),
        badge("starRider", BadgeAward.RIDE_END),
        badge("busy", BadgeAward.RIDE_END),
    )

val BADGE_IDS: List<String> = BADGES.map { it.id }

/** The progress after a badge check and the ids of the badges that were newly awarded (in order). */
data class BadgeCheck(
    val progress: Progress,
    val awarded: List<String>,
)

private fun allCoursesThreeStars(progress: Progress) =
    COURSE_IDS.all {
        progress.courses[it]?.stars ==
            TUNING.scoring.maxStars
    }

// Only the real courses count, not stray entries of a damaged or newer save.
private fun anyCourseThreeStars(progress: Progress) =
    COURSE_IDS.any {
        progress.courses[it]?.stars ==
            TUNING.scoring.maxStars
    }

/** Awards all still-missing badges from [conditions] (id -> fulfilled) with date [nowIso]. */
private fun award(
    progress: Progress,
    conditions: Map<String, Boolean>,
    nowIso: String,
): BadgeCheck {
    val awarded = BADGES.filter { conditions[it.id] == true && !progress.badges.containsKey(it.id) }.map { it.id }
    if (awarded.isEmpty()) return BadgeCheck(progress, awarded)
    val badges = LinkedHashMap(progress.badges)
    for (id in awarded) badges[id] = nowIso
    return BadgeCheck(progress.copy(badges = badges), awarded)
}

/**
 * Instant badges (first jump, jump mouse). Call after every counted jump, i.e. after [addJump].
 * Conditions are "at least", so older saves catch up.
 */
fun checkInstantBadges(
    progress: Progress,
    nowIso: String,
): BadgeCheck {
    val jumps = progress.jumps
    return award(
        progress,
        mapOf("firstJump" to (jumps >= 1), "jumpMouse" to (jumps >= TUNING.badges.jumpsForJumpMouse)),
        nowIso,
    )
}

/**
 * Badges at ride end. Call only for FINISHED rides and AFTER [applyFinishedRide]; aborted rides
 * do not call it (the caller ensures this, rule 40).
 * Oxer pro and combination pro come exclusively from the ride result, never from stored data.
 * A stored course with 3 stars also counts as clean.
 */
fun checkRideEndBadges(
    progress: Progress,
    result: RideResult?,
    nowIso: String,
): BadgeCheck =
    award(
        progress,
        mapOf(
            "clean" to (result?.faults?.total == 0 || anyCourseThreeStars(progress)),
            "oxerPro" to (result?.cleanOxer == true),
            "comboPro" to (result?.cleanCombination == true),
            "allOpen" to (progress.unlocked >= COURSE_COUNT),
            "starRider" to allCoursesThreeStars(progress),
            "busy" to (progress.finishedRides >= TUNING.badges.ridesForBusy),
        ),
        nowIso,
    )
