package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertGreaterOrEqual
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val AREA = PaddockArea(x = 30.0, z = -12.0, width = 24.0, depth = 16.0, rotation = 0.6)
private const val DT = 1.0 / 30

/** The nose, the tail and the two sides (at the middle of the body) of a grazer (world x/z). */
private fun bodyPoints(g: Grazer): List<XZ> {
    val fx = sin(g.heading)
    val fz = cos(g.heading)
    val ox = g.x + fx * GRAZING.bodyOffset
    val oz = g.z + fz * GRAZING.bodyOffset
    return listOf(
        HORSE_EXTENT.front to 0.0,
        -HORSE_EXTENT.back to 0.0,
        -GRAZING.bodyOffset to HORSE_EXTENT.side,
        -GRAZING.bodyOffset to -HORSE_EXTENT.side,
    ).map { (along, across) -> XZ(ox + fx * along + fz * across, oz + fz * along - fx * across) }
}

private class Log {
    val states = HashMap<GrazerState, Double>()
    var maxSpeed = 0.0
    var maxTurn = 0.0
    var maxGrazeStep = 0.0
    var minGap = Double.POSITIVE_INFINITY
    var outside = 0
}

private class Simulation(
    val grazers: List<Grazer>,
    val log: Log,
    val walked: Double,
)

private fun simulate(
    seed: Int,
    seconds: Double,
    count: Int = 2,
    area: PaddockArea = AREA,
): Simulation {
    val rng = createRng(seed)
    val grazers =
        List(count) { i ->
            val p = toWorld(area, (i - (count - 1) / 2.0) * 6, 0.0)
            createGrazer(p.x, p.z, rng() * 6 - 3, rng)
        }
    val log = Log()
    val prevGraze = grazers.map { it.graze }.toDoubleArray()
    var walked = 0.0
    var t = 0.0
    while (t < seconds) {
        grazers.forEachIndexed { i, g ->
            val others = grazers.filter { it !== g }
            val bx = g.x
            val bz = g.z
            stepGrazer(g, DT, area, others, rng)
            log.states[g.state] = (log.states[g.state] ?: 0.0) + DT
            log.maxSpeed = max(log.maxSpeed, g.speed)
            log.maxTurn = max(log.maxTurn, abs(g.turnRate))
            log.maxGrazeStep = max(log.maxGrazeStep, abs(g.graze - prevGraze[i]))
            prevGraze[i] = g.graze
            walked += hypot(g.x - bx, g.z - bz)
            if (!insideArea(area, g.x, g.z, 0.0)) log.outside++
        }
        if (count > 1) {
            log.minGap = min(log.minGap, hypot(grazers[0].x - grazers[1].x, grazers[0].z - grazers[1].z))
        }
        t += DT
    }
    return Simulation(grazers, log, walked)
}

class PaddockAreaTest {
    @Test
    fun `toWorld and toLocal are inverse and respect the rotation`() {
        for ((lx, lz) in listOf(0.0 to 0.0, 5.0 to -3.0, -11.0 to 7.0)) {
            val w = toWorld(AREA, lx, lz)
            val l = toLocal(AREA, w.x, w.z)
            assertClose(lx, l.x, 9)
            assertClose(lz, l.z, 9)
        }
        // a quarter turn: the local x axis points along -z (three.js rotation about Y)
        val q = toWorld(PaddockArea(0.0, 0.0, 4.0, 4.0, PI / 2), 1.0, 0.0)
        assertClose(0.0, q.x, 9)
        assertClose(-1.0, q.z, 9)
    }

    @Test
    fun `insideArea honours the margin`() {
        val c = toWorld(AREA, 11.5, 0.0)
        assertTrue(insideArea(AREA, c.x, c.z, 0.0))
        assertTrue(!insideArea(AREA, c.x, c.z, 1.0))
        val out = toWorld(AREA, 13.0, 0.0)
        assertTrue(!insideArea(AREA, out.x, out.z, 0.0))
    }
}

class NextSpotTest {
    @Test
    fun `lies inside the area with a margin to the fence and is a real step away`() {
        val rng = createRng(2)
        for (i in 0 until 200) {
            val from = toWorld(AREA, (rng() - 0.5) * 20, (rng() - 0.5) * 12)
            val spot = assertNotNull(pickSpot(rng, AREA, from))
            assertTrue(insideArea(AREA, spot.x, spot.z, GRAZING.margin - 1e-6))
        }
    }

    @Test
    fun `prefers spots away from the other horses`() {
        val rng = createRng(3)
        val from = toWorld(AREA, 0.0, 0.0)
        val other = toWorld(AREA, 3.0, 0.0)
        var near = 0
        for (i in 0 until 100) {
            val spot = assertNotNull(pickSpot(rng, AREA, from, listOf(other)))
            if (hypot(spot.x - other.x, spot.z - other.z) < 2) near++
        }
        assertLess(near.toDouble(), 10.0)
    }
}

class GrazingHorseTest {
    @Test
    fun `grazes most of the time sometimes looks up and walks a few steps`() {
        val sim = simulate(7, 1200.0)
        val total =
            sim.log.states.values
                .sum()

        fun time(s: GrazerState) = sim.log.states[s] ?: 0.0
        assertGreater(time(GrazerState.GRAZE) / total, 0.6)
        assertGreater(time(GrazerState.LOOK), 10.0)
        assertGreater(time(GrazerState.TURN), 2.0)
        assertGreater(time(GrazerState.WALK), 10.0)
        assertGreater(sim.walked, 20.0)
    }

    @Test
    fun `walks slowly and turns gently`() {
        val log = simulate(8, 900.0).log
        assertLessOrEqual(log.maxSpeed, GRAZING.walkSpeed + 1e-9)
        assertLessOrEqual(log.maxTurn, GRAZING.turnRate + 1e-9)
    }

    @Test
    fun `stays in the paddock and keeps away from the other horse`() {
        for (seed in listOf(1, 2, 3)) {
            val log = simulate(seed, 900.0).log
            assertEquals(0, log.outside)
            assertGreater(log.minGap, 0.8)
        }
    }

    @Test
    fun `lowers and raises the head gradually`() {
        assertLess(simulate(9, 600.0).log.maxGrazeStep, 0.08)
    }

    @Test
    fun `head is down while grazing and up while looking and moving`() {
        val rng = createRng(4)
        val g = createGrazer(AREA.x, AREA.z, rng = rng)
        var t = 0.0
        while (t < 4) {
            stepGrazer(g, DT, AREA, emptyList(), rng)
            t += DT
        }
        assertEquals(GrazerState.GRAZE, g.state)
        assertGreater(g.graze, 0.95)
        g.timer = 0.0
        t = 0.0
        while (t < 3) {
            stepGrazer(g, DT, AREA, emptyList(), rng)
            t += DT
        }
        assertEquals(GrazerState.LOOK, g.state)
        assertLess(g.graze, 0.5)
    }

    @Test
    fun `is deterministic for a seed`() {
        fun run() = simulate(11, 300.0).grazers.map { listOf(it.x, it.z, it.heading) }
        assertEquals(run(), run())
    }

    @Test
    fun `walks with the head up and stands still while it grazes`() {
        val rng = createRng(5)
        val g = createGrazer(AREA.x, AREA.z, rng = rng)
        var walking = 0.0
        var checkedWalk = 0
        var checkedGraze = 0
        var t = 0.0
        while (t < 900) {
            stepGrazer(g, DT, AREA, emptyList(), rng)
            walking = if (g.state == GrazerState.WALK) walking + DT else 0.0
            if (walking > 2.5) {
                assertLess(g.graze, 0.1)
                checkedWalk++
            }
            if (g.state == GrazerState.GRAZE && g.graze > 0.95) {
                assertLess(g.speed, 0.3)
                checkedGraze++
            }
            t += DT
        }
        assertGreater(checkedWalk.toDouble(), 5.0)
        assertGreater(checkedGraze.toDouble(), 100.0)
    }
}

class WholeHorseTest {
    @Test
    fun `the body centre lies behind the origin of the horse and covers the whole model`() {
        assertLessOrEqual(GRAZING.bodyOffset + HORSE_EXTENT.front, GRAZING.bodyRadius)
        assertLessOrEqual(HORSE_EXTENT.back - GRAZING.bodyOffset, GRAZING.bodyRadius)
        assertGreaterOrEqual(GRAZING.margin, GRAZING.bodyRadius)
    }

    @Test
    fun `nose tail and sides stay inside the fence for half an hour several seeds two horses`() {
        for (seed in 1..5) {
            val rng = createRng(seed)
            val grazers =
                listOf(-5.0, 5.0).map { x ->
                    val p = toWorld(AREA, x, (rng() - 0.5) * 6)
                    createGrazer(p.x, p.z, rng() * 6 - 3, rng)
                }
            var turned = 0
            var worst = Double.POSITIVE_INFINITY // the closest a point comes to the fence (m)
            var t = 0.0
            while (t < 1800) {
                for (g in grazers) {
                    stepGrazer(g, DT, AREA, grazers, rng)
                    if (g.state == GrazerState.TURN) turned++
                    for (p in bodyPoints(g)) {
                        val l = toLocal(AREA, p.x, p.z)
                        worst = min(worst, min(AREA.width / 2 - abs(l.x), AREA.depth / 2 - abs(l.z)))
                    }
                }
                t += DT
            }
            assertGreater(turned.toDouble(), 0.0, "seed $seed: turns on the spot happened")
            assertGreaterOrEqual(worst, 0.0, "seed $seed")
        }
    }

    @Test
    fun `nose tail and sides stay out of the footprints of the props`() {
        // footprints (radius of the prop) and the keep-out circles of the area (footprint + reach)
        fun prop(
            lx: Double,
            lz: Double,
            r: Double,
        ) = toWorld(AREA, lx, lz).let { AvoidCircle(it.x, it.z, r) }

        val props = listOf(prop(-8.0, 0.0, 3.2), prop(6.0, 4.5, 1.2), prop(-2.0, -5.0, 1.0))
        val area = AREA.withAvoid(props.map { AvoidCircle(it.x, it.z, it.r + GRAZING.bodyRadius) })
        for (seed in 1..5) {
            val rng = createRng(seed)
            val start = toWorld(AREA, 3.0, -2.0)
            val g = createGrazer(start.x, start.z, rng() * 6 - 3, rng)
            var walked = 0.0
            var closest = Double.POSITIVE_INFINITY
            var t = 0.0
            while (t < 1800) {
                val bx = g.x
                val bz = g.z
                stepGrazer(g, DT, area, listOf(g), rng)
                walked += hypot(g.x - bx, g.z - bz)
                for (p in bodyPoints(g)) {
                    for (c in props) closest = min(closest, hypot(p.x - c.x, p.z - c.z) - c.r)
                }
                t += DT
            }
            assertGreater(walked, 20.0, "seed $seed: the horse moved around")
            assertGreaterOrEqual(closest, 0.0, "seed $seed")
        }
    }
}

class KeepOutCircleTest {
    private val avoid =
        listOf(
            AvoidCircle(AREA.x, AREA.z, 3.0),
            toWorld(AREA, 8.0, 4.0).let { AvoidCircle(it.x, it.z, 2.0) },
        )
    private val area = AREA.withAvoid(avoid)

    @Test
    fun `distanceToSegment measures to the nearest point of the segment`() {
        assertClose(1.0, distanceToSegment(0.0, 1.0, -1.0, 0.0, 1.0, 0.0), 9)
        assertClose(2.0, distanceToSegment(3.0, 0.0, -1.0, 0.0, 1.0, 0.0), 9)
        assertClose(hypot(3.0, 3.0), distanceToSegment(5.0, 5.0, 2.0, 2.0, 2.0, 2.0), 9)
    }

    @Test
    fun `picks spots that are outside the circles and whose way does not cross them`() {
        val rng = createRng(6)
        for (i in 0 until 300) {
            val from = toWorld(AREA, (rng() - 0.5) * 20, (rng() - 0.5) * 12)
            val startsInside = avoid.any { hypot(from.x - it.x, from.z - it.z) < it.r }
            val spot = if (startsInside) null else pickSpot(rng, area, from)
            for (c in avoid) {
                if (spot != null) {
                    assertGreaterOrEqual(distanceToSegment(c.x, c.z, from.x, from.z, spot.x, spot.z), c.r - 1e-9)
                }
            }
        }
    }

    @Test
    fun `returns null when every way is blocked`() {
        val boxed = AREA.withAvoid(listOf(AvoidCircle(AREA.x, AREA.z, 100.0)))
        assertNull(pickSpot(createRng(1), boxed, XZ(AREA.x + 8, AREA.z)))
    }

    @Test
    fun `a grazer never enters a circle while it walks around the paddock`() {
        for (seed in 1..4) {
            val rng = createRng(seed)
            val start = toWorld(AREA, -9.0, -5.0)
            val g = createGrazer(start.x, start.z, 0.0, rng)
            var inside = 0
            var t = 0.0
            while (t < 1500) {
                stepGrazer(g, DT, area, emptyList(), rng)
                if (avoid.any { hypot(g.x - it.x, g.z - it.z) < it.r - 0.3 }) inside++
                t += DT
            }
            assertEquals(0, inside)
        }
    }
}
