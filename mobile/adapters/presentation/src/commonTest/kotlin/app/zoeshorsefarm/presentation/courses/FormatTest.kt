package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun formatsMinutesSecondsHundredthsWithTwoDigitMinutes() {
        assertEquals("00:48,27", formatCs(4827))
        assertEquals("01:00,01", formatCs(6001))
        assertEquals("00:00,00", formatCs(0))
        assertEquals("01:15,20", formatCs(7520))
    }

    @Test
    fun usesADecimalPointInEnglish() {
        assertEquals("00:48.27", formatCs(4827, Language.EN))
    }

    @Test
    fun roundsToWholeHundredthsAndNeverShowsANegativeTime() {
        assertEquals("00:48,27", formatCs(4826.6))
        assertEquals("00:00,00", formatCs(-5))
    }

    @Test
    fun keepsCountingPastTenMinutes() {
        assertEquals("10:00,00", formatCs(60_000))
    }

    @Test
    fun roundsAHalfUpLikeJavaScript() {
        assertEquals("00:00,03", formatCs(2.5))
        assertEquals("00:00,00", formatCs(-0.5))
    }
}
