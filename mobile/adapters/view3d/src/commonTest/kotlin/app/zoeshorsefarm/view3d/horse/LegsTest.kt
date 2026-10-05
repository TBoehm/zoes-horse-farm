package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.domain.testing.FRAME
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LF = 0
private const val RF = 1
private const val LH = 2
private const val RH = 3

private class Settled(
    val m: Motion,
    val state: Horse,
)

private fun settled(
    gait: Gait,
    speed: Double,
    turnRate: Double = 0.0,
): Settled {
    val m = createMotion()
    val state = Horse(gait = gait, speed = speed, turnRate = turnRate)
    for (i in 0 until 240) stepMotion(m, FRAME, state)
    return Settled(m, state)
}

private class Prev(
    var z: Double,
    var planted: Boolean,
)

class PlantedHoovesTest {
    @Test
    fun `a hoof on the ground keeps its place whatever the speed does`() {
        val m = createMotion()
        val state = Horse(gait = Gait.CANTER, speed = 6.0)
        for (i in 0 until 120) stepMotion(m, FRAME, state)
        var world = 0.0
        val prev = arrayOfNulls<Prev>(4)
        var checked = 0
        for (i in 0 until 360) {
            // the speed wobbles between 4.6 and 7.4 m/s
            state.speed = 6 + 1.4 * sin(i * 0.07)
            stepMotion(m, FRAME, state)
            world += state.speed * FRAME
            m.legs.forEachIndexed { k, leg ->
                val planted = leg.y == 0.0
                val z = world + leg.dz
                val p = prev[k]
                if (p != null && p.planted && planted) {
                    assertLess(abs(z - p.z), 1e-9)
                    checked++
                }
                prev[k] = Prev(z, planted)
            }
        }
        assertGreater(checked.toDouble(), 300.0)
    }

    private class LegPrev(
        var dz: Double,
        var v: Double,
        var lifted: Boolean,
    )

    @Test
    fun `a hoof is already moving back when it touches down at some of the ground speed`() {
        // a hoof that stops dead at touch-down and sets off at once in stance turns the joints of the
        // leg by half a radian in one frame
        for ((gait, speed) in listOf(Gait.TROT to 3.2, Gait.CANTER to 6.0)) {
            val (m, state) = settled(gait, speed).let { it.m to it.state }
            val prev = m.legs.map { LegPrev(it.dz, 0.0, false) }
            var checked = 0
            for (i in 0 until 360) {
                stepMotion(m, FRAME, state)
                m.legs.forEachIndexed { k, leg ->
                    val p = prev[k]
                    if (p.lifted && leg.y == 0.0) {
                        assertLess(p.v, -0.1 * speed * FRAME, "$gait leg $k")
                        checked++
                    }
                    p.v = leg.dz - p.dz
                    p.dz = leg.dz
                    p.lifted = leg.y > 0
                }
            }
            assertGreater(checked.toDouble(), 6.0)
        }
    }

    @Test
    fun `the stride never asks more of the legs than they can reach`() {
        val cases =
            listOf(
                Gait.WALK to listOf(0.3, 1.0, 1.8),
                Gait.TROT to listOf(2.0, 3.2, 4.0),
                Gait.CANTER to listOf(4.5, 6.0, 8.0, 10.0),
            )
        for ((gait, speeds) in cases) {
            for (speed in speeds) {
                val (m, state) = settled(gait, speed).let { it.m to it.state }
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                for (i in 0 until 240) {
                    stepMotion(m, FRAME, state)
                    for (leg in m.legs) {
                        lo = min(lo, leg.dz)
                        hi = max(hi, leg.dz)
                    }
                }
                // hoof offset within +- 0.5 m (+ the centre of the gait) of the neutral position
                assertLess(hi - lo, 1.25, "$gait at $speed m/s")
            }
        }
    }

    @Test
    fun `a hoof never travels more than 0 point 9 m during one stance`() {
        val cases =
            listOf(
                Gait.WALK to listOf(1.0, 1.8),
                Gait.TROT to listOf(2.0, 3.2, 4.5),
                Gait.CANTER to listOf(4.5, 6.0, 8.0, 10.0),
            )
        for ((gait, speeds) in cases) {
            for (speed in speeds) {
                val (m, state) = settled(gait, speed).let { it.m to it.state }
                val start = arrayOfNulls<Double>(4)
                m.legs.forEachIndexed { k, leg -> start[k] = leg.dz }
                var worst = 0.0
                for (i in 0 until 360) {
                    stepMotion(m, FRAME, state)
                    m.legs.forEachIndexed { k, leg ->
                        if (leg.y > 0) {
                            start[k] = null
                        } else {
                            if (start[k] == null) start[k] = leg.dz
                            worst = max(worst, abs(leg.dz - (start[k] ?: leg.dz)))
                        }
                    }
                }
                assertLessOrEqual(worst, 0.9, "$gait at $speed m/s")
            }
        }
    }

    @Test
    fun `a faster gait shortens the stance instead of overstretching the legs`() {
        fun duty(speed: Double): Double {
            val m = createLegModel()
            val w = GaitWeights(canter = 1.0)
            for (i in 0 until 100) blendGait(m, w, speed, speed, FRAME)
            return m.duty
        }
        assertLess(duty(9.0), duty(6.0))
        assertLessOrEqual(duty(6.0), GAITS.canter.duty(6.0) + 1e-9)
    }
}

class HaltTest {
    @Test
    fun `after a stop at the fence the legs finish their step then square up one at a time`() {
        val (m, state) = settled(Gait.CANTER, 6.0).let { it.m to it.state }
        state.gait = Gait.HALT
        state.speed = 0.0
        var steppingAtOnce = 0
        var stoppedAt = -1
        for (i in 0 until 6 * 60) {
            stepMotion(m, FRAME, state)
            val lifted = m.legs.count { it.y > 0.004 }
            if (i > 45) {
                // after the legs in swing have landed, at most one leg is off the ground
                steppingAtOnce = max(steppingAtOnce, lifted)
                if (lifted == 0 && stoppedAt < 0 && m.legs.all { abs(it.dz) < 0.06 }) stoppedAt = i
            }
        }
        assertTrue(steppingAtOnce <= 1)
        assertGreater(stoppedAt.toDouble(), 0.0)
        assertLess(stoppedAt.toDouble(), 5.0 * 60)
        for (leg in m.legs) {
            assertLess(abs(leg.dz), 0.06)
            assertEquals(0.0, leg.y)
        }
    }

    @Test
    fun `a step in progress when the horse stops is lifted properly no scraping over the ground`() {
        // stops at eight different phases of the trot: the legs that are in swing at that moment finish
        // their step, and the best of the eight stops shows the full lift of the trot
        var best = 0.0
        for (phase in 0 until 8) {
            val (m, state) = settled(Gait.TROT, 3.2).let { it.m to it.state }
            for (i in 0 until phase * 4) stepMotion(m, FRAME, state)
            state.gait = Gait.HALT
            state.speed = 0.0
            var lifted = 0.0
            for (i in 0 until 30) {
                stepMotion(m, FRAME, state)
                lifted = max(lifted, m.legs.maxOf { it.y })
            }
            best = max(best, lifted)
        }
        assertGreater(best, 0.1)
    }

    @Test
    fun `a standing horse stays still legs do not move no footfalls`() {
        val m = createMotion()
        val state = Horse(gait = Gait.HALT, speed = 0.0)
        for (i in 0 until 600) {
            val falls = stepMotion(m, FRAME, state)
            assertEquals(0, falls.size)
            for (leg in m.legs) {
                assertEquals(0.0, leg.dz)
                assertEquals(0.0, leg.y)
            }
        }
    }

    @Test
    fun `turning on the spot steps in place without walking away`() {
        val (m, state) = settled(Gait.HALT, 0.0, 1.4).let { it.m to it.state }
        var maxY = 0.0
        var maxDz = 0.0
        for (i in 0 until 240) {
            stepMotion(m, FRAME, state)
            for (leg in m.legs) {
                maxY = max(maxY, leg.y)
                maxDz = max(maxDz, abs(leg.dz))
            }
        }
        assertGreater(maxY, 0.04)
        assertLess(maxDz, 0.12)
    }
}

class GaitChangeTest {
    @Test
    fun `trot to canter ends in the canter sequence of the lead after about two strides`() {
        val m = createMotion()
        val state = Horse(gait = Gait.TROT, speed = 3.2, turnRate = -0.5)
        for (i in 0 until 240) stepMotion(m, FRAME, state)
        state.gait = Gait.CANTER
        state.speed = 6.0
        for (i in 0 until 3 * 60) stepMotion(m, FRAME, state)
        val seq = ArrayList<Int>()
        for (i in 0 until 2 * 60) seq.addAll(stepMotion(m, FRAME, state).toList())
        val i = seq.indexOf(RH)
        assertEquals(RH, seq[i])
        assertEquals(listOf(LH, RF).sorted(), listOf(seq[i + 1], seq[i + 2]).sorted())
        assertEquals(LF, seq[i + 3])
    }

    @Test
    fun `walk to trot to walk gives diagonal pairs after the change and four beats after the way back`() {
        val m = createMotion()
        val state = Horse(gait = Gait.WALK, speed = 1.5)
        for (i in 0 until 240) stepMotion(m, FRAME, state)
        state.gait = Gait.TROT
        state.speed = 3.2
        for (i in 0 until 4 * 60) stepMotion(m, FRAME, state)
        val times = HashMap<Int, MutableList<Int>>()
        for (i in 0 until 3 * 60) {
            val falls = stepMotion(m, FRAME, state)
            for (k in 0 until falls.size) times.getOrPut(falls[k]) { ArrayList() }.add(i)
        }

        // LF and RH land together, RF and LH half a cycle later
        fun near(
            a: Int,
            b: Int,
        ): Boolean {
            val ta = assertNotNull(times[a])
            val tb = assertNotNull(times[b])
            return ta.any { t -> tb.any { u -> abs(t - u) <= 3 } }
        }
        assertTrue(near(LF, RH))
        assertTrue(near(RF, LH))
        assertTrue(!near(LF, RF))
        state.gait = Gait.WALK
        state.speed = 1.5
        for (i in 0 until 4 * 60) stepMotion(m, FRAME, state)
        val seq = ArrayList<Int>()
        for (i in 0 until 3 * 60) seq.addAll(stepMotion(m, FRAME, state).toList())
        val start = seq.indexOf(LH)
        assertEquals(listOf(LH, LF, RH, RF), seq.subList(start, start + 4))
    }
}

class HoofScrapeTest {
    @Test
    fun `lifts a foreleg and sets it down again only at halt`() {
        val m = createMotion(createRng(5))
        val state = Horse(gait = Gait.HALT, speed = 0.0)
        var scraped = 0
        val legsUsed = HashSet<Int>()
        val gesture = assertNotNull(m.gesture)
        for (i in 0 until 90 * 60) {
            stepMotion(m, FRAME, state)
            val g = gesture.state
            if (g.id == GestureId.PAW && g.weight > 0.5) {
                scraped++
                legsUsed.add(g.leg)
                // the other three legs stand
                assertTrue(m.legs.filterIndexed { k, _ -> k != g.leg }.all { it.y == 0.0 })
            }
        }
        assertGreater(scraped.toDouble(), 30.0)
        assertTrue(legsUsed.all { it == LF || it == RF })
        // afterwards both forelegs are down and square again
        while (gesture.state.id != null) stepMotion(m, FRAME, state)
        for (i in 0 until 60) stepMotion(m, FRAME, state)
        assertTrue(m.legs.all { it.y == 0.0 && abs(it.dz) < 0.06 })
    }

    @Test
    fun `is taken back smoothly when the horse sets off`() {
        val m = createMotion(createRng(5))
        val state = Horse(gait = Gait.HALT, speed = 0.0)
        val gesture = assertNotNull(m.gesture)
        var guard = 0
        while (gesture.state.id != GestureId.PAW && guard++ < 200 * 60) stepMotion(m, FRAME, state)
        assertEquals(GestureId.PAW, gesture.state.id)
        while (gesture.state.weight < 0.9) stepMotion(m, FRAME, state)
        val leg = gesture.state.leg
        var prev = m.legs[leg].y
        state.gait = Gait.WALK
        state.speed = 1.0
        var maxStep = 0.0
        for (i in 0 until 90) {
            stepMotion(m, FRAME, state)
            maxStep = max(maxStep, abs(m.legs[leg].y - prev))
            prev = m.legs[leg].y
        }
        assertLess(maxStep, 0.07)
        assertEquals(null, gesture.state.id)
    }

    @Test
    fun `never starts while the horse moves or grazes`() {
        for ((gait, speed, graze) in listOf(Triple(Gait.WALK, 1.2, 0.0), Triple(Gait.HALT, 0.0, 1.0))) {
            val m = createMotion(createRng(6))
            val state = Horse(gait = gait, speed = speed)
            val gesture = assertNotNull(m.gesture)
            var started = false
            for (i in 0 until 120 * 60) {
                stepMotion(m, FRAME, state, graze)
                if (gesture.state.id != null) started = true
            }
            assertTrue(!started)
        }
    }
}
