package app.zoeshorsefarm.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpringTest {
    /** Same rule as vitest's toBeCloseTo: |expected - actual| < 10^-digits / 2. */
    private fun assertClose(
        expected: Double,
        actual: Double,
        digits: Int,
    ) {
        var tolerance = 0.5
        repeat(digits) { tolerance /= 10 }
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    @Test
    fun settlesToTheTargetForEveryDampingRegime() {
        for (zeta in listOf(0.2, 0.7, 1.0, 1.5, 4.0)) {
            val s = createSpring(1.0, 0.0)
            repeat(600) { stepSpring(s, 0.25, 12.0, zeta, 1.0 / 60) }
            assertClose(0.25, s.x, 4)
            assertTrue(abs(s.v) < 1e-3)
        }
    }

    @Test
    fun criticalDampingDoesNotOvershoot() {
        val s = createSpring(0.0, 0.0)
        var peak = 0.0
        repeat(300) {
            stepSpring(s, 1.0, 10.0, 1.0, 1.0 / 60)
            peak = max(peak, s.x)
        }
        assertTrue(peak <= 1 + 1e-9)
    }

    @Test
    fun underDampedSpringOvershootsAndRings() {
        val s = createSpring(0.0, 0.0)
        var peak = 0.0
        repeat(300) {
            stepSpring(s, 1.0, 10.0, 0.3, 1.0 / 60)
            peak = max(peak, s.x)
        }
        assertTrue(peak > 1.2)
    }

    @Test
    fun isExactOneBigStepEqualsManySmallStepsConstantTarget() {
        for (zeta in listOf(0.3, 1.0, 2.0)) {
            val a = createSpring(0.5, 2.0)
            val b = createSpring(0.5, 2.0)
            stepSpring(a, 0.0, 9.0, zeta, 0.2)
            repeat(20) { stepSpring(b, 0.0, 9.0, zeta, 0.01) }
            assertClose(a.x, b.x, 6)
            assertClose(a.v, b.v, 6)
        }
    }

    @Test
    fun doesNotBlowUpAtHugeStepsStiffSpringsOrOddInput() {
        val s = createSpring(1.0, 1.0)
        for (dt in listOf(0.5, 5.0, 1e3, 1e-9, 0.0, -1.0)) {
            stepSpring(s, 0.0, 500.0, 0.1, dt)
            assertTrue(s.x.isFinite())
            assertTrue(s.v.isFinite())
            assertTrue(abs(s.x) < 60)
        }
    }

    @Test
    fun givesAFrameOfAWholeSecondTheSameCareStableAndItSettles() {
        for (zeta in listOf(0.2, 1.0, 3.0)) {
            val s = createSpring(0.0)
            repeat(8) { stepSpring(s, 2.0, 30.0, zeta, 1.5) }
            assertTrue(s.x.isFinite())
            assertClose(2.0, s.x, 3)
        }
    }

    @Test
    fun ignoresAZeroOrNegativeTimeStepAndKeepsAStillSpringStill() {
        val s = createSpring(0.3)
        assertSame(s, stepSpring(s, 5.0, 10.0, 1.0, 0.0))
        assertSame(s, stepSpring(s, 5.0, 10.0, 1.0, -1.0))
        assertEquals(0.3, s.x)
        stepSpring(s, 0.3, 10.0, 1.0, 0.5)
        assertClose(0.3, s.x, 9)
    }

    @Test
    fun isIndependentOfTheFrameRate() {
        val a = createSpring(0.0)
        val b = createSpring(0.0)
        repeat(120) { stepSpring(a, 1.0, 10.0, 0.5, 1.0 / 120) }
        repeat(30) { stepSpring(b, 1.0, 10.0, 0.5, 1.0 / 30) }
        assertClose(a.x, b.x, 6)
        assertClose(a.v, b.v, 5)
    }

    @Test
    fun snapSpringJumpsWithoutVelocity() {
        val s = createSpring(0.0)
        stepSpring(s, 1.0, 10.0, 1.0, 0.05)
        snapSpring(s, 4.0)
        assertEquals(Spring(4.0, 0.0), s)
    }
}
