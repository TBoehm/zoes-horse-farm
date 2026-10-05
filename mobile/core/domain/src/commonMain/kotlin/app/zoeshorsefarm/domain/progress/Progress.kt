package app.zoeshorsefarm.domain.progress

import app.zoeshorsefarm.domain.course.RatedResult
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.course.isBetterResult
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.shared.clamp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Progress (concept rules 36, 37, 44, 47, 48): pure and immutable, no DOM, no storage.
// All functions return new objects; unknown fields are preserved unchanged (rule 47): the save file
// may come from a newer version, so everything this version does not understand rides along in the
// `unknown*` maps as plain JSON-like values (null, Boolean, Number, String, List, Map).

/** Best result of one course: faults, time in hundredths and best stars; [extra] = unknown fields. */
data class CourseBest(
    val faults: Int,
    override val timeCs: Int,
    val stars: Int,
    val extra: Map<String, Any?> = emptyMap(),
) : RatedResult {
    override val totalFaults: Int get() = faults

    /** The JSON-like form of the save file. */
    fun toTree(): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(extra)
        tree["faults"] = faults
        tree["timeCs"] = timeCs
        tree["stars"] = stars
        return tree
    }
}

/**
 * The progress of the player: unlocked courses, best results per course (key "1".."5"), counted
 * jumps, finished rides and the awarded badges (id -> ISO date).
 *
 * [unknownCourses], [unknownBadges] and [unknown] hold what this version does not know (other
 * course ids, other badge ids, other top-level fields), unchanged.
 */
data class Progress(
    val unlocked: Int = 1,
    val courses: Map<String, CourseBest> = emptyMap(),
    val jumps: Int = 0,
    val finishedRides: Int = 0,
    val badges: Map<String, String> = emptyMap(),
    val unknownCourses: Map<String, Any?> = emptyMap(),
    val unknownBadges: Map<String, Any?> = emptyMap(),
    val unknown: Map<String, Any?> = emptyMap(),
) {
    /** The JSON-like form of the save file (what [sanitizeProgress] reads back). */
    fun toTree(): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(unknown)
        tree["unlocked"] = unlocked
        val courseTree = LinkedHashMap<String, Any?>(unknownCourses)
        for ((key, best) in courses) courseTree[key] = best.toTree()
        tree["courses"] = courseTree
        tree["jumps"] = jumps
        tree["finishedRides"] = finishedRides
        val badgeTree = LinkedHashMap<String, Any?>(unknownBadges)
        badgeTree.putAll(badges)
        tree["badges"] = badgeTree
        return tree
    }
}

/** The progress of a new player. */
val PROGRESS_DEFAULTS = Progress()

private fun finiteNumber(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun toInt(value: Double): Int = if (value >= Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()

private fun toCount(value: Any?): Int {
    val v = finiteNumber(value)
    return if (v != null && v >= 0) toInt(floor(v)) else 0
}

/** The entries of a JSON-like object with string keys; empty for anything else. */
private fun entriesOf(value: Any?): List<Pair<String, Any?>> =
    (value as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } } ?: emptyList()

private val COURSE_ENTRY_KEYS = setOf("faults", "timeCs", "stars")

/** A finite number >= 0, or null. */
private fun nonNegative(value: Any?): Double? = finiteNumber(value)?.takeIf { it >= 0 }

/** A whole number of stars in 1..maxStars, or null. */
private fun starCount(value: Any?): Int? =
    finiteNumber(value)?.takeIf { it == floor(it) && it >= 1 && it <= TUNING.scoring.maxStars }?.toInt()

private fun sanitizeCourse(entry: Any?): CourseBest? {
    if (entry !is Map<*, *>) return null
    val faults = nonNegative(entry["faults"])
    val timeCs = nonNegative(entry["timeCs"])
    val stars = starCount(entry["stars"])
    if (faults == null || timeCs == null || stars == null) return null
    val extra = entriesOf(entry).filter { it.first !in COURSE_ENTRY_KEYS }.toMap()
    return CourseBest(toInt(floor(faults)), toInt(floor(timeCs)), stars, extra)
}

/** Sanitized copy: invalid -> default, readable values are kept, unknown fields are kept. */
fun sanitizeProgress(raw: Any?): Progress {
    val source = entriesOf(raw).toMap()
    val unlockedRaw = finiteNumber(source["unlocked"])
    val unlocked = if (unlockedRaw != null) floor(unlockedRaw) else 1.0

    // Unknown keys (later versions) stay untouched (rule 47); courses 1..5 are cleaned
    val rawCourses = entriesOf(source["courses"]).toMap()
    val unknownCourses = rawCourses.filterKeys { it !in COURSE_IDS }
    val courses = LinkedHashMap<String, CourseBest>()
    for (key in COURSE_IDS) {
        val entry = sanitizeCourse(rawCourses[key])
        if (entry != null) courses[key] = entry
    }

    val unknownBadges = LinkedHashMap<String, Any?>()
    val badges = LinkedHashMap<String, String>()
    for ((id, value) in entriesOf(source["badges"])) {
        if (id !in BADGE_IDS) {
            unknownBadges[id] = value
        } else if (value is String && isValidIsoDate(value)) {
            badges[id] = value
        }
    }

    return Progress(
        unlocked = clamp(unlocked, 1.0, COURSE_COUNT.toDouble()).toInt(),
        courses = courses,
        jumps = toCount(source["jumps"]),
        finishedRides = toCount(source["finishedRides"]),
        badges = badges,
        unknownCourses = unknownCourses,
        unknownBadges = unknownBadges,
        unknown = source.filterKeys { it !in KNOWN_FIELDS },
    )
}

private val KNOWN_FIELDS = setOf("unlocked", "courses", "jumps", "finishedRides", "badges")

/** The progress after a finished ride, whether it was a new best, and the newly unlocked course. */
data class AppliedRide(
    val progress: Progress,
    val isNewBest: Boolean,
    val unlockedCourse: Int?,
)

/**
 * Applies a FINISHED ride (never pass aborted rides, rule 40). A ride of an unknown course changes
 * nothing.
 */
fun applyFinishedRide(
    progress: Progress,
    result: RideResult,
): AppliedRide {
    val courseId = result.courseId
    if (courseId < 1 || courseId > COURSE_COUNT) return AppliedRide(progress, isNewBest = false, unlockedCourse = null)
    val key = courseId.toString()
    val stars = min(TUNING.scoring.maxStars, max(1, result.stars))
    val previous = progress.courses[key]
    val isNewBest = isBetterResult(result, previous)
    val faults = if (isNewBest || previous == null) result.faults.total else previous.faults
    val timeCs = if (isNewBest || previous == null) result.timeCs else previous.timeCs

    val next = min(COURSE_COUNT, courseId + 1)
    val unlockedCourse = if (next > progress.unlocked) next else null

    val best =
        CourseBest(
            faults = faults,
            timeCs = timeCs,
            stars = max(previous?.stars ?: 0, stars),
            extra = previous?.extra ?: emptyMap(),
        )
    return AppliedRide(
        progress =
            progress.copy(
                unlocked = unlockedCourse ?: progress.unlocked,
                courses = progress.courses + (key to best),
                finishedRides = progress.finishedRides + 1,
            ),
        isNewBest = isNewBest,
        unlockedCourse = unlockedCourse,
    )
}

/** Counts one counted jump (rule 40). */
fun addJump(progress: Progress): Progress = progress.copy(jumps = progress.jumps + 1)

/** "Delete progress" (rule 48): only these fields, everything else is kept. */
fun resetProgress(progress: Progress): Progress =
    progress.copy(
        unlocked = PROGRESS_DEFAULTS.unlocked,
        courses = emptyMap(),
        jumps = PROGRESS_DEFAULTS.jumps,
        finishedRides = PROGRESS_DEFAULTS.finishedRides,
        badges = emptyMap(),
        unknownCourses = emptyMap(),
        unknownBadges = emptyMap(),
    )

// ISO 8601 as written by Date.toISOString(): YYYY-MM-DD with an optional time and zone, the
// forms JavaScript's Date.parse accepts for it. Other texts that a browser would still parse
// ("Oct 3 2026") count as invalid here: the app only ever stores ISO dates.
private val ISO_DATE =
    Regex(
        "^([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
            "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d+)?)?(Z|z|[+-]\\d{2}(?::?\\d{2})?)?)?$",
    )

// capture groups of ISO_DATE
private const val G_MONTH = 2
private const val G_DAY = 3
private const val G_HOUR = 4
private const val G_MINUTE = 5
private const val G_SECOND = 6
private const val G_ZONE = 7

// ranges of the date and time parts (24:00:00 is allowed as end of day, like in JavaScript)
private const val MAX_MONTH = 12
private const val MAX_DAY = 31
private const val MAX_HOUR = 24
private const val MAX_MINUTE = 59
private const val MAX_SECOND = 59
private const val MAX_ZONE_HOUR = 23
private const val ZONE_HHMM_LENGTH = 4
private const val ZONE_HOUR_DIGITS = 2

private fun part(
    groups: List<String>,
    i: Int,
): Int? = groups[i].takeIf { it.isNotEmpty() }?.toInt()

private fun isDatePartValid(groups: List<String>): Boolean {
    val month = part(groups, G_MONTH)
    val day = part(groups, G_DAY)
    return (month == null || month in 1..MAX_MONTH) && (day == null || day in 1..MAX_DAY)
}

private fun isTimePartValid(groups: List<String>): Boolean {
    val hour = part(groups, G_HOUR) ?: return true
    val minute = part(groups, G_MINUTE) ?: 0
    val second = part(groups, G_SECOND) ?: 0
    val endOfDay = hour == MAX_HOUR && (minute != 0 || second != 0)
    return hour <= MAX_HOUR && minute <= MAX_MINUTE && second <= MAX_SECOND && !endOfDay
}

private fun isZoneValid(zone: String): Boolean {
    if (zone.length <= 1) return true
    val digits = zone.drop(1).replace(":", "")
    val minutesValid = digits.length != ZONE_HHMM_LENGTH || digits.drop(ZONE_HOUR_DIGITS).toInt() <= MAX_MINUTE
    return digits.take(ZONE_HOUR_DIGITS).toInt() <= MAX_ZONE_HOUR && minutesValid
}

/** Is [text] a date the saved badges may carry (a valid ISO 8601 date, optionally with a time)? */
fun isValidIsoDate(text: String): Boolean {
    val groups = ISO_DATE.matchEntire(text)?.groupValues ?: return false
    return isDatePartValid(groups) && isTimePartValid(groups) && isZoneValid(groups[G_ZONE])
}
