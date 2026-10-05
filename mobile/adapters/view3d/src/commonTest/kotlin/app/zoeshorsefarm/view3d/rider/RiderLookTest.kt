package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertGreaterOrEqual
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DT = 1.0 / 60

private fun run(
    look: HeadLook,
    state: Horse,
    seconds: Double,
    calm: Double = 0.0,
): HeadLook {
    for (i in 0 until (seconds / DT).roundToInt()) stepHeadLook(look, DT, state, calm)
    return look
}

class LookTargetTest {
    @Test
    fun `looks ahead when going straight`() {
        val t = lookTarget(Horse(turnRate = 0.0))
        assertEquals(0.0, t.yaw)
        assertEquals(0.0, t.pitch)
    }

    @Test
    fun `turns the head into the curve right turn is negative yaw and plus X is left`() {
        assertLess(lookTarget(Horse(turnRate = 0.8)).yaw, -0.2)
        assertGreater(lookTarget(Horse(turnRate = -0.8)).yaw, 0.2)
    }

    @Test
    fun `clamps the yaw`() {
        assertClose(-0.55, lookTarget(Horse(turnRate = 50.0)).yaw, 9)
        assertClose(0.55, lookTarget(Horse(turnRate = -50.0)).yaw, 9)
    }

    @Test
    fun `looks down at the fence on take-off and ahead in flight`() {
        val takeoff = lookTarget(Horse(jump = JumpView(JumpPhase.TAKEOFF, 0.75)))
        val flight = lookTarget(Horse(jump = JumpView(JumpPhase.FLIGHT, 0.5)))
        assertGreater(takeoff.pitch, 0.1)
        assertLess(flight.pitch, -0.05)
    }

    @Test
    fun `returns to level after the landing`() {
        assertClose(0.0, lookTarget(Horse(jump = JumpView(JumpPhase.LANDING, 1.0))).pitch, 6)
    }

    @Test
    fun `glances around only when calm`() {
        var calmMax = 0.0
        var busyMax = 0.0
        var t = 0.0
        while (t < 60) {
            calmMax = max(calmMax, abs(lookTarget(Horse(), 1.0, t).yaw))
            busyMax = max(busyMax, abs(lookTarget(Horse(), 0.0, t).yaw))
            t += 0.25
        }
        assertGreater(calmMax, 0.03)
        assertLessOrEqual(calmMax, 0.14 + 1e-9)
        assertEquals(0.0, busyMax)
        assertGreaterOrEqual(calmMax, 0.0)
    }
}

class StepHeadLookTest {
    @Test
    fun `eases into the curve without overshoot and back out`() {
        val look = createHeadLook()
        val target = lookTarget(Horse(turnRate = 0.8)).yaw
        var prev = 0.0
        var maxStep = 0.0
        for (i in 0 until 180) {
            stepHeadLook(look, DT, Horse(turnRate = 0.8), 0.0)
            maxStep = max(maxStep, abs(look.yaw.x - prev))
            prev = look.yaw.x
            assertGreaterOrEqual(look.yaw.x, target - 1e-9)
        }
        assertClose(target, look.yaw.x, 2)
        assertLess(maxStep, 0.02) // < 1.2 degrees per frame
        run(look, Horse(turnRate = 0.0), 3.0)
        assertLess(abs(look.yaw.x), 0.01)
    }

    @Test
    fun `does not snap when the jump phase changes`() {
        val look = createHeadLook()
        var prev = 0.0
        var maxStep = 0.0
        for (i in 0 until 60) {
            val s = i / 60.0
            val jump =
                if (s < 0.2) JumpView(JumpPhase.TAKEOFF, s / 0.2) else JumpView(JumpPhase.FLIGHT, (s - 0.2) / 0.55)
            stepHeadLook(look, DT, Horse(jump = jump), 0.0)
            maxStep = max(maxStep, abs(look.pitch.x - prev))
            prev = look.pitch.x
        }
        assertLess(maxStep, 0.02)
    }
}
