package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemClockTest {
    @Test
    fun `reports a plausible time in milliseconds`() {
        // after 2024-01-01 and not decreasing
        val first = SystemClock.nowMs()
        assertTrue(first > 1_704_067_200_000L)
        assertTrue(SystemClock.nowMs() >= first)
    }

    @Test
    fun `reports the same time as ISO text with milliseconds and Z`() {
        val iso = SystemClock.nowIso()
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(iso), iso)
        assertEquals("20", iso.take(2))
    }
}
