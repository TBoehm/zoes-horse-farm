package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.wrapAngle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DT = 1.0 / 60

class FollowHeadingTest {
    @Test
    fun `moves towards the target without overshooting`() {
        var h = 0.0
        repeat(120) {
            h = followHeading(h, 1.0, 4.0, DT)
            assertTrue(h >= 0.0)
            assertTrue(h <= 1.0)
        }
        assertTrue(h > 0.99)
    }

    @Test
    fun `takes the short way around the circle wrap at plus minus pi`() {
        val h = followHeading(PI - 0.05, -PI + 0.05, 4.0, DT)
        // continues over +pi instead of swinging back through 0
        assertTrue(abs(h) > PI - 0.05)
    }

    @Test
    fun `keeps the result between minus pi and pi`() {
        var h = PI - 0.01
        repeat(30) {
            h = followHeading(h, -PI + 0.3, 4.0, DT)
            assertTrue(abs(h) <= PI + 1e-9)
        }
    }

    @Test
    fun `does not move at all when already on target or with dt = 0`() {
        assertEquals(0.7, followHeading(0.7, 0.7, 4.0, DT), 1e-12)
        assertEquals(0.2, followHeading(0.2, 1.5, 4.0, 0.0), 1e-12)
    }

    @Test
    fun `limits the camera swing rate to maxRate rad per s on big heading jumps`() {
        val h = followHeading(0.0, 2.0, 4.0, DT, 1.5)
        assertTrue(abs(h) <= 1.5 * DT + 1e-12)
    }
}

class CameraHeadingConstantsTest {
    // fastest the player can turn the horse (rad/s)
    private val maxTurn = TUNING.control.turnInPlace

    /** Holds a constant turn rate for `seconds` and returns the largest lag (rad) seen. */
    private fun maxLag(
        turnRate: Double,
        seconds: Double,
        stiffness: Double,
        maxRate: Double,
    ): Double {
        var horse = 0.0
        var cam = 0.0
        var worst = 0.0
        repeat((seconds / DT).roundToInt()) {
            horse = wrapAngle(horse + turnRate * DT)
            cam = followHeading(cam, horse, stiffness, DT, maxRate)
            worst = max(worst, abs(wrapAngle(horse - cam)))
        }
        return worst
    }

    @Test
    fun `the follow camera keeps up with the fastest turn - the lag stays bounded for 20 s`() {
        val steady = maxTurn / FOLLOW_HEADING_STIFFNESS
        val lag = maxLag(maxTurn, 20.0, FOLLOW_HEADING_STIFFNESS, FOLLOW_HEADING_MAX_RATE)
        // near the steady-state value of an exponential follower, never a whole turn behind
        assertTrue(lag < steady * 1.1)
        assertTrue(lag < 60 * PI / 180)
    }

    @Test
    fun `the rider view keeps up with the fastest turn too`() {
        val steady = maxTurn / RIDER_HEADING_STIFFNESS
        assertTrue(maxLag(maxTurn, 20.0, RIDER_HEADING_STIFFNESS, Double.POSITIVE_INFINITY) < steady * 1.1)
    }

    @Test
    fun `the swing-rate cap lies safely above the fastest player turn`() {
        assertTrue(FOLLOW_HEADING_MAX_RATE > maxTurn * 1.1)
    }

    @Test
    fun `still limits the swing on a sudden big heading jump`() {
        val h = followHeading(0.0, PI, FOLLOW_HEADING_STIFFNESS, DT, FOLLOW_HEADING_MAX_RATE)
        assertTrue(abs(h) <= FOLLOW_HEADING_MAX_RATE * DT + 1e-12)
    }
}
