package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.testing.assertCloseTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReinBackTest {
    private val r = TUNING.reinBack
    private val dt = 1.0 / 60

    private fun standing(
        speed: Double = 0.0,
        gallop: Boolean = false,
        settling: Boolean = false,
        backBlocked: Boolean = false,
    ) = SpeedState(speed = speed, gallop = gallop, settling = settling, backHold = 0.0, backBlocked = backBlocked)

    /** One step of the same chain the sim uses: rein-back first, the normal speed update otherwise. */
    private fun stepOnce(
        state: SpeedState,
        throttle: Double,
    ) {
        if (!updateReinBack(state, throttle, dt, TUNING)) updateSpeed(state, throttle, dt, TUNING)
    }

    private fun run(
        state: SpeedState,
        throttle: Double,
        seconds: Double,
    ): SpeedState {
        var t = 0.0
        while (t < seconds - 1e-9) {
            stepOnce(state, throttle)
            t += dt
        }
        return state
    }

    // ---- rein-back tuning (rule 9) ----

    @Test
    fun reinBackTuningHasAPauseShorterThanHalfASecondAndASpeedWellBelowTheWalk() {
        assertTrue(r.delayS > 0)
        assertTrue(r.delayS < 0.5)
        assertTrue(r.maxSpeed > 0)
        assertTrue(r.maxSpeed < TUNING.speeds.walkMax / 2)
        assertTrue(r.accel > 0)
        assertTrue(r.decel > 0)
        assertTrue(r.blockedShare > 0)
        assertTrue(r.blockedShare < 1)
        assertTrue(r.rearClearance > 0)
    }

    // ---- updateReinBack ----

    @Test
    fun doesNotTakeOverBeforeThePauseIsOverThenStartsBacking() {
        val s = standing()
        run(s, -1.0, r.delayS - 0.05)
        assertEquals(0.0, s.speed)
        run(s, -1.0, 0.1)
        assertTrue(s.speed < 0)
    }

    @Test
    fun acceleratesToTheMaximumSpeedAndNeverBeyond() {
        val s = run(standing(), -1.0, 4.0)
        assertCloseTo(-r.maxSpeed, s.speed, 9)
    }

    @Test
    fun followsTheDeflectionJoystickHalfDownGivesHalfTheMaximumSpeed() {
        val s = run(standing(), -0.5, 4.0)
        assertCloseTo(-r.maxSpeed / 2, s.speed, 9)
    }

    @Test
    fun slowsDownToTheNewTargetWhenTheDeflectionIsReduced() {
        val s = run(standing(), -1.0, 3.0)
        run(s, -0.4, 3.0)
        assertCloseTo(-r.maxSpeed * 0.4, s.speed, 9)
    }

    @Test
    fun stopsWhenTheThrottleIsReleasedAndThePauseStartsAnew() {
        val s = run(standing(), -1.0, 3.0)
        run(s, 0.0, 2.0)
        assertEquals(0.0, s.speed)
        run(s, -1.0, r.delayS - 0.05)
        assertEquals(0.0, s.speed)
    }

    @Test
    fun forwardThrottleEndsItAtOnceAndThenAcceleratesNormally() {
        val s = run(standing(), -1.0, 3.0)
        stepOnce(s, 1.0)
        assertCloseTo(TUNING.control.speedUp * dt, s.speed, 9)
    }

    @Test
    fun gallopEndsItAtOnce() {
        val s = run(standing(), -1.0, 3.0)
        s.gallop = true
        stepOnce(s, -1.0)
        assertTrue(s.speed >= 0)
    }

    @Test
    fun brakingFromAWalkWithSDoesNotBackUntilTheHorseStandsAndThePauseIsOver() {
        val s = standing(speed = 1.2)
        var backedWhileMoving = false
        var t = 0.0
        while (t < 1.2) {
            val wasMoving = s.speed > 0
            stepOnce(s, -1.0)
            if (wasMoving && s.speed < 0) backedWhileMoving = true
            t += dt
        }
        assertFalse(backedWhileMoving)
        assertTrue(s.speed < 0)
    }

    @Test
    fun doesNotStartWithoutANegativeThrottle() {
        val s = run(standing(), 0.0, 2.0)
        assertEquals(0.0, s.speed)
        assertFalse(updateReinBack(s, 0.0, dt, TUNING))
    }

    @Test
    fun aBlockedHorseStaysPutUntilTheThrottleIsReleased() {
        val s = standing(backBlocked = true)
        run(s, -1.0, 2.0)
        assertEquals(0.0, s.speed)
        run(s, 0.0, dt)
        assertFalse(s.backBlocked)
        run(s, -1.0, r.delayS + 0.1)
        assertTrue(s.speed < 0)
    }

    @Test
    fun doesNotStartWhileTheGallopIsActiveOrSettling() {
        val galloping = run(standing(gallop = true), -1.0, 1.0)
        assertTrue(galloping.speed >= 0)
        val settling = standing(settling = true)
        assertFalse(updateReinBack(settling, -1.0, 1.0, TUNING))
        assertEquals(0.0, settling.speed)
    }
}
