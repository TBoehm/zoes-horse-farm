package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.view3d.FENCE
import app.zoeshorsefarm.view3d.FenceStyle
import app.zoeshorsefarm.view3d.GATE
import app.zoeshorsefarm.view3d.PADDOCK
import app.zoeshorsefarm.view3d.paddockContains
import app.zoeshorsefarm.view3d.planFence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val hx = ARENA.width / 2 + FENCE.offset
private val hz = ARENA.length / 2 + FENCE.offset

/** Distance of a point from the rectangle of the arena fence line. */
private fun distanceToFence(
    x: Double,
    z: Double,
): Double = min(abs(hx - abs(x)), abs(hz - abs(z)))

class PlanBuntingTest {
    private val plan = planBunting()

    @Test
    fun `hangs one string per span of the arena fence and none across the gate`() {
        val spans = planFence().segments.filter { it.style == FenceStyle.ARENA }
        assertEquals(spans.size, plan.strings.size)
        for (s in plan.strings) {
            assertTrue(s.a.y > FENCE.height)
            assertTrue(distanceToFence(s.a.x, s.a.z) < 1e-6)
            assertTrue(distanceToFence(s.b.x, s.b.z) < 1e-6)
            assertTrue(s.sag > 0)
        }
        for (p in plan.pennants) {
            val inGate = abs(p.x + hx) < 0.5 && abs(p.z - GATE.z) < GATE.width / 2
            assertFalse(inGate)
        }
    }

    @Test
    fun `puts a good number of pennants on the fence hanging below the string`() {
        assertTrue(plan.pennants.size > 300)
        for (p in plan.pennants) {
            assertTrue(distanceToFence(p.x, p.z) < 0.05)
            assertTrue(p.y < FENCE.height + 0.15)
            assertTrue(p.y - p.length > 0.5)
            assertTrue(p.width > 0.1)
        }
    }

    @Test
    fun `alternates the colours so that neighbours differ`() {
        val colors = BUNTING_COLORS.toSet()
        assertTrue(colors.size >= 5)
        for (p in plan.pennants) assertTrue(p.color in colors)
        val bySpan = plan.pennants.groupBy { it.span }
        for (list in bySpan.values) {
            val sorted = list.sortedBy { it.index }
            for (i in 1 until sorted.size) assertNotEquals(sorted[i - 1].color, sorted[i].color)
        }
    }

    @Test
    fun `lists every second pennant first so that a prefix is an evenly thinner string`() {
        assertTrue(plan.coreCount > plan.pennants.size * 0.45)
        assertTrue(plan.coreCount < plan.pennants.size * 0.7)
        plan.pennants.forEachIndexed { i, p -> assertEquals(i < plan.coreCount, p.core) }
        // within the core, no two pennants of a span are neighbours
        for (p in plan.pennants.take(plan.coreCount)) assertEquals(0, p.index % 2)
    }

    @Test
    fun `tells which way a pennant faces along the fence with the flutter across`() {
        for (p in plan.pennants) assertTrue(abs(hypot(p.tx, p.tz) - 1) < 1e-6)
    }
}

class PlanPotsTest {
    private val pots = planPots()

    @Test
    fun `stands a few pots at the gate outside the fence`() {
        assertTrue(pots.size >= 3)
        assertTrue(pots.size <= 8)
        for (pot in pots) {
            assertTrue(pot.x < -hx)
            assertTrue(abs(pot.z - GATE.z) < GATE.width / 2 + 3)
            assertTrue(pot.scale > 0.7)
        }
    }

    @Test
    fun `keeps the way through the gate and the path free`() {
        for (pot in pots) assertFalse(abs(pot.z - GATE.z) < GATE.width / 2 + 0.1)
    }

    @Test
    fun `does not put two pots into each other`() {
        for (i in pots.indices) {
            for (j in i + 1 until pots.size) {
                assertTrue(hypot(pots[i].x - pots[j].x, pots[i].z - pots[j].z) > 0.6)
            }
        }
    }
}

class PlanPaddockPropsTest {
    private val props = planPaddockProps()

    @Test
    fun `places shelter trough and hay rack inside the paddock clear of the fence`() {
        for (p in listOf(props.shelter, props.trough, props.rack)) assertTrue(paddockContains(p.x, p.z, 0.6))
    }

    @Test
    fun `turns the open side of the shelter towards the paddock`() {
        val p = props.shelter
        // the opening points from the shelter to the center of the paddock
        val toCenter = atan2(PADDOCK.x - p.x, PADDOCK.z - p.z)
        var d = p.rotation - toCenter
        d -= round(d / (2 * PI)) * 2 * PI
        assertTrue(abs(d) < PI / 2)
    }
}
