package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertGreaterOrEqual
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GaitsTest {
    @Test
    fun `walk 55 trot 80 canter 100 per minute at typical speed`() {
        assertClose(55.0, GAITS.walk.freq(1.6) * 60, -1)
        assertClose(80.0, GAITS.trot.freq(3.2) * 60, -1)
        assertClose(100.0, GAITS.canter.freq(6.0) * 60, -1)
    }

    @Test
    fun `canter stride is about 3 point 7 m at 6 m per s and speed rises mainly through stride length`() {
        fun stride(v: Double) = v / GAITS.canter.freq(v)
        assertGreater(stride(6.0), 3.4)
        assertLess(stride(6.0), 3.9)
        val fRatio = GAITS.canter.freq(8.0) / GAITS.canter.freq(5.0)
        val sRatio = stride(8.0) / stride(5.0)
        assertGreater(sRatio, fRatio)
    }

    @Test
    fun `duty factor walk about 0 point 6 trot 0 point 35 to 0 point 45 canter 0 point 3 to 0 point 4`() {
        assertGreater(GAITS.walk.duty(1.6), 0.5)
        for (v in listOf(2.0, 3.0, 4.0)) {
            assertGreaterOrEqual(GAITS.trot.duty(v), 0.35)
            assertLessOrEqual(GAITS.trot.duty(v), 0.45)
        }
        for (v in listOf(4.5, 6.0, 8.0)) {
            assertGreaterOrEqual(GAITS.canter.duty(v), 0.3)
            assertLessOrEqual(GAITS.canter.duty(v), 0.4)
        }
    }

    @Test
    fun `rein-back is a slow two-beat diagonal gait with the same diagonal pairs as the trot`() {
        val o = offsetsFor(Gait.BACK, 1.0)
        // LF+RH together, RF+LH together, half a cycle apart
        assertEquals(o[0], o[3])
        assertEquals(o[1], o[2])
        assertClose(0.5, abs(o[0] - o[1]), 9)
        // calm cadence: well below the trot at the full backing speed
        val v = TUNING.reinBack.maxSpeed
        assertLess(GAITS.back.freq(v), GAITS.trot.freq(3.2))
        assertGreater(GAITS.back.freq(v), 0.5)
        assertGreater(GAITS.back.freq(0.05), 0.3)
        // no suspension phase
        assertGreaterOrEqual(GAITS.back.duty(v), 0.5)
    }

    @Test
    fun `rein-back stance hoof travels forward relative to the body reverse of the trot`() {
        val v = TUNING.reinBack.maxSpeed
        val f = GAITS.back.freq(v)
        val d = GAITS.back.duty(v)
        val early = legSample(Gait.BACK, 0, 0.05, v, f)
        val earlyDz = early.dz
        val earlyStance = early.stance
        val late = legSample(Gait.BACK, 0, d - 0.05, v, f)
        assertTrue(earlyStance && late.stance)
        assertGreater(late.dz, earlyDz)
        val tf = GAITS.trot.freq(3.0)
        val td = GAITS.trot.duty(3.0)
        assertLess(legSample(Gait.TROT, 0, td - 0.02, 3.0, tf).dz, legSample(Gait.TROT, 0, 0.02, 3.0, tf).dz)
    }

    @Test
    fun `rein-back swing hoof is lifted and the path is continuous at the phase boundaries`() {
        val v = TUNING.reinBack.maxSpeed
        val f = GAITS.back.freq(v)
        val d = GAITS.back.duty(v)
        val off = offsetsFor(Gait.BACK, 1.0)[0]
        assertGreater(legSample(Gait.BACK, 0, (off + d + (1 - d) / 2) % 1, v, f).y, 0.05)
        for (p in listOf(d, 1.0)) {
            val a = legSample(Gait.BACK, 0, (off + p - 1e-6) % 1, v, f).dz
            val b = legSample(Gait.BACK, 0, (off + p + 1e-6) % 1, v, f).dz
            assertClose(a, b, 3)
        }
    }

    @Test
    fun `right lead is the mirrored left lead`() {
        val l = offsetsFor(Gait.CANTER, 1.0)
        val r = offsetsFor(Gait.CANTER, -1.0)
        assertEquals(listOf(l[1], l[0], l[3], l[2]), r.toList())
    }

    @Test
    fun `stance on the ground swing lifted`() {
        val f = GAITS.trot.freq(3.0)
        val d = GAITS.trot.duty(3.0)
        val stance = legSample(Gait.TROT, 0, d / 2, 3.0, f)
        assertTrue(stance.stance)
        assertEquals(0.0, stance.y)
        val swing = legSample(Gait.TROT, 0, d + (1 - d) / 2, 3.0, f)
        assertEquals(false, swing.stance)
        assertGreater(swing.y, 0.1)
        assertGreater(swing.flex, 0.5)
    }

    @Test
    fun `hoof path is continuous at the phase boundaries`() {
        val v = 6.0
        val f = GAITS.canter.freq(v)
        val d = GAITS.canter.duty(v)
        val off = offsetsFor(Gait.CANTER, 1.0)[0]
        for (p in listOf(d, 1.0)) {
            val a = legSample(Gait.CANTER, 0, (off + p - 1e-6) % 1, v, f)
            val aDz = a.dz
            val aY = a.y
            val b = legSample(Gait.CANTER, 0, (off + p + 1e-6) % 1, v, f)
            assertClose(aDz, b.dz, 3)
            assertClose(aY, b.y, 3)
        }
    }

    @Test
    fun `hoof travel per stance is limited to avoid overextension`() {
        val f = GAITS.canter.freq(8.0)
        val d = GAITS.canter.duty(8.0)
        val a = legSample(Gait.CANTER, 3, 0.0, 8.0, f).dz
        val b = legSample(Gait.CANTER, 3, d - 1e-6, 8.0, f).dz
        assertLessOrEqual(a - b, MAX_STANCE_TRAVEL + 1e-6)
    }

    @Test
    fun `legPhase stays in 0 to 1`() {
        assertClose(0.35, legPhase(0.1, 0.75), 2)
        assertClose(0.65, legPhase(0.9, 0.25), 2)
    }

    private fun slope(u: Double) = (swingEnds(u + 1e-6) - swingEnds(u - 1e-6)) / 2e-6

    @Test
    fun `swingEnds is zero at both ends and has the slope 1 there`() {
        assertEquals(0.0, swingEnds(0.0))
        assertEquals(0.0, swingEnds(1.0))
        assertClose(1.0, slope(1e-5), 3)
        assertClose(1.0, slope(1 - 1e-5), 3)
    }

    @Test
    fun `swingEnds hardly moves the hoof in the middle of the swing`() {
        var u = 0.3
        while (u <= 0.7) {
            assertLess(abs(swingEnds(u)), 0.04)
            u += 0.05
        }
    }

    @Test
    fun `swingEnds moves the hoof back only a little behind the lift-off point`() {
        // a unit stride with the hoof speed of a canter (-2.4 per unit of u): the dip is a few percent
        var min = 0.0
        var u = 0.0
        while (u <= 1) {
            min = min(min, swingTravel(u) - 2.4 * 0.3 * swingEnds(u))
            u += 0.01
        }
        assertGreater(min, -0.2)
    }
}
