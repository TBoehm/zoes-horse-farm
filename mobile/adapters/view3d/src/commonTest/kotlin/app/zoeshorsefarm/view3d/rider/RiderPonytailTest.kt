package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertFinite
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun rest() = PonytailInput()

private fun Ponytail.total(axis: String) = (if (axis == "pitch") pitch else yaw).sumOf { it.x }

private fun run(
    p: Ponytail,
    input: PonytailInput,
    seconds: Double,
    dt: Double = 1.0 / 60,
): Ponytail {
    for (i in 0 until (seconds / dt).roundToInt()) stepPonytail(p, input, dt)
    return p
}

class PonytailTargetTest {
    @Test
    fun `hangs straight at rest`() {
        val t = ponytailTarget(rest())
        assertClose(0.0, t.pitch, 9)
        assertClose(0.0, t.yaw, 9)
    }

    @Test
    fun `trails downwards when the head accelerates upwards and lifts when it falls`() {
        assertLess(ponytailTarget(PonytailInput(ay = 12.0)).pitch, -0.05)
        assertGreater(ponytailTarget(PonytailInput(ay = -12.0)).pitch, 0.05)
    }

    @Test
    fun `streams backwards when moving forward and not when moving backwards`() {
        assertGreater(ponytailTarget(PonytailInput(vz = 5.0)).pitch, 0.1)
        assertClose(0.0, ponytailTarget(PonytailInput(vz = -5.0)).pitch, 9)
    }

    @Test
    fun `swings to the opposite side of a sideways motion`() {
        assertGreater(ponytailTarget(PonytailInput(ax = 8.0)).yaw, 0.05)
        assertLess(ponytailTarget(PonytailInput(ax = -8.0)).yaw, -0.05)
    }

    @Test
    fun `keeps hanging down when the head pitches forward`() {
        val pitched = PI / 4
        val t = ponytailTarget(PonytailInput(gy = -cos(pitched), gz = sin(pitched)))
        assertClose(-0.7 * pitched, t.pitch, 6)
    }

    @Test
    fun `clamps absurd inputs such as a teleport or a frame hitch`() {
        val t = ponytailTarget(PonytailInput(ay = 1e6, az = 1e6, ax = 1e6, vz = 1e6, vx = 1e6))
        assertLessOrEqual(abs(t.pitch), 1.0)
        assertLessOrEqual(abs(t.yaw), 1.0)
    }
}

class StepPonytailTest {
    @Test
    fun `has one spring per segment and starts at rest`() {
        val p = createPonytail()
        assertEquals(PONY_SEGMENTS, p.pitch.size)
        assertEquals(0.0, p.total("pitch"))
    }

    @Test
    fun `settles on the target and stays there damped with no endless wobble`() {
        val p = run(createPonytail(), PonytailInput(vz = 4.0), 5.0)
        val want = ponytailTarget(PonytailInput(vz = 4.0)).pitch
        assertClose(want, p.total("pitch"), 3)
        assertTrue(p.pitch.all { abs(it.v) < 1e-3 })
    }

    @Test
    fun `the tip lags behind the root of the chain`() {
        val p = createPonytail()
        run(p, PonytailInput(ay = 15.0), 0.06)
        val want = ponytailTarget(PonytailInput(ay = 15.0)).pitch

        // after 60 ms the root segment has covered more of its way than the last one
        fun frac(i: Int) = p.pitch[i].x / (want * 0.35)
        assertGreater(abs(frac(0)), abs(frac(PONY_SEGMENTS - 1)) * 0.9)
        assertLess(abs(p.pitch[PONY_SEGMENTS - 1].x), abs(want) * 0.35)
    }

    @Test
    fun `overshoots a little on a sudden stop and swings back`() {
        val p = createPonytail()
        run(p, PonytailInput(ax = 10.0), 1.0)
        val peak = p.total("yaw")
        run(p, rest(), 0.2)
        assertLess(p.total("yaw"), peak)
        var lowest = 0.0
        for (i in 0 until 90) {
            stepPonytail(p, rest(), 1.0 / 60)
            lowest = min(lowest, p.total("yaw"))
        }
        assertLess(lowest, 0.0) // swung past the rest position
        assertGreater(lowest, -peak * 0.5)
    }

    @Test
    fun `is stable at a large time step`() {
        val p = createPonytail()
        for (i in 0 until 10) stepPonytail(p, PonytailInput(ay = 30.0, vz = 9.0), 0.5)
        assertFinite(p.total("pitch"))
        assertLessOrEqual(abs(p.total("pitch")), 1.01)
    }

    @Test
    fun `gives the same result at 30 and 120 fps`() {
        val a = run(createPonytail(), PonytailInput(ax = 6.0), 0.5, 1.0 / 30)
        val b = run(createPonytail(), PonytailInput(ax = 6.0), 0.5, 1.0 / 120)
        assertClose(a.total("yaw"), b.total("yaw"), 6)
    }
}
