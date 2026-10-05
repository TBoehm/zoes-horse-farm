package app.zoeshorsefarm.platform

import app.zoeshorsefarm.application.Clock
import kotlin.time.Clock as KotlinClock

/** The system clock as the application's [Clock] port: badge dates and durations (web: `clock.js`). */
object SystemClock : Clock {
    override fun nowMs(): Long = KotlinClock.System.now().toEpochMilliseconds()
}
