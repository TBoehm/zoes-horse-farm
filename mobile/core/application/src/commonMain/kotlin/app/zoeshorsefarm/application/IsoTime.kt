package app.zoeshorsefarm.application

// Pure calendar arithmetic for the Clock port (no platform date API in this layer).

/**
 * [epochMs] (milliseconds since 1970-01-01T00:00:00Z) as ISO 8601 text with milliseconds and "Z",
 * the form `Date.toISOString()` writes in the web app (years 0000..9999).
 * Civil-from-days after Howard Hinnant's date algorithms.
 */
@Suppress("MagicNumber") // calendar constants of the proleptic Gregorian calendar, no game values
fun isoFromEpochMs(epochMs: Long): String {
    val days = epochMs.floorDiv(86_400_000L)
    val msOfDay = epochMs.mod(86_400_000L).toInt()

    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0

    val hour = msOfDay / 3_600_000
    val minute = msOfDay / 60_000 % 60
    val second = msOfDay / 1_000 % 60
    val milli = msOfDay % 1_000
    return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(hour, 2)}:${pad(minute, 2)}:" +
        "${pad(second, 2)}.${pad(milli, 3)}Z"
}

private fun pad(
    value: Number,
    width: Int,
): String = value.toString().padStart(width, '0')
