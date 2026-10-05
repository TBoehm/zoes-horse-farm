package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DT = 1.0 / 60

class BreathingTest {
    @Test
    fun `moves gently and periodically`() {
        val values = ArrayList<Double>()
        var t = 0.0
        while (t < 8) {
            values.add(breathing(t, 1.0).chest)
            t += 0.05
        }
        assertGreater(values.max(), 0.01)
        assertLess(values.max(), 0.03)
        assertLess(values.min(), -0.01)
    }

    @Test
    fun `is calmer in motion with no weight shift and a smaller breath`() {
        var calm = 0.0
        var busy = 0.0
        var busyRoll = 0.0
        var t = 0.0
        while (t < 10) {
            calm = max(calm, abs(breathing(t, 1.0).chest))
            busy = max(busy, abs(breathing(t, 0.0).chest))
            busyRoll = max(busyRoll, abs(breathing(t, 0.0).roll))
            t += 0.05
        }
        assertLess(busy, calm)
        assertEquals(0.0, busyRoll)
    }
}

class StepPatTest {
    private fun run(
        p: Pat,
        seconds: Double,
        jumping: Boolean = false,
        halted: Boolean = false,
    ): Double {
        var peak = 0.0
        for (i in 0 until (seconds / DT).roundToInt()) {
            stepPat(p, DT, jumping, halted)
            peak = max(peak, p.reach)
        }
        return peak
    }

    @Test
    fun `does nothing without a jump`() {
        val p = createPat()
        assertEquals(0.0, run(p, 5.0, halted = true))
    }

    @Test
    fun `pats once when the horse stands after a jump then stops`() {
        val p = createPat()
        run(p, 0.5, jumping = true)
        assertGreater(run(p, 3.0, halted = true), 0.9)
        run(p, 0.5, halted = true)
        assertLess(p.reach, 0.01)
        assertLess(run(p, 3.0, halted = true), 1e-6)
    }

    @Test
    fun `does not pat when the horse keeps going or stops long after the jump`() {
        val p = createPat()
        run(p, 0.5, jumping = true)
        assertEquals(0.0, run(p, 12.0))
        assertLess(run(p, 3.0, halted = true), 1e-6)
    }

    @Test
    fun `takes the hand back quickly and smoothly when the ride continues`() {
        val p = createPat()
        run(p, 0.2, jumping = true)
        run(p, 0.6, halted = true)
        assertGreater(p.reach, 0.5)
        var prev = p.reach
        var maxStep = 0.0
        for (i in 0 until 30) {
            stepPat(p, DT)
            maxStep = max(maxStep, abs(p.reach - prev))
            prev = p.reach
        }
        assertLess(maxStep, 0.12) // no jump
        assertLess(p.reach, 0.05) // but gone within half a second
    }

    @Test
    fun `reach rises and falls smoothly`() {
        val p = createPat()
        run(p, 0.2, jumping = true)
        var prev = 0.0
        var maxStep = 0.0
        for (i in 0 until 150) {
            stepPat(p, DT, halted = true)
            maxStep = max(maxStep, abs(p.reach - prev))
            prev = p.reach
        }
        assertLess(maxStep, 0.1)
    }
}
