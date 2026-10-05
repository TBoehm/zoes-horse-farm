package app.zoeshorsefarm.input

import app.zoeshorsefarm.domain.sim.TUNING
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val STICK_DEAD_ZONE = TUNING.control.stickDeadZone
private val STEER_FULL = TUNING.control.stickSteerFull

private fun deg(d: Double) = d * PI / 180

private fun deg(d: Int) = deg(d.toDouble())

/** Like vitest's `toBeCloseTo(expected, digits)`. */
internal fun assertCloseTo(
    expected: Double,
    actual: Double,
    digits: Int,
) {
    var limit = 0.5
    repeat(digits) { limit /= 10 }
    assertTrue(abs(expected - actual) < limit, "expected $actual to be close to $expected ($digits digits)")
}

class JoystickMappingTest {
    private val neutral = StickOutput(steer = 0.0, throttle = 0.0)

    @Test
    fun pushingRightSteersRightAndUpGivesFullThrottle() {
        val right = mapStick(1.0, deg(0))
        assertEquals(1.0, right.steer)
        assertCloseTo(0.0, right.throttle, 9)
        val up = mapStick(1.0, deg(90))
        assertCloseTo(0.0, up.steer, 9)
        assertCloseTo(1.0, up.throttle, 9)
    }

    @Test
    fun pushingLeftSteersLeftAndDownBrakes() {
        assertCloseTo(-1.0, mapStick(1.0, deg(180)).steer, 9)
        assertCloseTo(-1.0, mapStick(1.0, deg(270)).throttle, 9)
    }

    @Test
    fun radialDeadZoneMakesASmallDeflectionInAnyDirectionNeutral() {
        for (angle in 0 until 360 step 15) {
            assertEquals(neutral, mapStick(STICK_DEAD_ZONE * 0.9, deg(angle)))
        }
    }

    @Test
    fun scaledRadialDeadZoneStartsTheOutputAtAboutZeroJustOutsideTheDeadZone() {
        val out = mapStick(STICK_DEAD_ZONE + 0.01, deg(90))
        assertTrue(out.throttle > 0)
        assertTrue(out.throttle < 0.02)
        val side = mapStick(STICK_DEAD_ZONE + 0.01, deg(0))
        assertTrue(side.steer > 0)
        assertTrue(side.steer < 0.05)
    }

    @Test
    fun throttleIsTheRescaledVerticalComponentNotAmplified() {
        val half = (1 - STICK_DEAD_ZONE) / 2 + STICK_DEAD_ZONE
        assertCloseTo(0.5, mapStick(half, deg(90)).throttle, 9)
        assertCloseTo(-0.5, mapStick(half, deg(270)).throttle, 9)
    }

    @Test
    fun pullingTheStickFullyDownGivesThrottleMinusOne() {
        val down = mapStick(1.0, deg(270))
        assertCloseTo(-1.0, down.throttle, 9)
        assertTrue(abs(down.steer) < 1e-9)
    }

    @Test
    fun fullSteeringLockIsReachedAtTwoThirdsSidewaysDeflectionAtTheLatest() {
        assertTrue(STEER_FULL <= 2.0 / 3)
        assertEquals(1.0, mapStick(2.0 / 3, deg(0)).steer)
        assertEquals(-1.0, mapStick(2.0 / 3, deg(180)).steer)
        assertCloseTo(1.0, mapStick(STEER_FULL, deg(0)).steer, 9)
        assertTrue(mapStick(STEER_FULL * 0.99, deg(0)).steer < 1)
    }

    @Test
    fun fullLockComesEarlyOnTheStickAtHalfDeflectionOrLess() {
        assertTrue(STEER_FULL <= 0.5)
        assertEquals(1.0, mapStick(0.5, deg(0)).steer)
    }

    @Test
    fun aThumbWobbleWithinTheAxialZoneAroundVerticalStaysStraightAtAnyForce() {
        val edge = asin(TUNING.control.stickAxialSteer)
        for (force in listOf(0.3, 0.6, 1.0)) {
            for (sign in listOf(-1, 1)) {
                assertEquals(0.0, mapStick(force, PI / 2 + sign * 0.95 * edge).steer)
            }
        }
    }

    @Test
    fun aWobbleJustOutsideTheAxialZoneSteersOnlyGently() {
        // 10 degrees off vertical at full deflection
        val steer = mapStick(1.0, deg(80)).steer
        assertTrue(steer > 0)
        assertTrue(steer < 0.2)
    }

    @Test
    fun steeringGrowsLinearlyBetweenTheDeadZoneAndTheFullLockDeflection() {
        val mid = (STICK_DEAD_ZONE + STEER_FULL) / 2
        assertCloseTo(0.5, mapStick(mid, deg(0)).steer, 9)
        var last = 0.0
        var f = STICK_DEAD_ZONE
        while (f <= 1) {
            val steer = mapStick(f, deg(0)).steer
            assertTrue(steer >= last)
            last = steer
            f += 0.02
        }
    }

    @Test
    fun holdingTheStick45DegreesForwardRightSteersClearlyAndStillAccelerates() {
        val full = mapStick(1.0, deg(45))
        assertTrue(full.steer >= 0.9)
        assertTrue(full.throttle > 0.6)
        val light = mapStick(0.6, deg(45))
        assertTrue(light.steer >= 0.5)
        assertTrue(light.throttle > 0.2)
        assertTrue(mapStick(1.0, deg(135)).steer <= -0.9)
    }

    @Test
    fun aNearlyStraightForwardHoldGivesSteerZeroAndFullThrottle() {
        val nearlyUp = mapStick(1.0, deg(86))
        assertEquals(0.0, nearlyUp.steer)
        assertCloseTo(0.997, nearlyUp.throttle, 2)
    }

    @Test
    fun aNearlyStraightDownHoldGivesSteerZeroAndReinsBack() {
        val nearlyDown = mapStick(1.0, deg(266))
        assertEquals(0.0, nearlyDown.steer)
        assertCloseTo(-0.997, nearlyDown.throttle, 2)
    }

    @Test
    fun aNearHorizontalHoldGivesThrottleZeroSoTurningOnTheSpotStaysATurn() {
        for (angle in listOf(0, 10, -10, 180, 170, 190)) {
            val out = mapStick(1.0, deg(angle))
            assertEquals(0.0, out.throttle)
            assertEquals(1.0, abs(out.steer))
        }
        assertEquals(0.0, mapStick(STEER_FULL, deg(10)).throttle)
    }

    @Test
    fun aboveTheAxialThrottleZoneTheThrottleStartsWithoutAJump() {
        // 15 degrees above horizontal: only a little throttle
        assertCloseTo(0.074, mapStick(1.0, deg(15)).throttle, 2)
        val edge = asin(TUNING.control.stickAxialThrottle)
        assertEquals(0.0, mapStick(1.0, edge - 0.001).throttle)
        assertTrue(mapStick(1.0, edge + 0.01).throttle < 0.02)
    }

    @Test
    fun diagonalHoldsKeepBothComponents() {
        val full = mapStick(1.0, deg(45))
        assertEquals(1.0, full.steer)
        assertCloseTo(0.634, full.throttle, 2)
        val light = mapStick(0.6, deg(45))
        assertCloseTo(0.842, light.steer, 2)
        assertCloseTo(0.346, light.throttle, 2)
    }

    @Test
    fun clampsForcesAboveOneAndIgnoresInvalidValues() {
        assertEquals(1.0, mapStick(3.0, deg(0)).steer)
        assertEquals(neutral, mapStick(Double.NaN, deg(0)))
        assertEquals(neutral, mapStick(1.0, Double.NaN))
        assertEquals(neutral, mapStick(Double.POSITIVE_INFINITY, deg(0)))
        assertEquals(neutral, mapStick(-1.0, deg(0)))
    }

    // mapStickXY (Compose helper replacing the nipplejs force/angle pair)

    @Test
    fun cartesianStickPositionGivesTheSameOutputAsForceAndAngle() {
        assertEquals(mapStick(1.0, deg(0)), mapStickXY(1.0, 0.0))
        assertEquals(mapStick(1.0, deg(90)), mapStickXY(0.0, 1.0))
        assertEquals(mapStick(1.0, deg(180)), mapStickXY(-1.0, 0.0))
        assertEquals(mapStick(0.5, deg(270)), mapStickXY(0.0, -0.5))
    }

    @Test
    fun cartesianCenterAndInvalidValuesAreNeutral() {
        assertEquals(neutral, mapStickXY(0.0, 0.0))
        assertEquals(neutral, mapStickXY(Double.NaN, 0.5))
        assertEquals(neutral, mapStickXY(0.5, Double.POSITIVE_INFINITY))
    }
}
