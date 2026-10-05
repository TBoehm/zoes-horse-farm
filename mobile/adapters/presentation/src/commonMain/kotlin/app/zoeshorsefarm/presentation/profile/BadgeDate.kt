package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.isoFromEpochMs
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.presentation.LocalTimeZone
import app.zoeshorsefarm.presentation.UtcTimeZone

// Date of an earned badge as long text. The month names and the pattern are texts of the language
// files (`date.long`, `date.month.N`), the calendar maths is plain arithmetic.

// date, optional time with optional fraction, optional zone (Z or +hh:mm)
private val ISO_TIMESTAMP =
    Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:\d{2})?)?$""")

private val DAYS_IN_MONTH = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_SECOND = 1_000L
private const val MS_PER_DAY = 86_400_000L
private const val MAX_FRACTION_DIGITS = 3

@Suppress("MagicNumber") // Gregorian leap-year rule
private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

/**
 * Days since 1970-01-01 of a calendar date (days-from-civil after Howard Hinnant's date algorithms).
 */
@Suppress("MagicNumber") // calendar constants of the proleptic Gregorian calendar
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

private fun zoneMinutes(text: String?): Int {
    if (text == null || text == "Z") return 0
    val sign = if (text[0] == '-') -1 else 1
    return sign * (text.substring(1, 3).toInt() * 60 + text.substring(4, 6).toInt())
}

// milliseconds of the fraction "5" = 500, "123456" = 123 (only the first three digits count)
private fun fractionMs(text: String): Long =
    text
        .take(MAX_FRACTION_DIGITS)
        .padEnd(MAX_FRACTION_DIGITS, '0')
        .toLong()

/** The instant of an ISO timestamp in epoch milliseconds, or null when it is no valid timestamp. */
private fun parseEpochMs(iso: String): Long? {
    val g = ISO_TIMESTAMP.matchEntire(iso)?.groupValues ?: return null
    val (year, month, day) = g.subList(1, 4).map { it.toInt() }
    val days = DAYS_IN_MONTH.getOrNull(month - 1)?.plus(if (month == 2 && isLeap(year)) 1 else 0)
    val hour = g[4].ifEmpty { "0" }.toInt()
    val minute = g[5].ifEmpty { "0" }.toInt()
    val second = g[6].ifEmpty { "0" }.toInt()
    val valid = days != null && day in 1..days && hour < 24 && minute < 60 && second < 60
    if (!valid) return null
    val timeMs = hour * MS_PER_HOUR + minute * MS_PER_MINUTE + second * MS_PER_SECOND + fractionMs(g[7])
    return daysFromCivil(year, month, day) * MS_PER_DAY + timeMs - zoneMinutes(g[8].ifEmpty { null }) * MS_PER_MINUTE
}

/**
 * The calendar date of an ISO timestamp as long text in the language of [i18n]: "5. März 2026" in
 * German, "5 March 2026" in English (web: `toLocaleDateString` with de-DE / en-GB). The date is the
 * one in the player's [zone] (00:30 local time shows the local day). An invalid date gives "".
 */
fun formatBadgeDate(
    iso: String,
    i18n: I18n,
    zone: LocalTimeZone = UtcTimeZone,
): String {
    val epochMs = parseEpochMs(iso) ?: return ""
    val localMs = epochMs + zone.offsetMinutes(epochMs) * MS_PER_MINUTE
    val (year, month, day) = isoFromEpochMs(localMs).take(10).split('-').map { it.toInt() }
    return i18n.t(
        "date.long",
        mapOf("day" to day, "month" to i18n.t("date.month.$month"), "year" to year),
    )
}
