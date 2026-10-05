package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutCheckTest {
    private fun el(
        id: String,
        x: Double,
        z: Double,
        rot: Double = 0.0,
        kind: ElementKind = ElementKind.VERTICAL,
        spread: Double = 0.0,
    ) = Element(id, kind, 0.6, spread, x, z, rot)

    private fun obs(
        elements: List<Element>,
        directed: Boolean = true,
    ) = Obstacle(1, elements, directed)

    private fun List<String>.mentions(text: String) = any { it.contains(text) }

    @Test
    fun acceptsASingleObstacleWithEnoughRoom() {
        assertEquals(emptyList(), checkLayout(listOf(obs(listOf(el("a", 0.0, 0.0))))))
    }

    @Test
    fun reportsTooLittleDistanceToTheFence() {
        val issues = checkLayout(listOf(obs(listOf(el("a", 16.0, 0.0)))))
        assertTrue(issues.mentions("fence"))
    }

    @Test
    fun reportsACorridorThatExtendsOutsideTheArena() {
        // 14 m of approach before z = -22 reaches to -36
        val issues = checkLayout(listOf(obs(listOf(el("a", 0.0, -22.0)))))
        assertTrue(issues.mentions("outside the arena"))
    }

    @Test
    fun reportsAnObstacleInTheApproachCorridorBeforeAndAfterTheJump() {
        val before =
            checkLayout(
                listOf(obs(listOf(el("a", 0.0, 5.0))), obs(listOf(el("b", 1.0, -5.0, PI / 2)))),
            )
        assertTrue(before.mentions("b is in the approach corridor"))
        val after =
            checkLayout(
                listOf(obs(listOf(el("a", 0.0, 0.0))), obs(listOf(el("b", 0.0, 6.0, PI / 2)))),
            )
        assertTrue(after.mentions("b is in the approach corridor"))
    }

    @Test
    fun directedObstaclesNeedOnlyTheLandingPathBehindUndirectedOnesTheFullApproach() {
        val directed = corridorOf(obs(listOf(el("a", 0.0, 0.0)), directed = true))
        val free = corridorOf(obs(listOf(el("a", 0.0, 0.0)), directed = false))
        assertCloseTo(14.0 + 8.0, directed.halfAlong * 2, 6)
        assertCloseTo(28.0, free.halfAlong * 2, 6)
    }

    @Test
    fun reportsElementsOfDifferentObstaclesStandingTooClose() {
        val issues = checkLayout(listOf(obs(listOf(el("a", -2.5, 0.0))), obs(listOf(el("b", 2.5, 0.0)))))
        assertTrue(issues.mentions("too close"))
    }

    @Test
    fun allowsElementsOfTheSameCombinationInTheCorridor() {
        assertEquals(
            emptyList(),
            checkLayout(listOf(obs(listOf(el("a", 0.0, -4.0), el("b", 0.0, 3.3))))),
        )
    }

    @Test
    fun reportsStartFinishLinesTooCloseToElementsOrInTheCorridor() {
        val issues =
            checkLayout(
                listOf(obs(listOf(el("a", 0.0, 0.0)))),
                lines = listOf(LayoutLine("finish", Vec2(-3.0, -10.0), Vec2(3.0, -10.0))),
            )
        assertTrue(issues.mentions("finish: crosses"))
    }

    @Test
    fun rectangleAndSegmentIntersection() {
        val r = footprint(el("a", 0.0, 0.0))
        assertTrue(rectsOverlap(r, footprint(el("b", 1.0, 0.0))))
        assertFalse(rectsOverlap(r, footprint(el("b", 10.0, 0.0))))
        assertTrue(segmentHitsRect(Vec2(-5.0, 0.0), Vec2(5.0, 0.0), r))
        assertFalse(segmentHitsRect(Vec2(-5.0, 3.0), Vec2(5.0, 3.0), r))
    }
}
