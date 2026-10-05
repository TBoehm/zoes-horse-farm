package app.zoeshorsefarm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReachTest {
    private val dMax = 1.0

    @Test
    fun isTheHardClampWithoutASoftZone() {
        assertEquals(0.5, softReach(0.5, dMax, 0.0))
        assertEquals(dMax, softReach(3.0, dMax, 0.0))
    }

    @Test
    fun keepsDistancesShortOfTheSoftZoneAsTheyAre() {
        for (d in listOf(0.1, 0.5, 0.9)) assertEquals(d, softReach(d, dMax, 0.1))
    }

    @Test
    fun neverReachesTheFullStretchHoweverFarTheTargetIs() {
        for (d in listOf(0.95, 1.0, 1.2, 1.5)) assertTrue(softReach(d, dMax, 0.1) < dMax)
        assertTrue(softReach(1e6, dMax, 0.1) <= dMax)
    }

    @Test
    fun isMonotonicAndHasNoKinkWhereTheCompressionStarts() {
        var prev = softReach(0.0, dMax, 0.1)
        var prevSlope = 1.0
        var d = 0.001
        while (d < 2) {
            val r = softReach(d, dMax, 0.1)
            val slope = (r - prev) / 0.001
            assertTrue(slope >= 0)
            // the slope falls gradually from 1 (no jump at the start of the zone)
            assertTrue(slope <= prevSlope + 1e-6)
            assertTrue(prevSlope - slope < 0.02)
            prev = r
            prevSlope = slope
            d += 0.001
        }
    }
}
