package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals

private fun ctx(
    g: Gait,
    phi: Double = 0.0,
    jumpWeight: Double = 0.0,
    jumpJ: Double = 0.0,
    hopWeight: Double = 0.0,
    stopWeight: Double = 0.0,
    pitch: Double = 0.0,
) = RiderContext().also {
    it.weights = GaitWeights.of(g)
    it.phi = phi
    it.jumpWeight = jumpWeight
    it.jumpJ = jumpJ
    it.hopWeight = hopWeight
    it.stopWeight = stopWeight
    it.pitch = pitch
}

private fun jumpAt(j: Double) = riderSeat(Horse(gait = Gait.CANTER), ctx(Gait.CANTER, jumpWeight = 1.0, jumpJ = j))

class RiderSeatTest {
    @Test
    fun `halt walk and back give an upright sitting seat in the saddle`() {
        for (g in listOf(Gait.HALT, Gait.WALK, Gait.BACK)) {
            val s = riderSeat(Horse(gait = g), ctx(g))
            assertLess(s.lean, 0.2)
            assertClose(0.0, s.rise, 3)
        }
    }

    @Test
    fun `trot is a rising trot up and down once per stride`() {
        val rises = (0 until 40).map { riderSeat(Horse(gait = Gait.TROT), ctx(Gait.TROT, phi = it / 40.0)).rise }
        assertGreater(rises.max(), 0.08)
        assertLess(rises.min(), 0.01)
        val ups = rises.filterIndexed { i, r -> r > 0.05 && rises[(i + 39) % 40] <= 0.05 }.size
        assertEquals(1, ups)
    }

    @Test
    fun `canter is a light seat out of the saddle leaning forward`() {
        val s = riderSeat(Horse(gait = Gait.CANTER), ctx(Gait.CANTER))
        assertGreater(s.lean, 0.35)
        assertLess(s.lean, 0.75)
        assertGreater(s.rise, 0.03)
    }

    @Test
    fun `jump flight is a two-point seat folded forward with hands following the mouth`() {
        val canter = riderSeat(Horse(gait = Gait.CANTER), ctx(Gait.CANTER))
        val s =
            riderSeat(
                Horse(gait = Gait.CANTER, jump = JumpView(JumpPhase.FLIGHT, 0.5)),
                ctx(Gait.CANTER, jumpWeight = 1.0, jumpJ = 1.5),
            )
        assertGreater(s.lean, 0.8)
        assertGreater(s.handZ, canter.handZ + 0.1)
    }

    @Test
    fun `refusal stop makes the rider sit back`() {
        val s = riderSeat(Horse(gait = Gait.HALT), ctx(Gait.HALT, stopWeight = 1.0))
        assertLess(s.lean, 0.05)
    }

    @Test
    fun `works without horse context derived from the gait`() {
        val s = riderSeat(Horse(gait = Gait.CANTER))
        assertGreater(s.lean, 0.35)
    }
}

class CrestReleaseTest {
    @Test
    fun `is zero on the approach and after the landing and full in flight`() {
        assertClose(0.0, crestRelease(0.2), 6)
        assertGreater(crestRelease(1.5), 0.99)
        assertClose(0.0, crestRelease(3.0), 6)
        assertEquals(0.0, crestRelease(1.5, 0.0))
    }

    @Test
    fun `hands slide forward and down along the neck in flight and come back on landing`() {
        val approach = jumpAt(0.05)
        val flight = jumpAt(1.5)
        val landed = jumpAt(2.95)
        assertGreater(flight.handZ, approach.handZ + 0.2)
        assertLess(flight.handY, approach.handY - 0.05)
        assertClose(approach.handZ, landed.handZ, 2)
        // the contact is taken up again before the torso is fully upright
        val touchdown = jumpAt(2.4)
        assertLess(touchdown.handZ, flight.handZ - 0.05)
        assertGreater(touchdown.lean, landed.lean)
    }

    @Test
    fun `the seat absorbs the touchdown and sinks a little`() {
        assertLess(jumpAt(2.2).rise, jumpAt(1.5).rise - 0.03)
    }

    @Test
    fun `is a continuous function of the jump parameter`() {
        var prev = jumpAt(0.0)
        var prevValues = doubleArrayOf(prev.rise, prev.forward, prev.lean, prev.handZ, prev.handY)
        var j = 0.01
        while (j <= 3) {
            prev = jumpAt(j)
            val values = doubleArrayOf(prev.rise, prev.forward, prev.lean, prev.handZ, prev.handY)
            for (k in values.indices) assertLess(abs(values[k] - prevValues[k]), 0.03, "channel $k at J=$j")
            prevValues = values
            j += 0.01
        }
    }
}

class SeatFilterTest {
    private fun step(
        filter: SeatFilter,
        c: RiderContext,
        n: Int = 1,
        dt: Double = 1.0 / 60,
    ): SeatPose {
        val out = SeatPose()
        for (i in 0 until n) filter.step(dt, Horse(gait = Gait.CANTER), c, out)
        return out
    }

    @Test
    fun `starts at the target with no swing in from zero`() {
        val f = createSeatFilter()
        val c = ctx(Gait.CANTER)
        val first = step(f, c)
        assertClose(riderSeat(Horse(gait = Gait.CANTER), c).lean, first.lean, 6)
    }

    @Test
    fun `settles on the unfiltered seat`() {
        val f = createSeatFilter()
        val c = ctx(Gait.TROT, phi = 0.3)
        step(f, ctx(Gait.HALT), 5)
        val out = step(f, c, 120)
        val raw = riderSeat(Horse(gait = Gait.TROT), c)
        assertClose(raw.rise, out.rise, 3)
        assertClose(raw.forward, out.forward, 3)
        assertClose(raw.lean, out.lean, 3)
        assertClose(raw.handX, out.handX, 3)
        assertClose(raw.handY, out.handY, 3)
        assertClose(raw.handZ, out.handZ, 3)
        assertClose(raw.footForward, out.footForward, 3)
    }

    @Test
    fun `turns an abrupt stop into a quick but smooth move`() {
        val f = createSeatFilter()
        step(f, ctx(Gait.CANTER), 10)
        val stopped = ctx(Gait.CANTER, stopWeight = 1.0)
        val raw = riderSeat(Horse(gait = Gait.CANTER), stopped)
        var prev = step(f, ctx(Gait.CANTER)).lean
        var maxStep = 0.0
        for (i in 0 until 40) {
            val lean = step(f, stopped).lean
            maxStep = max(maxStep, abs(lean - prev))
            prev = lean
        }
        val jump = abs(riderSeat(Horse(gait = Gait.CANTER), ctx(Gait.CANTER)).lean - raw.lean)
        assertLess(maxStep, jump * 0.2)
        assertClose(raw.lean, prev, 2) // reached within 0.7 s
    }

    @Test
    fun `keeps the posting beat exactly in step with the stride with no lag`() {
        val f = createSeatFilter()
        step(f, ctx(Gait.TROT), 120)
        for (phi in listOf(0.0, 0.25, 0.5, 0.75)) {
            val c = ctx(Gait.TROT, phi = phi)
            assertClose(riderSeat(Horse(gait = Gait.TROT), c).rise, step(f, c).rise, 3)
        }
    }

    @Test
    fun `balances against the horse pitch immediately`() {
        val f = createSeatFilter()
        step(f, ctx(Gait.CANTER), 60)
        val level = step(f, ctx(Gait.CANTER)).lean
        val nose = step(f, ctx(Gait.CANTER, pitch = 0.3)).lean
        assertClose(0.15, level - nose, 3)
    }
}

class SeatPartsTest {
    @Test
    fun `posting goes to the immediate part and the pose to the calm part`() {
        val base = SeatPose()
        val osc = SeatPose()
        riderSeatParts(Horse(gait = Gait.TROT), ctx(Gait.TROT, phi = 0.5), base, osc)
        assertGreater(osc.rise, 0.09)
        assertClose(0.0, base.rise, 6)
        assertClose(0.25, base.lean, 6)
    }

    @Test
    fun `the rhythmic part fades out in the two-point seat`() {
        val base = SeatPose()
        val osc = SeatPose()
        riderSeatParts(
            Horse(gait = Gait.CANTER),
            ctx(Gait.TROT, phi = 0.5, jumpWeight = 1.0, jumpJ = 1.5),
            base,
            osc,
        )
        assertLess(abs(osc.rise), 0.005)
    }
}
