package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.testing.assertCloseTo
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    private val deg = PI / 180
    private val approach = 12.0
    private val halfPole = POLE_LENGTH / 2

    // rot 0: jump axis +z, cross axis -x (to the right when jumping in +z)
    private val vertical = Element("v", ElementKind.VERTICAL, 0.6, 0.0, 0.0, 0.0, 0.0)
    private val oxer = Element("o", ElementKind.OXER, 0.8, 0.8, 0.0, 0.0, 0.0)

    private fun horseAt(
        x: Double,
        z: Double,
        heading: Double = 0.0,
    ) = Pose(x, z, heading)

    private fun assertClose(
        expected: Double,
        actual: Double,
        digits: Int = 2,
    ) = assertCloseTo(expected, actual, digits)

    // ---- approachInfo ----

    @Test
    fun approachStraightTowardTheElementDirection1Distance8Angle0OnTheLine() {
        val info = assertNotNull(approachInfo(vertical, horseAt(0.0, -8.0), approach))
        assertEquals(1, info.dir)
        assertEquals(8.0, info.distance)
        assertEquals(0.0, info.angle)
        assertEquals(0.0, info.crossing)
        assertTrue(info.onLine)
        assertTrue(info.approaching)
    }

    @Test
    fun approachWorksFromTheOtherSideDirectionMinus1() {
        val info = assertNotNull(approachInfo(vertical, horseAt(0.0, 8.0, PI), approach))
        assertEquals(-1, info.dir)
        assertEquals(8.0, info.distance)
        assertTrue(info.onLine)
        assertClose(0.0, info.angle, 9)
    }

    @Test
    fun approachIsNullForAHeadingParallelToTheObstacle() {
        assertNull(approachInfo(vertical, horseAt(0.0, -5.0, PI / 2), approach))
        assertNull(approachInfo(vertical, horseAt(0.0, -5.0, -PI / 2), approach))
    }

    @Test
    fun approachIsNullWhileMovingAwayFromTheElement() {
        assertNull(approachInfo(vertical, horseAt(0.0, -5.0, PI), approach))
        assertNull(approachInfo(vertical, horseAt(0.0, 5.0, 0.0), approach))
    }

    @Test
    fun approachMeasuresTheDistanceOfAnOxerFromTheLeadingPoleHalfTheSpreadLess() {
        val near = assertNotNull(approachInfo(oxer, horseAt(0.0, -8.0), approach))
        assertClose(8 - 0.4, near.distance, 9)
        val far = assertNotNull(approachInfo(oxer, horseAt(0.0, 8.0, PI), approach))
        assertClose(8 - 0.4, far.distance, 9)
        assertEquals(-1, far.dir)
    }

    @Test
    fun approachReportsTheAngleToThePerpendicularOfTheObstacle() {
        val info = assertNotNull(approachInfo(vertical, horseAt(0.0, -8.0, 20 * deg), approach))
        assertClose(20 * deg, info.angle, 9)
        val mirrored = assertNotNull(approachInfo(vertical, horseAt(0.0, -8.0, -20 * deg), approach))
        assertClose(20 * deg, mirrored.angle, 9)
    }

    @Test
    fun theAngleIsNeverNegativeAndStaysDefinedForANearlyFrontalCourse() {
        val info = assertNotNull(approachInfo(vertical, horseAt(0.0, -8.0, 1e-9), approach))
        assertTrue(info.angle >= 0)
        assertFalse(info.angle.isNaN())
    }

    @Test
    fun approachOnTheLineJustInsideTheStandsNotJustBeyondThem() {
        // cross axis is -x: x = +offset is local across = -offset
        val inside = assertNotNull(approachInfo(vertical, horseAt(halfPole - 0.01, -8.0), approach))
        val beyond = assertNotNull(approachInfo(vertical, horseAt(halfPole + 0.01, -8.0), approach))
        assertTrue(inside.onLine)
        assertFalse(beyond.onLine)
        assertClose(-(halfPole + 0.01), beyond.crossing, 9)
        val left = assertNotNull(approachInfo(vertical, horseAt(-(halfPole + 0.01), -8.0), approach))
        assertFalse(left.onLine)
        assertClose(halfPole + 0.01, left.crossing, 9)
    }

    @Test
    fun approachComputesTheCrossingPointFromTheCourseNotFromTheHorsePosition() {
        // starts on the line but drifts out: 10 m before the plane at 12 degrees drift
        val drifting = assertNotNull(approachInfo(vertical, horseAt(0.0, -10.0, 12 * deg), approach))
        assertClose(-10 * tan(12 * deg), drifting.crossing, 9)
        assertFalse(drifting.onLine)
        // starts off the line but steers back onto it
        val steering = assertNotNull(approachInfo(vertical, horseAt(3.0, -10.0, -12 * deg), approach))
        assertTrue(steering.onLine)
    }

    @Test
    fun approachingNeedsTheLineAndADistanceStrictlyBelowTheApproachDistance() {
        assertTrue(assertNotNull(approachInfo(vertical, horseAt(0.0, -(approach - 0.01)), approach)).approaching)
        assertFalse(assertNotNull(approachInfo(vertical, horseAt(0.0, -approach), approach)).approaching)
        val closeButOff = assertNotNull(approachInfo(vertical, horseAt(halfPole + 0.5, -3.0), approach))
        assertFalse(closeButOff.onLine)
        assertFalse(closeButOff.approaching)
    }

    @Test
    fun approachRespectsTheElementRotation() {
        val turned = vertical.copy(x = 4.0, z = 4.0, rot = PI / 2)
        // jump axis is +x: a horse at x = -2 heading +x (heading pi/2) approaches over 6 m
        val info = assertNotNull(approachInfo(turned, horseAt(-2.0, 4.0, PI / 2), approach))
        assertEquals(1, info.dir)
        assertTrue(info.onLine)
        assertClose(6.0, info.distance, 9)
        assertClose(0.0, info.angle, 6)
    }

    // ---- wrapAngle ----

    @Test
    fun wrapAngleLeavesAnglesInsideMinusPiPiAlone() {
        assertEquals(0.0, wrapAngle(0.0))
        assertClose(1.0, wrapAngle(1.0), 12)
        assertClose(-1.0, wrapAngle(-1.0), 12)
    }

    @Test
    fun wrapAngleKeepsPiAndMapsMinusPiToPiUpperBoundIncludedLowerExcluded() {
        assertClose(PI, wrapAngle(PI), 12)
        assertClose(PI, wrapAngle(-PI), 12)
    }

    @Test
    fun wrapAngleWrapsJustBeyondTheBoundsToTheOtherSide() {
        assertClose(-PI + 0.1, wrapAngle(PI + 0.1), 12)
        assertClose(PI - 0.1, wrapAngle(-PI - 0.1), 12)
    }

    @Test
    fun wrapAngleWrapsFullTurnsAndMultiples() {
        assertClose(0.0, wrapAngle(2 * PI), 12)
        assertClose(0.0, wrapAngle(-2 * PI), 12)
        assertClose(PI, wrapAngle(3 * PI), 12)
        assertClose(PI, wrapAngle(-3 * PI), 12)
        assertClose(-PI + 0.5, wrapAngle(7 * PI + 0.5), 12)
    }

    // ---- element coordinates ----

    private val placed = Placement(3.0, -2.0, 0.7)

    @Test
    fun axesAreUnitVectorsPerpendicularToEachOther() {
        val n = axisOf(placed)
        val t = crossAxisOf(placed)
        assertClose(1.0, hypot(n.x, n.z), 12)
        assertClose(1.0, hypot(t.x, t.z), 12)
        assertClose(0.0, n.x * t.x + n.z * t.z, 12)
    }

    @Test
    fun toLocalAndFromLocalAreInverse() {
        val local = toLocal(placed, 5.5, 1.25)
        val world = fromLocal(placed, local.along, local.across)
        assertClose(5.5, world.x, 12)
        assertClose(1.25, world.z, 12)
    }

    @Test
    fun forwardOfAndHeadingOfAreInverse() {
        for (h in listOf(0.0, 1.0, -2.5, PI / 2)) {
            val f = forwardOf(h)
            assertClose(h, headingOf(f.x, f.z), 12)
        }
    }

    // ---- movedBackwards ----

    // heading 0 looks to +z
    @Test
    fun movedBackwardsIsTrueWhenTheDisplacementPointsAgainstTheHeading() {
        assertTrue(movedBackwards(0.0, 0.0, 0.0, -0.1, 0.0))
        assertTrue(movedBackwards(0.0, 0.0, 0.1, 0.0, -PI / 2))
    }

    @Test
    fun movedBackwardsIsFalseWhenMovingForwardSidewaysOrStanding() {
        assertFalse(movedBackwards(0.0, 0.0, 0.0, 0.1, 0.0))
        assertFalse(movedBackwards(0.0, 0.0, 0.1, 0.0, 0.0))
        assertFalse(movedBackwards(1.0, 2.0, 1.0, 2.0, 0.0))
    }

    @Test
    fun movedBackwardsIsFalseWithoutAUsableHeading() {
        assertFalse(movedBackwards(0.0, 0.0, 0.0, -1.0, Double.NaN))
    }
}
