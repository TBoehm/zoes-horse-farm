package app.zoeshorsefarm.application

import kotlin.test.Test
import kotlin.test.assertEquals

class PortsTest {
    @Test
    fun formatsTheEpochAsAnIsoDateWithMilliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", isoFromEpochMs(0))
        assertEquals("1970-01-01T00:16:40.000Z", isoFromEpochMs(1_000_000))
    }

    @Test
    fun formatsARealDate() {
        assertEquals("2026-01-02T03:04:05.000Z", isoFromEpochMs(1_767_323_045_000))
        assertEquals("2026-01-02T03:04:05.007Z", isoFromEpochMs(1_767_323_045_007))
    }

    @Test
    fun handlesLeapDaysAndYearEnds() {
        assertEquals("2024-02-29T23:59:59.999Z", isoFromEpochMs(1_709_251_199_999))
        assertEquals("2000-12-31T00:00:00.000Z", isoFromEpochMs(978_220_800_000))
    }

    @Test
    fun formatsDatesBeforeTheEpoch() {
        assertEquals("1969-12-31T23:59:59.999Z", isoFromEpochMs(-1))
    }

    @Test
    fun theDefaultNowIsoFollowsNowMs() {
        val clock =
            object : Clock {
                override fun nowMs() = 1_000_000L
            }
        assertEquals("1970-01-01T00:16:40.000Z", clock.nowIso())
    }
}
