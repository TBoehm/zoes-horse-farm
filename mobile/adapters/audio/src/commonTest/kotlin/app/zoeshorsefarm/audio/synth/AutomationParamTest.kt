package app.zoeshorsefarm.audio.synth

import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

// Sample rate 1000: sample k is at t = k / 1000 seconds.
class AutomationParamTest {
    private fun param(initial: Double = 0.0) = AutomationParam(1000.0, initial)

    private fun AutomationParam.valuesAt(vararg seconds: Double): List<Double> {
        val out = ArrayList<Double>()
        for (s in seconds.sorted()) {
            val target = (s * 1000).toLong()
            while (position < target) next()
            out += next()
        }
        return out
    }

    @Test
    fun holdsTheInitialValueWithoutEvents() {
        val p = param(0.7)
        repeat(10) { assertEquals(0.7, p.next(), 1e-12) }
    }

    @Test
    fun setValueAtTimeStepsAtTheEventTime() {
        val p = param(1.0)
        p.setValueAtTime(0.25, 0.010)
        p.setValueAtTime(0.5, 0.020)
        val v = p.valuesAt(0.009, 0.010, 0.019, 0.020, 0.100)
        assertEquals(listOf(1.0, 0.25, 0.25, 0.5, 0.5), v)
    }

    @Test
    fun linearRampInterpolatesBetweenTheEvents() {
        val p = param()
        p.setValueAtTime(1.0, 0.0)
        p.linearRampToValueAtTime(3.0, 0.100)
        val v = p.valuesAt(0.0, 0.025, 0.050, 0.075, 0.100, 0.200)
        assertEquals(listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.0), v.map { it.round6() })
    }

    @Test
    fun exponentialRampFollowsAGeometricProgression() {
        val p = param()
        p.setValueAtTime(0.01, 0.0)
        p.exponentialRampToValueAtTime(1.0, 0.100)
        val v = p.valuesAt(0.0, 0.025, 0.050, 0.100, 0.150)
        assertEquals(0.01, v[0], 1e-9)
        assertEquals(0.01 * 100.0.pow(0.25), v[1], 1e-7)
        assertEquals(0.1, v[2], 1e-7)
        assertEquals(1.0, v[3], 1e-9)
        assertEquals(1.0, v[4], 1e-12)
    }

    @Test
    fun exponentialRampFromZeroHoldsTheStartValueUntilTheEnd() {
        val p = param()
        p.setValueAtTime(0.0, 0.0)
        p.exponentialRampToValueAtTime(1.0, 0.050)
        val v = p.valuesAt(0.010, 0.049, 0.050)
        assertEquals(listOf(0.0, 0.0, 1.0), v)
    }

    @Test
    fun rampsChainThroughSeveralEvents() {
        // the envelope shape of a tone: silent start, fast attack, hold, exponential decay
        val p = param()
        p.setValueAtTime(0.0001, 0.0)
        p.exponentialRampToValueAtTime(0.5, 0.010)
        p.setValueAtTime(0.5, 0.030)
        p.exponentialRampToValueAtTime(0.0001, 0.130)
        val v = p.valuesAt(0.0, 0.010, 0.020, 0.030, 0.080, 0.130, 0.200)
        assertEquals(0.0001, v[0], 1e-9)
        assertEquals(0.5, v[1], 1e-9)
        assertEquals(0.5, v[2], 1e-9)
        assertEquals(0.5, v[3], 1e-9)
        assertEquals(0.5 * (0.0001 / 0.5).pow(0.5), v[4], 1e-7)
        assertEquals(0.0001, v[5], 1e-9)
        assertEquals(0.0001, v[6], 1e-12)
    }

    @Test
    fun setTargetApproachesTheTargetExponentially() {
        val p = param(1.0)
        p.setTargetAtTime(0.0, 0.010, 0.020)
        val v = p.valuesAt(0.005, 0.010, 0.030, 0.070)
        assertEquals(1.0, v[0], 1e-12)
        assertEquals(1.0, v[1], 1e-9)
        assertEquals(exp(-1.0), v[2], 1e-6)
        assertEquals(exp(-3.0), v[3], 1e-6)
    }

    @Test
    fun aSecondSetTargetStartsFromTheValueOfTheFirstCurve() {
        val p = param(0.0)
        p.setTargetAtTime(1.0, 0.0, 0.010)
        p.setTargetAtTime(0.0, 0.010, 0.010)
        val v = p.valuesAt(0.010, 0.020)
        val atSwitch = 1 - exp(-1.0)
        assertEquals(atSwitch, v[0], 1e-6)
        assertEquals(atSwitch * exp(-1.0), v[1], 1e-6)
    }

    @Test
    fun aRampWithoutPrecedingEventStartsAtTheCurrentValue() {
        val p = param(2.0)
        repeat(10) { p.next() } // now t = 0.010
        p.linearRampToValueAtTime(4.0, 0.110)
        val v = p.valuesAt(0.010, 0.060, 0.110)
        assertEquals(listOf(2.0, 3.0, 4.0), v.map { it.round6() })
    }

    @Test
    fun cancelAndHoldFreezesTheValueOfARunningRamp() {
        val p = param()
        p.setValueAtTime(0.0, 0.0)
        p.linearRampToValueAtTime(10.0, 0.100)
        repeat(40) { p.next() } // t = 0.040
        p.cancelAndHoldAtTime(0.040)
        assertEquals(4.0, p.next(), 1e-6)
        repeat(100) { assertEquals(4.0, p.next(), 1e-6) }
    }

    @Test
    fun cancelAndHoldFreezesTheValueOfARunningTargetCurve() {
        val p = param(1.0)
        p.setTargetAtTime(0.0, 0.0, 0.010)
        repeat(10) { p.next() }
        p.cancelAndHoldAtTime(0.010)
        val held = p.next()
        assertEquals(exp(-1.0), held, 1e-6)
        repeat(50) { assertEquals(held, p.next(), 1e-12) }
    }

    @Test
    fun cancelAndHoldThenSetTargetFadesFromTheHeldValue() {
        val p = param()
        p.setValueAtTime(0.0, 0.0)
        p.setTargetAtTime(1.0, 0.0, 0.010)
        repeat(10) { p.next() }
        p.cancelAndHoldAtTime(0.010)
        p.setTargetAtTime(0.0, 0.010, 0.010)
        val start = 1 - exp(-1.0)
        val v = p.valuesAt(0.010, 0.020)
        assertEquals(start, v[0], 1e-6)
        assertEquals(start * exp(-1.0), v[1], 1e-6)
    }

    @Test
    fun eventsAreOrderedByTimeAndKeepInsertionOrderForEqualTimes() {
        val p = param()
        p.setValueAtTime(3.0, 0.030)
        p.setValueAtTime(1.0, 0.010)
        p.setValueAtTime(2.0, 0.010)
        p.setValueAtTime(9.0, 0.020)
        val v = p.valuesAt(0.010, 0.020, 0.030)
        assertEquals(listOf(2.0, 9.0, 3.0), v)
    }

    @Test
    fun resetRestartsTheParameterAtAGivenFrame() {
        val p = param(1.0)
        p.setValueAtTime(5.0, 0.0)
        repeat(5) { p.next() }
        p.reset(frame = 100, value = 0.5)
        p.setValueAtTime(2.0, 0.102)
        assertEquals(listOf(0.5, 0.5, 2.0), listOf(p.next(), p.next(), p.next()))
    }

    @Test
    fun growsBeyondItsInitialCapacityAndKeepsTheOrder() {
        val p = AutomationParam(1000.0, 0.0, capacity = 2)
        for (i in 1..20) p.setValueAtTime(i.toDouble(), i * 0.001)
        repeat(5) { p.next() }
        assertEquals(5.0, p.next(), 0.0)
        repeat(13) { p.next() }
        assertEquals(19.0, p.next(), 0.0)
        assertEquals(20.0, p.next(), 0.0)
    }

    private fun Double.round6() = kotlin.math.round(this * 1e6) / 1e6
}
