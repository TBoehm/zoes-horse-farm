package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertGreaterOrEqual
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DT = 1.0 / 60
private val HALT = GaitWeights(halt = 1.0)
private val CANTER = GaitWeights(canter = 1.0)

private fun input(
    speed: Double = 0.0,
    turnRate: Double = 0.0,
    bodyY: Double = 0.0,
    weights: GaitWeights = HALT,
) = LifeInput(speed = speed, turnRate = turnRate, bodyY = bodyY, neckAngle = 0.0, weights = weights, alert = 0.0)

private fun run(
    life: Life,
    seconds: Double,
    make: (Double) -> LifeInput,
) {
    var t = 0.0
    while (t < seconds) {
        stepLife(life, DT, make(t))
        t += DT
    }
}

private fun Life.hair(): List<HairSegment> = tail.segments + mane.segments

class TailAndManeTest {
    @Test
    fun `a start throws the tail back it swings after and settles when the speed is constant`() {
        val life = createLife(createRng(1))
        var speed = 0.0
        var peak = 0.0
        var t = 0.0
        while (t < 1) {
            speed = min(6.0, speed + 6 * DT) // 1 s from 0 to 6 m/s
            stepLife(life, DT, input(speed = speed, weights = CANTER))
            peak =
                max(
                    peak,
                    life.tail.segments[3]
                        .pitch.x,
                )
            t += DT
        }
        // while accelerating the tail was pushed back (+ pitch)
        assertGreater(peak, 0.1)
        // at constant speed it swings over and comes to rest again, apart from the breeze
        var tipMin = 0.0
        t = 0.0
        while (t < 2) {
            stepLife(life, DT, input(speed = 6.0, weights = CANTER))
            tipMin =
                min(
                    tipMin,
                    life.tail.segments[4]
                        .pitch.x,
                )
            t += DT
        }
        assertLess(tipMin, 0.05)
        run(life, 4.0) { input(speed = 6.0, weights = CANTER) }
        for (seg in life.tail.segments) assertLess(abs(seg.pitch.x), 0.12)
    }

    @Test
    fun `a stop swings the tail forward`() {
        val life = createLife(createRng(2))
        run(life, 2.0) { input(speed = 6.0, weights = CANTER) }
        run(life, 0.5) { t -> input(speed = max(0.0, 6 - 24 * t), weights = CANTER) }
        val min = life.tail.segments.minOf { it.pitch.x }
        assertLess(min, -0.05)
    }

    @Test
    fun `a turn swings tail and mane to the outside`() {
        val life = createLife(createRng(3))
        run(life, 1.0) { input(speed = 5.0, turnRate = 1.2, weights = CANTER) }
        // right turn (+): acceleration to the right, hair to the left (+ sway)
        assertGreater(
            life.tail.segments[3]
                .sway.x,
            0.03,
        )
        assertGreater(
            life.mane.segments[2]
                .sway.x,
            0.0,
        )
    }

    @Test
    fun `a landing makes the hair jump vertical acceleration moves tail and mane`() {
        val life = createLife(createRng(4))
        run(life, 2.0) { input(speed = 6.0, weights = CANTER) }
        var calm = 0.0
        for (seg in life.hair()) calm = max(calm, max(abs(seg.pitch.x), abs(seg.sway.x)))
        var moved = 0.0
        var t = 0.0
        while (t < 0.6) {
            // down from 0.6 m at 4 m/s, then the ground stops the body
            stepLife(life, DT, input(speed = 6.0, weights = CANTER, bodyY = max(0.0, 0.6 - 4 * t)))
            for (seg in life.hair()) moved = max(moved, max(abs(seg.pitch.x), abs(seg.sway.x)))
            t += DT
        }
        assertGreater(moved, calm + 0.05)
        assertLess(moved, 1.3) // and it stays within sane limits
    }

    @Test
    fun `hair is calm at halt only the light breeze and the mane stays near its rest pose`() {
        val life = createLife(createRng(5))
        run(life, 3.0) { input() }
        for (seg in life.hair() + life.forelock.segments) {
            assertLess(abs(seg.pitch.x), 0.1)
            assertLess(abs(seg.sway.x), 0.15)
        }
    }

    @Test
    fun `survives long frames and wild input`() {
        val life = createLife(createRng(6))
        for (i in 0 until 100) {
            stepLife(life, if (i % 7 == 0) 0.1 else DT, input(speed = (i % 5) * 40.0, bodyY = (i % 3) * 3.0))
            for (seg in life.hair()) {
                assertTrue(seg.pitch.x.isFinite() && seg.sway.x.isFinite())
                assertLess(abs(seg.pitch.x), 3.0)
            }
        }
    }

    @Test
    fun `swishes the tail now and then at halt not while galloping`() {
        var swishes = 0
        var prevSway = 0.0
        // count the swishes: the angular velocity of the tip jumps when the tail is kicked
        val l = createLife(createRng(7))
        var t = 0.0
        while (t < 120) {
            stepLife(l, DT, input())
            val v =
                l.tail.segments[4]
                    .sway.v
            if (abs(v) > 3 && abs(prevSway) <= 3) swishes++
            prevSway = v
            t += DT
        }
        assertGreater(swishes.toDouble(), 5.0)
        val gallop = createLife(createRng(7))
        var max = 0.0
        t = 0.0
        while (t < 60) {
            stepLife(gallop, DT, input(speed = 6.0, weights = CANTER))
            max =
                max(
                    max,
                    abs(
                        gallop.tail.segments[4]
                            .sway.v,
                    ),
                )
            t += DT
        }
        assertLess(max, 3.0)
    }
}

class BlinkBreathingTest {
    @Test
    fun `blinks every few seconds and the lid closes completely`() {
        val life = createLife(createRng(8))
        var blinks = 0
        var prev = 0.0
        var peak = 0.0
        var t = 0.0
        while (t < 120) {
            stepLife(life, DT, input())
            if (life.blinkClosure > 0.5 && prev <= 0.5) blinks++
            prev = life.blinkClosure
            peak = max(peak, life.blinkClosure)
            t += DT
        }
        assertGreater(peak, 0.95)
        assertGreater(blinks.toDouble(), 8.0)
        assertLess(blinks.toDouble(), 45.0)
    }

    private class Rate(
        val perSecond: Double,
        val minFlare: Double,
        val maxFlare: Double,
    )

    private fun rate(weights: GaitWeights): Rate {
        val life = createLife(createRng(9))
        var crossings = 0
        var prev = 0.0
        var minFlare = 1.0
        var maxFlare = 0.0
        var t = 0.0
        while (t < 30) {
            stepLife(life, DT, input(speed = if (weights === HALT) 0.0 else 6.0, weights = weights))
            if (life.breath > 0 && prev <= 0) crossings++
            prev = life.breath
            if (t > 5) {
                minFlare = min(minFlare, life.flare)
                maxFlare = max(maxFlare, life.flare)
            }
            t += DT
        }
        return Rate(crossings / 30.0, minFlare, maxFlare)
    }

    @Test
    fun `breathes faster in the canter than at halt and the nostrils flare with it`() {
        val halt = rate(HALT)
        val canter = rate(CANTER)
        assertGreater(canter.perSecond, halt.perSecond * 3)
        assertGreater(halt.maxFlare - halt.minFlare, 0.15)
        assertGreater(canter.minFlare, halt.minFlare)
        assertLessOrEqual(canter.maxFlare, 1.0)
        assertGreaterOrEqual(halt.minFlare, 0.0)
    }
}

class GestureShapesTest {
    private fun g(
        id: GestureId?,
        t: Double,
        weight: Double = 1.0,
        duration: Double = 1.5,
    ) = GestureState().also {
        it.id = id
        it.t = t
        it.weight = weight
        it.duration = duration
        it.leg = 0
    }

    private fun assertZero(h: HeadGesture) {
        assertEquals(0.0, h.yaw)
        assertEquals(0.0, h.pitch)
        assertEquals(0.0, h.neck)
    }

    @Test
    fun `are zero without a gesture or at weight 0`() {
        assertZero(gestureHead(null))
        assertZero(gestureHead(g(GestureId.SHAKE, 0.5, 0.0)))
        assertZero(gestureHead(g(null, 0.0, 1.0, 1.0)))
    }

    @Test
    fun `a head shake swings from side to side and dies away a toss lifts the head`() {
        var maxYaw = 0.0
        var minYaw = 0.0
        var t = 0.0
        while (t < 1.5) {
            val o = gestureHead(g(GestureId.SHAKE, t))
            maxYaw = max(maxYaw, o.yaw)
            minYaw = min(minYaw, o.yaw)
            t += DT
        }
        assertGreater(maxYaw, 0.15)
        assertLess(minYaw, -0.15)
        assertLess(abs(gestureHead(g(GestureId.SHAKE, 1.49)).yaw), 0.05)
        assertLess(gestureHead(g(GestureId.TOSS, 0.3)).pitch, -0.3)
        assertGreater(gestureHead(g(GestureId.TOSS, 1.3)).pitch, -0.05)
    }

    @Test
    fun `change gently from frame to frame`() {
        for (id in GestureId.entries) {
            var prev = gestureHead(g(id, 0.0))
            var prevYaw = prev.yaw
            var prevPitch = prev.pitch
            var t = DT
            while (t < 1.5) {
                prev = gestureHead(g(id, t))
                assertLess(abs(prev.yaw - prevYaw), 0.13)
                assertLess(abs(prev.pitch - prevPitch), 0.1)
                prevYaw = prev.yaw
                prevPitch = prev.pitch
                t += DT
            }
        }
    }
}
