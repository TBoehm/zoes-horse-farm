package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.POLE_LENGTH
import app.zoeshorsefarm.domain.sim.Placement
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.domain.sim.crossAxisOf
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.toFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

private fun near(
    expected: Double,
    actual: Double,
    eps: Double = 1e-6,
) = assertEquals(expected, actual, eps)

private fun element(
    kind: ElementKind,
    height: Double = 0.6,
    spread: Double = 0.0,
    x: Double = 0.0,
    z: Double = 0.0,
    rot: Double = 0.0,
) = Element("e", kind, height, spread, x, z, rot)

/** World position of an element-local point (lx, lz). */
private fun localToWorld(
    element: Element,
    lx: Double,
    lz: Double,
): Vec2 {
    val c = cos(element.rot)
    val s = sin(element.rot)
    return Vec2(element.x + lx * c + lz * s, element.z - lx * s + lz * c)
}

/** A rng that cycles through the given values. */
private fun seq(vararg values: Double): () -> Double {
    var i = 0
    return { values[i++ % values.size] }
}

private fun positionKey(
    x: Double,
    z: Double,
) = "${x.toFixed(2)},${z.toFixed(2)}"

class AxesAndFlagsTest {
    @Test
    fun `t matches crossAxisOf of the simulation`() {
        for (rot in listOf(0.0, 0.7, PI, -2.0)) {
            val t = axesOf(rot).t
            val ref = crossAxisOf(Placement(0.0, 0.0, rot))
            near(ref.x, t.x)
            near(ref.z, t.z)
        }
    }

    @Test
    fun `red flag stands on the plus t side`() {
        for (rot in listOf(0.0, 1.2, PI, 4.0)) {
            val el = element(ElementKind.VERTICAL, x = 3.0, z = -2.0, rot = rot)
            val p = localToWorld(el, flagSides().red * STAND_X, 0.0)
            val t = axesOf(rot).t
            near(STAND_X, (p.x - el.x) * t.x + (p.z - el.z) * t.z)
            val w = localToWorld(el, flagSides().white * STAND_X, 0.0)
            near(-STAND_X, (w.x - el.x) * t.x + (w.z - el.z) * t.z)
        }
    }

    @Test
    fun `local plus Z points in jump direction n`() {
        val el = element(ElementKind.VERTICAL, x = 1.0, z = 1.0, rot = 0.9)
        val p = localToWorld(el, 0.0, 2.0)
        val n = axesOf(0.9).n
        near(1 + 2 * n.x, p.x)
        near(1 + 2 * n.z, p.z)
    }
}

class PolesOfTest {
    @Test
    fun `vertical - top pole is rail 0 with its top at height`() {
        val poles = polesOf(element(ElementKind.VERTICAL, height = 0.6))
        assertEquals(1, poles.count { it.rail == 0 })
        near(0.6, poles[0].a[1] + POLE_RADIUS)
        assertTrue(poles.all { it.rail <= 0 })
    }

    @Test
    fun `a high vertical also has a fixed filler pole`() {
        val poles = polesOf(element(ElementKind.VERTICAL, height = 0.85))
        assertEquals(1, poles.count { it.rail == -1 })
    }

    @Test
    fun `oxer - front rail 0 at minus half spread back rail 1 at plus half spread both at height`() {
        val poles = polesOf(element(ElementKind.OXER, height = 0.8, spread = 1.0))
        val r0 = poles.first { it.rail == 0 }
        val r1 = poles.first { it.rail == 1 }
        near(-0.5, r0.a[2])
        near(0.5, r1.a[2])
        near(r0.a[1], r1.a[1])
        near(0.8, r0.a[1] + POLE_RADIUS)
    }

    @Test
    fun `cross - two crossed poles as rail 0 crossing at height plus a ground pole`() {
        val poles = polesOf(element(ElementKind.CROSS, height = 0.5))
        val crossed = poles.filter { it.rail == 0 }
        assertEquals(2, crossed.size)
        for (p in crossed) {
            val mid = (p.a[1] + p.b[1]) / 2
            near(0.5, mid + POLE_RADIUS)
        }
        assertEquals(1, poles.count { it.rail == -1 })
    }

    @Test
    fun `poles lie between the stands length below POLE_LENGTH`() {
        val p = polesOf(element(ElementKind.VERTICAL, height = 0.6))[0]
        assertTrue(p.b[0] - p.a[0] <= POLE_LENGTH)
        assertTrue(p.b[0] - p.a[0] > POLE_LENGTH - 0.05)
    }

    @Test
    fun `stand rows and height`() {
        assertEquals(listOf(-0.6, 0.6), standRows(element(ElementKind.OXER, spread = 1.2)).toList())
        assertEquals(listOf(0.0), standRows(element(ElementKind.CROSS)).toList())
        assertTrue(standHeight(element(ElementKind.VERTICAL, height = 0.4)) > 0.4 + 0.5)
        assertTrue(standHeight(element(ElementKind.VERTICAL, height = 1.2)) > 1.2)
    }
}

class LabelsTest {
    private fun obstacle(
        number: Int?,
        elements: Int,
    ) = Obstacle(number, List(elements) { element(ElementKind.VERTICAL) }, false)

    @Test
    fun `number combination with a and b none in free mode`() {
        assertEquals("3", labelOf(obstacle(3, 1), 0))
        assertEquals("5b", labelOf(obstacle(5, 2), 1))
        assertNull(labelOf(obstacle(null, 1), 0))
        assertEquals("5a", highlightText(obstacle(5, 2), 0, 5))
        assertEquals("2", highlightText(obstacle(2, 1), 0, 2))
        assertNull(highlightText(obstacle(null, 1), 0, null))
    }
}

class FallingPolesTest {
    @Test
    fun `fall curve starts at 0 ends at 1 with a small bounce`() {
        assertEquals(0.0, fallCurve(0.0))
        assertEquals(1.0, fallCurve(1.0))
        near(1.0, fallCurve(0.78))
        assertTrue(fallCurve(0.89) < 1)
        assertTrue(fallCurve(0.89) > 0.85)
        var t = 0.0
        while (t < 0.78) {
            assertTrue(fallCurve(t + 0.05) >= fallCurve(t))
            t += 0.05
        }
    }

    @Test
    fun `one end falls first both arrive at t = 1`() {
        val mid = endProgress(0.5, 0)
        assertTrue(mid.a > mid.b)
        val other = endProgress(0.5, 1)
        assertTrue(other.b > other.a)
        assertEquals(EndProgress(1.0, 1.0), endProgress(1.0, 0))
        assertEquals(EndProgress(0.0, 0.0), endProgress(0.0, 1))
    }

    @Test
    fun `endProgressOf is the allocation-free form of endProgress`() {
        for (lead in listOf(0, 1)) {
            for (t in listOf(0.0, 0.1, 0.3, 0.5, 0.9, 1.0)) {
                val ref = endProgress(t, lead)
                assertEquals(ref.a, endProgressOf(t, lead, true))
                assertEquals(ref.b, endProgressOf(t, lead, false))
            }
        }
    }

    @Test
    fun `fallPointInto writes the same point as fallPoint into a vector`() {
        val from = Vec3(0.0, 0.8, 0.0)
        val to = Vec3(0.2, 0.05, 1.0)
        val out = Vec3(9.0, 9.0, 9.0)
        for (t in listOf(0.0, 0.3, 0.8, 1.0)) {
            val ref = fallPoint(from.toArray(), to.toArray(), t)
            assertTrue(fallPointInto(out, from, to, t) === out)
            assertContentEquals(ref, out.toArray())
        }
    }

    @Test
    fun `fallPoint interpolates from cup to ground`() {
        assertContentEquals(
            doubleArrayOf(0.0, 0.8, 0.0),
            fallPoint(doubleArrayOf(0.0, 0.8, 0.0), doubleArrayOf(0.2, 0.05, 1.0), 0.0),
        )
        val end = fallPoint(doubleArrayOf(0.0, 0.8, 0.0), doubleArrayOf(0.2, 0.05, 1.0), 1.0)
        near(0.2, end[0])
        near(0.05, end[1])
        near(1.0, end[2])
    }

    @Test
    fun `a fallen pole lies on the sand on the fall side length is kept`() {
        for (side in listOf(1, -1)) {
            val t = fallTarget(doubleArrayOf(0.0, 0.6, 0.4), 3.48, side, seq(0.5, 0.9, 0.1, 0.3, 0.7))
            assertEquals(POLE_RADIUS, t.a[1])
            assertEquals(POLE_RADIUS, t.b[1])
            assertEquals(side.toDouble(), sign((t.a[2] + t.b[2]) / 2 - 0.4))
            near(3.48, hypot(t.b[0] - t.a[0], t.b[2] - t.a[2]))
            assertEquals(side.toDouble(), sign(t.roll))
        }
    }
}

class AidPlacementTest {
    private val zone = Zone(far = 3.0, near = 1.0, lastPoint = 0.5, reach = 4.0, center = 2.0)

    @Test
    fun `vertical rot 0 approach in plus n - band in front of the obstacle z below 0`() {
        val p = assertNotNull(aidPlacement(element(ElementKind.VERTICAL), 1, zone))
        near(0.0, p.x)
        near(-2.0, p.z)
        near(2.0, p.depth)
        assertEquals(POLE_LENGTH, p.width)
    }

    @Test
    fun `oxer - measured from the front edge half spread both directions`() {
        val el = element(ElementKind.OXER, spread = 1.2, x = 5.0, z = 5.0, rot = PI / 2)
        val p = assertNotNull(aidPlacement(el, 1, zone))
        // n = (1, 0): front edge at x = 4.4, band center 2 m before it
        near(2.4, p.x)
        near(5.0, p.z)
        val q = assertNotNull(aidPlacement(el, -1, zone))
        near(7.6, q.x)
        near(PI / 2, q.rotY)
    }

    @Test
    fun `swapped or empty zones`() {
        val el = element(ElementKind.VERTICAL)
        near(-2.0, assertNotNull(aidPlacement(el, 1, zone.copy(far = 1.0, near = 3.0))).z)
        assertNull(aidPlacement(el, 1, zone.copy(far = 2.0, near = 2.0)))
        assertNull(aidPlacement(null, 1, zone))
        assertNull(aidPlacement(el, 1, null))
    }
}

class LinesTest {
    private fun line(
        a: Vec2,
        b: Vec2,
    ) = Line(a, b, Vec2(0.0, 1.0))

    @Test
    fun `lineSegment gives center length and angle`() {
        val s = lineSegment(Vec2(0.0, 0.0), Vec2(0.0, 4.0))
        near(4.0, s.length)
        near(2.0, s.cz)
        near(0.0, s.angle)
    }

    @Test
    fun `planLines - separate signs with the given texts`() {
        val plan =
            planLines(
                CourseLines(
                    start = line(Vec2(0.0, 0.0), Vec2(4.0, 0.0)),
                    finish = line(Vec2(0.0, 10.0), Vec2(4.0, 10.0)),
                    labels = LineLabels(start = "Start", finish = "Finish"),
                ),
            )
        assertEquals(listOf("Start", "Finish"), plan.map { it.text })
        assertTrue(plan[1].finish)
    }

    @Test
    fun `planLines - identical lines give one shared sign`() {
        val same = line(Vec2(0.0, 0.0), Vec2(4.0, 0.0))
        val plan = planLines(CourseLines(start = same, finish = same, labels = LineLabels("S", "Z")))
        assertEquals(1, plan.size)
        assertEquals("S · Z", plan[0].text)
        assertTrue(plan[0].finish)
        assertEquals(emptyList(), planLines(null))
    }
}

class StartFinishLineFlagsTest {
    @Test
    fun `red flag stands on the riders right white on the left for every course line`() {
        for (course in COURSES) {
            for (line in listOf(course.start, course.finish)) {
                val posts = linePosts(lineSegment(line.a, line.b))
                val red = posts.first { it.red }
                val white = posts.first { !it.red }
                assertEquals(2, posts.size)
                val mx = (red.x + white.x) / 2
                val mz = (red.z + white.z) / 2
                // right of the rider for heading h = (-cos h, sin h); riding direction is line.dir
                val rightX = -line.dir.z
                val rightZ = line.dir.x
                assertTrue((red.x - mx) * rightX + (red.z - mz) * rightZ > 2)
                assertTrue((white.x - mx) * rightX + (white.z - mz) * rightZ < -2)
            }
        }
    }

    @Test
    fun `the red flag is the b end a is the left end of the line`() {
        // a is the rider's left end: riding +z, a is at x = +3 (left), b at x = -3 (right)
        val posts = linePosts(lineSegment(Vec2(3.0, 0.0), Vec2(-3.0, 0.0)))
        val red = posts.first { it.red }
        assertEquals(-3.0, red.x)
        assertEquals(0.0, red.z)
    }
}

class PlanFenceTest {
    private val plan = planFence(listOf(FenceRun(Vec2(-21.0, 24.0), Vec2(-35.0, 24.0))))

    @Test
    fun `post spacing at most 2_5 m fence outside the riding area`() {
        val arena = plan.segments.filter { it.style == FenceStyle.ARENA }
        assertTrue(arena.all { it.len <= FENCE.spacing + EPS })
        val posts = plan.posts.filter { it.style == FenceStyle.ARENA }
        assertTrue(posts.all { abs(it.x) >= ARENA.width / 2 || abs(it.z) >= ARENA.length / 2 })
    }

    @Test
    fun `gate gap without fence parts`() {
        val inGap =
            plan.segments.filter {
                it.style == FenceStyle.ARENA &&
                    it.x < 0 &&
                    abs(it.z - GATE.z) < GATE.width / 2 - 0.1 &&
                    abs(it.x + 20.18) < 0.1
            }
        assertEquals(0, inGap.size)
        val gate = assertNotNull(plan.gate)
        near(GATE.width, gate.z1 - gate.z0)
    }

    @Test
    fun `no duplicate posts wooden path fence`() {
        val keys = plan.posts.map { positionKey(it.x, it.z) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(plan.posts.any { it.style == FenceStyle.WOOD })
    }
}

class EnvironmentTest {
    @Test
    fun `terrain is flat around the facility and rises towards the horizon`() {
        assertEquals(0.0, terrainHeight(0.0, 0.0))
        assertEquals(0.0, terrainHeight(60.0, 40.0))
        assertTrue(terrainHeight(300.0, 0.0) > 5)
    }

    @Test
    fun `the arena is blocked for plants`() {
        assertTrue(isBlocked(0.0, 0.0))
        assertFalse(isBlocked(60.0, 60.0))
    }

    @Test
    fun `scatter returns points in the annulus outside blocked areas`() {
        var s = 1L
        val rng = {
            s = (s * 16807) % 2147483647
            s / 2147483647.0
        }
        val pts = scatter(rng, 50, 30.0, 80.0, 1.0)
        assertEquals(50, pts.size)
        for (p in pts) {
            val r = hypot(p.x, p.z)
            assertTrue(r >= 30 - EPS)
            assertTrue(r <= 80 + EPS)
            assertFalse(isBlocked(p.x, p.z, 1.0))
        }
    }

    @Test
    fun `instanceCount - mandatory instances always the rest by density`() {
        assertEquals(10, instanceCount(100, 10, 0.0))
        assertEquals(100, instanceCount(100, 10, 1.0))
        assertEquals(55, instanceCount(100, 10, 0.5))
        assertEquals(5, instanceCount(5, 10, 0.5))
    }
}

class PaddockTest {
    private val rotated = Paddock(x = 10.0, z = -5.0, width = 20.0, depth = 14.0, rotation = 0.6)

    @Test
    fun `has a documented rectangle on the meadow`() {
        assertTrue(PADDOCK.width >= 18)
        assertTrue(PADDOCK.depth >= 12)
    }

    @Test
    fun `does not touch the arena the stable the path the hut or the benches`() {
        val corners =
            listOf(
                paddockPoint(1.0, 1.0),
                paddockPoint(1.0, -1.0),
                paddockPoint(-1.0, 1.0),
                paddockPoint(-1.0, -1.0),
            )
        val halfX = ARENA.width / 2 + 3
        val halfZ = ARENA.length / 2 + 3
        for (c in corners) {
            assertTrue(abs(c.x) > halfX || abs(c.z) > halfZ)
            // stable footprint (with a gap) and the path fences
            val st = SITE.stable
            val inStable = abs(c.x - st.x) < st.depth / 2 + 2 && abs(c.z - st.z) < st.length / 2 + 2
            assertFalse(inStable)
            assertTrue(c.z < SITE.pathFence[1].a.z - 4)
        }
        assertTrue(hypot(PADDOCK.x - SITE.hut.x, PADDOCK.z - SITE.hut.z) > 40)
    }

    @Test
    fun `is on flat ground`() {
        for ((u, v) in listOf(0.0 to 0.0, 1.0 to 1.0, -1.0 to -1.0)) {
            val p = paddockPoint(u, v)
            assertEquals(0.0, terrainHeight(p.x, p.z))
        }
    }

    @Test
    fun `paddockPoint maps the unit square to the rectangle also when rotated`() {
        assertEquals(Vec2(PADDOCK.x, PADDOCK.z), paddockPoint(0.0, 0.0))
        val edge = paddockPoint(1.0, 0.0)
        assertEquals(PADDOCK.x + PADDOCK.width / 2, edge.x, 1e-9)
        val r = paddockPoint(1.0, 0.0, rotated)
        assertEquals(rotated.width / 2, hypot(r.x - rotated.x, r.z - rotated.z), 1e-9)
        val back = paddockPoint(0.0, 1.0, rotated)
        assertEquals(rotated.depth / 2, hypot(back.x - rotated.x, back.z - rotated.z), 1e-9)
    }

    @Test
    fun `paddockContains follows the rotated rectangle and the margin`() {
        assertTrue(paddockContains(PADDOCK.x, PADDOCK.z))
        assertFalse(paddockContains(PADDOCK.x + PADDOCK.width / 2 + 0.1, PADDOCK.z))
        assertFalse(paddockContains(PADDOCK.x + PADDOCK.width / 2 - 0.5, PADDOCK.z, 1.0))
        assertTrue(paddockContains(PADDOCK.x + PADDOCK.width / 2 + 0.5, PADDOCK.z, -1.0))
        for ((u, v) in listOf(0.9 to 0.9, -0.9 to 0.9, 0.9 to -0.9, 0.0 to 0.0)) {
            val p = paddockPoint(u, v, rotated)
            assertTrue(paddockContains(p.x, p.z, 0.0, rotated))
        }
        val outside = paddockPoint(1.2, 0.0, rotated)
        assertFalse(paddockContains(outside.x, outside.z, 0.0, rotated))
    }

    @Test
    fun `is blocked for plants with a clearance around the fence`() {
        assertTrue(isBlocked(PADDOCK.x, PADDOCK.z))
        assertTrue(isBlocked(PADDOCK.x + PADDOCK.width / 2 + 0.5, PADDOCK.z))
        assertFalse(isBlocked(PADDOCK.x - PADDOCK.width / 2 - 2, PADDOCK.z))
    }

    @Test
    fun `planPaddockFence runs along the four sides with unique posts`() {
        val plan = planPaddockFence()
        val perimeter = 2 * (PADDOCK.width + PADDOCK.depth)
        val length = plan.segments.sumOf { it.len }
        assertEquals(perimeter, length, 1e-6)
        assertTrue(plan.segments.all { it.style == FenceStyle.PADDOCK })
        assertEquals(plan.segments.size, plan.posts.size)
        val keys = plan.posts.map { positionKey(it.x, it.z) }.toSet()
        assertEquals(plan.posts.size, keys.size)
        // every post is on the fence line
        for (p in plan.posts) {
            val onX = abs(abs(p.x - PADDOCK.x) - PADDOCK.width / 2) < 1e-6
            val onZ = abs(abs(p.z - PADDOCK.z) - PADDOCK.depth / 2) < 1e-6
            assertTrue(onX || onZ)
        }
    }
}

class IsOnArenaSandTest {
    @Test
    fun `is true inside the riding area and false on the meadow around it`() {
        assertTrue(isOnArenaSand(0.0, 0.0))
        assertTrue(isOnArenaSand(ARENA.width / 2 - 0.5, ARENA.length / 2 - 0.5))
        assertTrue(isOnArenaSand(-ARENA.width / 2 + 0.5, -ARENA.length / 2 + 0.5))
        assertFalse(isOnArenaSand(ARENA.width / 2 + 2, 0.0))
        assertFalse(isOnArenaSand(0.0, -ARENA.length / 2 - 2))
        assertFalse(isOnArenaSand(PADDOCK.x, PADDOCK.z))
    }

    @Test
    fun `ends at the fence line a margin keeps away from the edge`() {
        val edge = ARENA.width / 2 + FENCE.offset
        assertTrue(isOnArenaSand(edge - 0.01, 0.0))
        assertFalse(isOnArenaSand(edge + 0.01, 0.0))
        assertFalse(isOnArenaSand(edge - 0.01, 0.0, 0.5))
    }
}
