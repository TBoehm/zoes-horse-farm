package app.zoeshorsefarm.domain.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningTest {
    @Test
    fun hasTheControlTimingAndGuardValuesAsFinitePositiveNumbers() {
        val values =
            listOf(
                TUNING.control.stickDeadZone,
                TUNING.control.stickSteerFull,
                TUNING.control.stickAxialThrottle,
                TUNING.control.stickAxialSteer,
                TUNING.control.gallopEndTrotUp,
                TUNING.rebuildDelayS,
                TUNING.missingHintS,
                TUNING.refusal.minStopRoom,
                TUNING.fence.releaseGap,
                TUNING.jump.window.heightRef,
                TUNING.jump.lastPoint.maxShareOfNear,
            )
        for (v in values) {
            assertTrue(v.isFinite())
            assertTrue(v > 0)
        }
    }

    @Test
    fun keepsTheStickDeadZoneSmallAndTheNearEdgeShareBelow1() {
        assertTrue(TUNING.control.stickDeadZone < 0.5)
        assertTrue(TUNING.jump.lastPoint.maxShareOfNear <= 1)
    }

    @Test
    fun reachesFullSteeringLockBeforeTheStickStopAtMost23AboveTheDeadZone() {
        assertTrue(TUNING.control.stickSteerFull <= 2.0 / 3)
        assertTrue(TUNING.control.stickSteerFull > TUNING.control.stickDeadZone)
    }

    @Test
    fun keepsTheAxialStickZonesNarrowAbout7To12DegreesAroundTheAxes() {
        val c = TUNING.control
        assertTrue(c.stickAxialThrottle > 0.1)
        assertTrue(c.stickAxialThrottle <= 0.3)
        assertTrue(c.stickAxialSteer > 0.05)
        assertTrue(c.stickAxialSteer <= 0.2)
    }

    @Test
    fun hasTheCourseBuildingValuesAsPositiveNumbers() {
        val c = TUNING.course
        for (v in listOf(c.stride, c.takeoffLanding, c.landingFree, c.oxerSpread.tall)) {
            assertTrue(v.isFinite())
            assertTrue(v > 0)
        }
        val heights = c.oxerSpread.byMaxHeight.map { it.maxHeight }
        assertEquals(heights.sorted(), heights)
    }

    @Test
    fun derivesTheCombinationDistanceFromTheCourseBuildingValues() {
        assertTrue(abs(COMBI_DISTANCE - (2 * TUNING.course.takeoffLanding + TUNING.course.stride)) < 1e-9)
    }

    @Test
    fun aTuningVariantKeepsEveryOtherValue() {
        val variant = TUNING.copy(rebuildDelayS = 9.0)
        assertEquals(9.0, variant.rebuildDelayS)
        assertEquals(TUNING.jump, variant.jump)
    }
}
