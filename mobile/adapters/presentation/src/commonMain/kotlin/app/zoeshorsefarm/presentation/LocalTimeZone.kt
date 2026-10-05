package app.zoeshorsefarm.presentation

/**
 * The time zone of the player, as far as the screens need it: the UTC offset at a moment (it changes
 * with daylight saving time). The shell provides it (Android `TimeZone`, iOS `NSTimeZone`).
 */
fun interface LocalTimeZone {
    /** Minutes to add to UTC at [epochMs] (milliseconds since 1970-01-01T00:00:00Z); +60 for CET. */
    fun offsetMinutes(epochMs: Long): Int
}

/** UTC itself: the default until the shell provides the real zone. */
object UtcTimeZone : LocalTimeZone {
    override fun offsetMinutes(epochMs: Long): Int = 0
}
