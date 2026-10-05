package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class JointLimitTest {
    private val dt = 1.0 / 60
    private val speed = 20.0 // rad/s (0.33 rad per frame)
    private val accel = 900.0 // rad/s^2 (0.25 rad per frame and frame)

    /** Runs the limiter over [targets]; returns the output angles. */
    private fun run(
        targets: List<Double>,
        step: Double = dt,
        maxSpeed: Double = speed,
        maxAccel: Double = accel,
    ): List<Double> {
        val s = createJointLimiter()
        return targets.map { limitJoint(s, it, step, maxSpeed, maxAccel) }
    }

    @Test
    fun `takes the first target as it is`() {
        assertEquals(1.5, run(listOf(1.5))[0])
    }

    @Test
    fun `leaves smooth motion untouched without lag`() {
        val targets = (0 until 120).map { 0.8 * kotlin.math.sin(it * 0.08) }
        val out = run(targets)
        out.forEachIndexed { i, x -> assertClose(targets[i], x, 9) }
    }

    @Test
    fun `spreads a one-frame V-kink so that the velocity changes by at most the limit`() {
        // 0.2 rad per frame, then at once 0.2 back: a change of the velocity by 0.4 rad per frame
        var x = 0.0
        val targets = ArrayList<Double>()
        for (i in 0 until 40) {
            x += if (i < 20) 0.2 else -0.2
            targets.add(x)
        }
        val out = run(targets)
        var worst = 0.0
        for (i in 2 until out.size) worst = maxOf(worst, abs(out[i] - 2 * out[i - 1] + out[i - 2]))
        assertLessOrEqual(worst, accel * dt * dt + 1e-9)
        // the second difference of the raw signal was 0.4
        assertGreater(abs(targets[20] - 2 * targets[19] + targets[18]), 0.35)
        // and it catches up with the target afterwards
        assertLess(abs(out.last() - targets.last()), 0.02)
    }

    @Test
    fun `never turns faster than the speed limit`() {
        val targets = listOf(0.0, 0.0, 0.0) + List(22) { 3.0 }
        val out = run(targets)
        for (i in 1 until out.size) assertLessOrEqual(abs(out[i] - out[i - 1]), speed * dt + 1e-9)
        assertClose(3.0, out.last(), 6)
    }

    @Test
    fun `settles on a step without overshooting by more than a hair`() {
        val out = run(listOf(0.0) + List(60) { 1.0 })
        assertLess(out.max(), 1.02)
        assertClose(1.0, out.last(), 4)
    }

    @Test
    fun `holds the angle for a step of no time and ignores bad time steps`() {
        val s = createJointLimiter()
        limitJoint(s, 0.4, dt, speed, accel)
        assertEquals(0.4, limitJoint(s, 2.0, 0.0, speed, accel))
        assertEquals(0.4, limitJoint(s, 2.0, Double.NaN, speed, accel))
    }

    @Test
    fun `limits per second so that a longer frame may turn further`() {
        val slow = run(listOf(0.0, 5.0, 5.0, 5.0), 1.0 / 30)
        val fast = run(listOf(0.0, 5.0, 5.0, 5.0), 1.0 / 120)
        assertGreater(slow[1], fast[1])
    }

    @Test
    fun `snaps to a value without velocity`() {
        val s = createJointLimiter()
        limitJoint(s, 0.0, dt, speed, accel)
        limitJoint(s, 1.0, dt, speed, accel)
        snapJoint(s, 0.3)
        assertEquals(0.0, s.v)
        assertClose(0.3, limitJoint(s, 0.3, dt, speed, accel), 9)
    }
}
