package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.texture.createRng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun flocks(
    seed: Int = 4,
    count: Int = 4,
    size: Int = 6,
) = planFlocks(createRng(seed), count, size)

/** Velocity from two poses a little apart. */
private class Velocity(
    val x: Double,
    val y: Double,
    val z: Double,
    val a: FlightPose,
)

private fun velocity(
    t: Double,
    dt: Double = 0.01,
    pose: (Double, FlightPose) -> FlightPose,
): Velocity {
    val a = pose(t, FlightPose())
    val b = pose(t + dt, FlightPose())
    return Velocity((b.x - a.x) / dt, (b.y - a.y) / dt, (b.z - a.z) / dt, a)
}

private fun near(
    expected: Double,
    actual: Double,
    eps: Double = 1e-12,
) = assertEquals(expected, actual, eps)

class PlanFlocksTest {
    @Test
    fun `is deterministic for a seed and differs between seeds`() {
        assertEquals(flocks(1), flocks(1))
        assertNotEquals(flocks(1), flocks(2))
    }

    @Test
    fun `plans the requested flocks with birds around the center`() {
        val planned = flocks(3, 3, 5)
        assertEquals(3, planned.size)
        for (flock in planned) {
            assertEquals(5, flock.birds.size)
            assertTrue(flock.radius >= BIRD_LIMITS.radius.start)
            assertTrue(flock.radius <= BIRD_LIMITS.radius.endInclusive)
            assertTrue(flock.altitude >= BIRD_LIMITS.altitude.start)
            assertTrue(flock.altitude <= BIRD_LIMITS.altitude.endInclusive)
        }
    }

    @Test
    fun `circles in both directions across the flocks`() {
        val dirs = flocks(5, 8).map { it.dir }.toSet()
        assertEquals(2, dirs.size)
    }

    @Test
    fun `reproduces the plan of the web app for seed 4`() {
        // expected values computed with the web app (node, createRng(4), 4 flocks of 6)
        val flock = flocks()[0]
        near(-11.534392356406897, flock.cx)
        near(12.610429408960044, flock.cz)
        near(76.10290079377592, flock.radius)
        near(19.485907282680273, flock.altitude)
        near(1.9293714083731173, flock.bob)
        near(4.527181428740732, flock.speed)
        assertEquals(1, flock.dir)
        near(3.0501820696520587, flock.angle0)
        near(3.39304793392868, flock.phase)
        near(2.9654533797875047, flock.birds[0].f)
        near(-1.001724723726511, flock.birds[0].r)
        near(0.5142222938454356, flock.birds[0].phase)
        val last = flocks()[3].birds[5]
        near(1.8442579440306872, last.f)
        near(3.20057708776252, last.phase)
    }
}

class FlockPoseTest {
    @Test
    fun `stays on its circle at its altitude`() {
        for (flock in flocks()) {
            for (t in listOf(0.0, 3.3, 41.0, 500.0, 3600.0)) {
                val p = flockPose(flock, t, FlightPose())
                assertEquals(flock.radius, hypot(p.x - flock.cx, p.z - flock.cz), 1e-6)
                assertTrue(abs(p.y - flock.altitude) <= flock.bob + 1e-9)
            }
        }
    }

    @Test
    fun `flies at the planned speed with its nose along the path`() {
        for (flock in flocks()) {
            for (t in listOf(0.0, 12.5, 100.0)) {
                val v = velocity(t) { time, out -> flockPose(flock, time, out) }
                val speed = hypot(v.x, v.z)
                assertEquals(flock.speed, speed, 0.05)
                // heading h points along (sin h, cos h)
                assertEquals(v.x / speed, sin(v.a.heading), 0.005)
                assertEquals(v.z / speed, cos(v.a.heading), 0.005)
            }
        }
    }

    @Test
    fun `circles slowly - a lap takes at least half a minute`() {
        for (flock in flocks()) {
            assertTrue(2 * PI * flock.radius / flock.speed > 30)
        }
    }

    @Test
    fun `reproduces the pose of the web app`() {
        val f = flocks()
        val p = flockPose(f[1], 41.0, FlightPose())
        near(88.79944099978549, p.x, 1e-9)
        near(53.67136257176378, p.z, 1e-9)
        near(19.68359901646998, p.y, 1e-9)
        near(2.5713243812851183, p.heading, 1e-9)
    }
}

class BirdPoseTest {
    @Test
    fun `keeps the birds together around the flock center`() {
        for (flock in flocks(6)) {
            for (t in listOf(0.0, 7.0, 90.0)) {
                val c = flockPose(flock, t, FlightPose())
                for (i in flock.birds.indices) {
                    val b = birdPose(flock, i, t, FlightPose())
                    assertTrue(hypot(b.x - c.x, b.z - c.z) <= BIRD_LIMITS.spread)
                    assertTrue(abs(b.y - c.y) <= BIRD_LIMITS.spread / 2)
                }
            }
        }
    }

    @Test
    fun `moves smoothly - no jump between frames`() {
        val flock = flocks(8)[0]
        var last = birdPose(flock, 1, 0.0, FlightPose())
        var t = 1.0 / 60
        while (t < 20) {
            val p = birdPose(flock, 1, t, FlightPose())
            assertTrue(hypot(hypot(p.x - last.x, p.y - last.y), p.z - last.z) < 0.3)
            assertTrue(abs(p.heading - last.heading) < 0.05)
            last = p
            t += 1.0 / 60
        }
    }

    @Test
    fun `writes into the object it is given and returns it`() {
        val flock = flocks()[0]
        val out = FlightPose()
        assertTrue(birdPose(flock, 0, 1.0, out) === out)
        for (value in listOf(out.x, out.y, out.z, out.heading, out.roll)) assertTrue(value.isFinite())
    }

    @Test
    fun `gives each bird its own place`() {
        val flock = flocks()[0]
        val a = birdPose(flock, 0, 5.0, FlightPose())
        val b = birdPose(flock, 1, 5.0, FlightPose())
        assertTrue(hypot(hypot(a.x - b.x, a.y - b.y), a.z - b.z) > 0.5)
    }

    @Test
    fun `reproduces the pose of the web app`() {
        val p = birdPose(flocks()[1], 2, 7.0, FlightPose())
        near(-67.63666527934105, p.x, 1e-9)
        near(56.64051142385462, p.z, 1e-9)
        near(16.865670402540253, p.y, 1e-9)
        near(0.6177311896466355, p.heading, 1e-9)
        near(0.20053976124019865, p.roll, 1e-9)
    }
}

class ButterflyTest {
    private val anchors = listOf(PatchAnchor(30.0, -20.0, 3.0), PatchAnchor(-20.0, 40.0, 4.0))

    private fun planned() = planButterflies(createRng(2), anchors, 10)

    @Test
    fun `plans the requested number over the given patches`() {
        assertEquals(10, planned().size)
        assertEquals(planned(), planned())
        assertEquals(emptyList(), planButterflies(createRng(2), emptyList(), 5))
    }

    @Test
    fun `wanders within its patch and low above the flowers`() {
        for (b in planned()) {
            var t = 0.0
            while (t < 120) {
                val p = butterflyPose(b, t, FlightPose())
                assertTrue(hypot(p.x - b.ax, p.z - b.az) <= b.reach + 1e-9)
                assertTrue(p.y > 0.2)
                assertTrue(p.y < 1.8)
                t += 0.7
            }
        }
    }

    @Test
    fun `flutters slowly across the meadow never faster than a few m per s`() {
        for (b in planned()) {
            var t = 0.0
            while (t < 60) {
                val v = velocity(t) { time, out -> butterflyPose(b, time, out) }
                assertTrue(hypot(hypot(v.x, v.y), v.z) < 2.6)
                t += 1.3
            }
        }
    }

    @Test
    fun `turns smoothly towards where it flies`() {
        val b = planned()[0]
        var last = butterflyPose(b, 0.0, FlightPose())
        var t = 1.0 / 60
        while (t < 30) {
            val p = butterflyPose(b, t, FlightPose())
            var d = p.heading - last.heading
            d -= (d / (2 * PI)).roundToInt() * 2 * PI
            assertTrue(abs(d) < 0.25)
            last = p
            t += 1.0 / 60
        }
    }

    @Test
    fun `reproduces the plan and the pose of the web app`() {
        val b = planned()
        near(29.613532899459823, b[0].ax)
        near(-19.931680716574192, b[0].az)
        near(1.640550566604361, b[0].rx)
        near(1.394999059382826, b[0].rz)
        near(2.691836192912998, b[0].reach)
        near(0.8876439735060557, b[0].height)
        near(0.37616666839458046, b[0].freq)
        near(4.910867928698134, b[0].p3)
        near(3.455155449867264, b[9].reach)
        val p = butterflyPose(b[3], 12.3, FlightPose())
        near(-20.122496075507943, p.x, 1e-9)
        near(41.57068092809974, p.z, 1e-9)
        near(0.4862626059590276, p.y, 1e-9)
        near(1.7753744116777719, p.heading, 1e-9)
    }
}
