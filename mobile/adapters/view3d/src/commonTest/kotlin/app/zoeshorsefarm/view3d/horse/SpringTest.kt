package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertFinite
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpringTest {
    private val tailCfg =
        HairConfig(
            count = 5,
            omega0 = 16.0,
            omegaTail = 9.0,
            zeta = 0.35,
            gainFwd = doubleArrayOf(0.02, 0.03, 0.04, 0.04, 0.04),
            gainUp = doubleArrayOf(0.01, 0.01, 0.005, 0.0, 0.0),
            gainLat = doubleArrayOf(0.02, 0.03, 0.04, 0.04, 0.04),
            couple = 0.3,
        )

    private val rest = HairDrive(0.0, 0.0, 0.0)

    private fun run(
        chain: HairChain,
        drive: HairDrive,
        seconds: Double,
        dt: Double = 1.0 / 60,
    ) {
        var t = 0.0
        while (t < seconds) {
            stepHairChain(chain, drive, dt)
            t += dt
        }
    }

    @Test
    fun `smoothTo reaches about 95 percent after 4 point 7 over omega`() {
        val s = createSpring()
        val omega = 10.0
        var t = 0.0
        while (t < 4.74 / omega) {
            smoothTo(s, 1.0, omega, 1.0 / 600)
            t += 1.0 / 600
        }
        assertGreater(s.x, 0.94)
        assertLess(s.x, 0.97)
    }

    @Test
    fun `softClamp is linear near zero and saturates`() {
        assertClose(0.1, softClamp(0.1, 10.0), 3)
        assertClose(10.0, softClamp(1000.0, 10.0), 6)
        assertClose(-10.0, softClamp(-1000.0, 10.0), 6)
    }

    @Test
    fun `hair chain stays at rest without drive`() {
        val c = createHairChain(tailCfg)
        run(c, rest, 2.0)
        for (s in c.segments) {
            assertEquals(0.0, s.pitch.x)
            assertEquals(0.0, s.sway.x)
        }
    }

    @Test
    fun `forward acceleration swings the hair back later segments lag`() {
        val c = createHairChain(tailCfg)
        run(c, HairDrive(4.0, 0.0, 0.0), 0.08)
        assertGreater(c.segments[0].pitch.x, 0.0)
        // the tip has not caught up yet: it moved less than the root segment
        assertLess(c.segments[4].pitch.x, c.segments[0].pitch.x)
    }

    @Test
    fun `turning swings the hair to the outside`() {
        val c = createHairChain(tailCfg)
        run(c, HairDrive(0.0, 0.0, 5.0), 0.3)
        assertGreater(c.segments[2].sway.x, 0.02)
        assertLess(abs(c.segments[2].pitch.x), 0.01)
    }

    @Test
    fun `swings after the drive stops and settles to rest`() {
        val c = createHairChain(tailCfg)
        run(c, HairDrive(6.0, 0.0, 0.0), 0.4)
        val peak = c.segments[2].pitch.x
        assertGreater(peak, 0.0)
        var crossed = false
        var t = 0.0
        while (t < 1.5) {
            stepHairChain(c, rest, 1.0 / 60)
            if (c.segments[2].pitch.x < -0.005) crossed = true
            t += 1.0 / 60
        }
        assertTrue(crossed)
        run(c, rest, 6.0)
        for (s in c.segments) {
            assertLess(abs(s.pitch.x), 1e-3)
            assertLess(abs(s.sway.x), 1e-3)
        }
    }

    @Test
    fun `stays bounded with extreme drive and long frames`() {
        val c = createHairChain(tailCfg)
        for (i in 0 until 200) {
            stepHairChain(c, HairDrive(1e4, -1e4, 1e4), if (i % 3 == 0) 0.5 else 1.0 / 60)
            for (s in c.segments) {
                assertTrue(s.pitch.x.isFinite() && s.sway.x.isFinite())
                assertLess(abs(s.pitch.x), 2.5)
                assertLess(abs(s.sway.x), 2.5)
            }
        }
    }

    @Test
    fun `kick adds motion that fades out`() {
        val c = createHairChain(tailCfg)
        kickChain(c, 0.0, 2.0)
        run(c, rest, 0.1)
        assertGreater(abs(c.segments[3].sway.x), 0.01)
        run(c, rest, 8.0)
        assertLess(abs(c.segments[3].sway.x), 1e-3)
    }

    @Test
    fun `acceleration estimator reports forward acceleration while speeding up and zero at constant speed`() {
        val est = createAccelEstimator()
        var speed = 0.0
        for (i in 0 until 60) {
            speed += 3.0 / 60
            stepAccelEstimator(est, AccelInput(speed, 0.0, 0.0), 1.0 / 60)
        }
        assertGreater(est.fwd, 2.0)
        assertLess(est.fwd, 3.2)
        for (i in 0 until 60) stepAccelEstimator(est, AccelInput(speed, 0.0, 0.0), 1.0 / 60)
        assertLess(abs(est.fwd), 0.05)
    }

    @Test
    fun `centripetal acceleration follows speed times turn rate`() {
        val est = createAccelEstimator()
        for (i in 0 until 60) stepAccelEstimator(est, AccelInput(5.0, 1.0, 0.0), 1.0 / 60)
        assertGreater(est.lat, 4.0)
    }

    @Test
    fun `vertical acceleration of a bounce is bounded and does not need a smooth input`() {
        val est = createAccelEstimator()
        var peak = 0.0
        for (i in 0 until 240) {
            val y = if (i == 100) 0.5 else 0.0 // a landing-like step in height
            stepAccelEstimator(est, AccelInput(0.0, 0.0, y), 1.0 / 60)
            peak = maxOf(peak, abs(est.up))
        }
        assertLessOrEqual(peak, ACCEL_LIMITS.up)
        assertGreater(peak, 1.0)
    }

    @Test
    fun `estimator ignores a zero or negative time step`() {
        val est = createAccelEstimator()
        stepAccelEstimator(est, AccelInput(1.0, 0.0, 0.0), 0.0)
        assertFalse(est.primed)
        assertFinite(est.fwd)
    }
}
