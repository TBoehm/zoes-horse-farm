package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.domain.sim.RefusalType
import app.zoeshorsefarm.domain.sim.RefusalView
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LF = 0
private const val RF = 1
private const val LH = 2
private const val RH = 3

/** A footfall seen by [run]: leg, time and stride phase. */
private class Fall(
    val leg: Int,
    val t: Double,
    val phi: Double,
)

private class Run(
    val m: Motion,
    val events: List<Fall>,
)

private fun horse(
    gait: Gait,
    speed: Double,
    turnRate: Double = 0.0,
) = Horse(gait = gait, speed = speed, turnRate = turnRate)

private fun run(
    gait: Gait,
    speed: Double,
    seconds: Double,
    dt: Double = 1.0 / 240,
    turnRate: Double = 0.0,
    warm: Double = 3.0,
): Run {
    val m = createMotion()
    val state = horse(gait, speed, turnRate)
    // settle (gait cross-fade finished)
    var t = 0.0
    while (t < warm) {
        stepMotion(m, dt, state)
        t += dt
    }
    val events = ArrayList<Fall>()
    var time = 0.0
    while (time < seconds) {
        val falls = stepMotion(m, dt, state)
        for (i in 0 until falls.size) events.add(Fall(falls[i], time, m.phi))
        time += dt
    }
    return Run(m, events)
}

private fun countPerLeg(events: List<Fall>) = (0 until 4).map { leg -> events.count { it.leg == leg } }

/** Groups the events by time (1/100 s) and sorts the legs of every group. */
private fun pairsByTime(events: List<Fall>): List<List<Int>> {
    val byTime = LinkedHashMap<Int, List<Int>>()
    for (e in events) {
        val k = (e.t * 100).roundToInt()
        byTime[k] = ((byTime[k] ?: emptyList()) + e.leg).sorted()
    }
    return byTime.values.filter { it.size == 2 }
}

class FootfallTest {
    @Test
    fun `never fires at halt`() {
        assertEquals(0, run(Gait.HALT, 0.0, 5.0).events.size)
    }

    @Test
    fun `does not fire when turning on the spot at halt`() {
        assertEquals(0, run(Gait.HALT, 0.0, 5.0, turnRate = 1.2).events.size)
    }

    private fun oncePerLegAndCycle(
        gait: Gait,
        speed: Double,
    ) {
        val f = GAITS[gait].freq(speed)
        val cycles = 6
        val r = run(gait, speed, cycles / f - 1e-3)
        for (n in countPerLeg(r.events)) assertTrue(abs(n - cycles) <= 1, "$gait: $n footfalls")
    }

    @Test
    fun `walk exactly once per leg and cycle`() = oncePerLegAndCycle(Gait.WALK, 1.6)

    @Test
    fun `trot exactly once per leg and cycle`() = oncePerLegAndCycle(Gait.TROT, 3.2)

    @Test
    fun `canter exactly once per leg and cycle`() = oncePerLegAndCycle(Gait.CANTER, 6.0)

    @Test
    fun `walk four-beat LH LF RH RF a quarter cycle apart`() {
        val events = run(Gait.WALK, 1.6, 3.0).events
        val seq = events.take(8).map { it.leg }
        val start = seq.indexOf(LH)
        assertEquals(listOf(LH, LF, RH, RF), seq.subList(start, start + 4))
        val f = GAITS.walk.freq(1.6)
        val ev = events.subList(start, start + 4)
        for (i in 1 until 4) assertClose(0.25, (ev[i].t - ev[i - 1].t) * f, 1)
    }

    @Test
    fun `trot diagonal pairs land together`() {
        val pairs = pairsByTime(run(Gait.TROT, 3.2, 2.0).events)
        assertGreater(pairs.size.toDouble(), 3.0)
        for (p in pairs) assertTrue(p == listOf(LF, RH).sorted() || p == listOf(RF, LH).sorted(), "$p")
    }

    @Test
    fun `rein-back diagonal pairs land together once per leg and cycle`() {
        val f = GAITS.back.freq(0.5)
        val cycles = 4
        val events = run(Gait.BACK, -0.5, cycles / f - 1e-3).events
        for (n in countPerLeg(events)) assertTrue(abs(n - cycles) <= 1)
        val pairs = pairsByTime(events)
        assertGreater(pairs.size.toDouble(), 3.0)
        for (p in pairs) assertTrue(p == listOf(LF, RH).sorted() || p == listOf(RF, LH).sorted(), "$p")
    }

    @Test
    fun `rein-back the horse blends back to halt with at most a step or two`() {
        val m = createMotion()
        val st = horse(Gait.BACK, -0.5)
        var t = 0.0
        while (t < 2) {
            stepMotion(m, 1.0 / 120, st)
            t += 1.0 / 120
        }
        assertGreater(m.weights.back, 0.99)
        st.gait = Gait.HALT
        st.speed = 0.0
        var n = 0
        t = 0.0
        while (t < 3) {
            n += stepMotion(m, 1.0 / 120, st).size
            t += 1.0 / 120
        }
        assertGreater(m.weights.halt, 0.99)
        assertLess(m.weights.back, 0.01)
        assertTrue(n <= 2)
    }

    @Test
    fun `canter on the left lead RH then LH and RF then LF then suspension`() {
        val r = run(Gait.CANTER, 6.0, 2.0, turnRate = -0.5)
        assertEquals(1.0, r.m.lead)
        val seq = r.events.map { it.leg }
        val i = seq.indexOf(RH)
        assertEquals(RH, seq[i])
        assertEquals(listOf(RF, LH).sorted(), listOf(seq[i + 1], seq[i + 2]).sorted())
        assertEquals(LF, seq[i + 3])
    }

    @Test
    fun `right turn when striking off gives the right lead LH then RH and LF then RF`() {
        val r = run(Gait.CANTER, 6.0, 2.0, turnRate = 0.6)
        assertEquals(-1.0, r.m.lead)
        val seq = r.events.map { it.leg }
        val i = seq.indexOf(LH)
        assertEquals(listOf(RH, LF).sorted(), listOf(seq[i + 1], seq[i + 2]).sorted())
        assertEquals(RF, seq[i + 3])
    }

    @Test
    fun `does not fire during a jump the landing has its own sound`() {
        val m = createMotion()
        val st = horse(Gait.CANTER, 6.0)
        var t = 0.0
        while (t < 2) {
            stepMotion(m, 1.0 / 120, st)
            t += 1.0 / 120
        }
        st.jump = JumpView(JumpPhase.FLIGHT, 0.5)
        var n = 0
        t = 0.0
        while (t < 0.6) {
            if (t > 0.15) n += stepMotion(m, 1.0 / 120, st).size else stepMotion(m, 1.0 / 120, st)
            t += 1.0 / 120
        }
        assertEquals(0, n)
    }
}

class MotionBlendingTest {
    @Test
    fun `weights sum to 1 and follow the gait smoothly`() {
        val m = createMotion()
        val st = horse(Gait.TROT, 3.0)
        stepMotion(m, 1.0 / 60, st)
        val w = m.weights
        assertClose(1.0, w.halt + w.walk + w.trot + w.canter, 6)
        assertGreater(w.trot, 0.0)
        assertLess(w.trot, 0.5)
        for (i in 0 until 120) stepMotion(m, 1.0 / 60, st)
        assertGreater(m.weights.trot, 0.99)
    }

    @Test
    fun `hooves do not slide during stance hoof velocity is minus speed relative to the body`() {
        for ((gait, v) in listOf(Gait.WALK to 1.4, Gait.TROT to 3.0, Gait.CANTER to 6.0)) {
            val m = createMotion()
            val st = horse(gait, v)
            var t = 0.0
            while (t < 3) {
                stepMotion(m, 1.0 / 240, st)
                t += 1.0 / 240
            }
            val dt = 1.0 / 240
            var checked = 0
            for (i in 0 until 240) {
                val beforeY = m.legs.map { it.y }
                val beforeDz = m.legs.map { it.dz }
                stepMotion(m, dt, st)
                m.legs.forEachIndexed { k, l ->
                    if (l.y == 0.0 && beforeY[k] == 0.0) {
                        assertClose(-v, (l.dz - beforeDz[k]) / dt, 0)
                        checked++
                    }
                }
            }
            assertGreater(checked.toDouble(), 50.0)
        }
    }

    @Test
    fun `rein-back hooves do not slide during stance they move forward relative to the body`() {
        val m = createMotion()
        val st = horse(Gait.BACK, -0.5)
        var t = 0.0
        while (t < 3) {
            stepMotion(m, 1.0 / 240, st)
            t += 1.0 / 240
        }
        val dt = 1.0 / 240
        var checked = 0
        for (i in 0 until 480) {
            val beforeY = m.legs.map { it.y }
            val beforeDz = m.legs.map { it.dz }
            stepMotion(m, dt, st)
            m.legs.forEachIndexed { k, l ->
                if (l.y == 0.0 && beforeY[k] == 0.0) {
                    assertClose(0.5, (l.dz - beforeDz[k]) / dt, 0)
                    checked++
                }
            }
        }
        assertGreater(checked.toDouble(), 50.0)
    }

    @Test
    fun `jump the pose weight follows the jump progress in during the take-off out during the landing`() {
        val m = createMotion()
        val st = horse(Gait.CANTER, 6.0)
        var t = 0.0
        while (t < 1) {
            stepMotion(m, 1.0 / 60, st)
            t += 1.0 / 60
        }
        assertEquals(0.0, m.jumpWeight)
        val weights = ArrayList<Double>()
        for ((phase, frames) in listOf(JumpPhase.TAKEOFF to 14, JumpPhase.FLIGHT to 36, JumpPhase.LANDING to 15)) {
            for (i in 0 until frames) {
                st.jump = JumpView(phase, (i + 0.5) / frames)
                stepMotion(m, 1.0 / 60, st)
                weights.add(m.jumpWeight)
            }
        }
        assertLess(weights[0], 0.1)
        assertGreater(weights[20], 0.99)
        assertClose(1.0, weights[40], 6)
        assertLess(weights.last(), 0.05)
        // never a snap: at most a few percent per frame, except for the slew limit of the quick take-off
        for (i in 1 until weights.size) assertLess(abs(weights[i] - weights[i - 1]), 0.24)
        st.jump = null
        for (i in 0 until 30) stepMotion(m, 1.0 / 60, st)
        assertEquals(0.0, m.jumpWeight)
        assertEquals(0.0, m.jumpJ)
    }

    @Test
    fun `jump that is interrupted fades out instead of snapping`() {
        val m = createMotion()
        val st = horse(Gait.CANTER, 6.0)
        var t = 0.0
        while (t < 1) {
            stepMotion(m, 1.0 / 60, st)
            t += 1.0 / 60
        }
        st.jump = JumpView(JumpPhase.FLIGHT, 0.5)
        for (i in 0 until 30) stepMotion(m, 1.0 / 60, st)
        assertClose(1.0, m.jumpWeight, 6)
        st.jump = null
        stepMotion(m, 1.0 / 60, st)
        assertGreater(m.jumpWeight, 0.8)
        for (i in 0 until 90) stepMotion(m, 1.0 / 60, st)
        assertEquals(0.0, m.jumpWeight)
    }

    @Test
    fun `refusal stop weight rises and falls with progress`() {
        val m = createMotion()
        val st = horse(Gait.TROT, 2.0)
        st.refusal = RefusalView(RefusalType.STOP, 0.4)
        for (i in 0 until 30) stepMotion(m, 1.0 / 60, st)
        assertGreater(m.stopWeight, 0.9)
        st.refusal = RefusalView(RefusalType.STOP, 1.0)
        for (i in 0 until 30) stepMotion(m, 1.0 / 60, st)
        assertLess(m.stopWeight, 0.1)
    }
}

class TurnLeanAndBendTest {
    @Test
    fun `lean follows the centripetal acceleration and stays within 0 point 3 rad`() {
        for ((v, w) in listOf(1.5 to 2.16, 3.2 to 1.76, 5.8 to 1.37, 8.0 to 1.16, 0.0 to 2.7)) {
            assertTrue(abs(turnLean(v, w)) <= 0.3)
        }
    }

    @Test
    fun `lean is not saturated at the usual gaits so it still grows with speed`() {
        val walk = turnLean(1.5, 2.16)
        val trot = turnLean(3.2, 1.76)
        val canter = turnLean(5.8, 1.37)
        assertGreater(trot, walk)
        assertGreater(canter, trot)
        assertLess(canter, 0.3)
    }

    @Test
    fun `leans into the turn a right turn leans right a left turn leans left`() {
        assertGreater(turnLean(5.0, 1.0), 0.0)
        assertLess(turnLean(5.0, -1.0), 0.0)
        assertEquals(0.0, turnLean(5.0, 0.0))
    }

    @Test
    fun `bend stays within 0 point 35 rad and grows with the turn rate without saturating at canter`() {
        assertTrue(abs(turnBend(2.7)) <= 0.35)
        assertTrue(abs(turnBend(-2.7)) <= 0.35)
        assertLess(abs(turnBend(1.37)), 0.3)
        assertGreater(abs(turnBend(1.76)), abs(turnBend(1.37)))
    }

    @Test
    fun `bends towards the inside a right turn bends to the right`() {
        assertLess(turnBend(1.0), 0.0)
        assertGreater(turnBend(-1.0), 0.0)
    }
}
