package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.COMBI_DISTANCE
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Placement
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.axisOf
import app.zoeshorsefarm.domain.sim.toLocal
import app.zoeshorsefarm.domain.testing.LayoutLine
import app.zoeshorsefarm.domain.testing.LayoutRect
import app.zoeshorsefarm.domain.testing.assertCloseTo
import app.zoeshorsefarm.domain.testing.checkLayout
import app.zoeshorsefarm.domain.testing.corridorOf
import app.zoeshorsefarm.domain.testing.footprint
import app.zoeshorsefarm.domain.testing.jsRound
import app.zoeshorsefarm.domain.testing.rectsOverlap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoursesTest {
    private fun elementsOf(obstacles: List<Obstacle>) = obstacles.flatMap { it.elements }

    private fun elementsOf(course: Course) = elementsOf(course.obstacles)

    private fun kindsOf(course: Course) = elementsOf(course).map { it.kind }.toSet()

    private fun maxHeight(course: Course) = elementsOf(course).maxOf { it.height }

    private fun cm(m: Double) = jsRound(m * 100).toInt()

    private fun isCombination(o: Obstacle) = o.elements.size == 2

    private fun mid(l: Line) = Vec2((l.a.x + l.b.x) / 2, (l.a.z + l.b.z) / 2)

    private fun ids(kinds: Set<ElementKind>) = kinds.map { it.id }.sorted()

    // ---- Rule 25: five courses ----

    @Test
    fun existWithTheNumbers1To5() {
        assertEquals(listOf(1, 2, 3, 4, 5), COURSES.map { it.id })
    }

    @Test
    fun haveThePrescribedNumberOfObstaclesACombinationCountsAsOne() {
        assertEquals(listOf(4, 5, 6, 7), COURSES.take(4).map { it.obstacles.size })
        val n5 = COURSES[4].obstacles.size
        assertTrue(n5 >= 8)
        assertTrue(n5 <= 10)
    }

    @Test
    fun p1CrossesOnly40To50cm() {
        val p1 = COURSES[0]
        assertEquals(setOf(ElementKind.CROSS), kindsOf(p1))
        for (e in elementsOf(p1)) {
            assertTrue(cm(e.height) >= 40)
            assertTrue(cm(e.height) <= 50)
        }
    }

    @Test
    fun p2CrossesAndVerticalsVerticals60cm() {
        val p2 = COURSES[1]
        assertEquals(listOf("cross", "vertical"), ids(kindsOf(p2)))
        for (e in elementsOf(p2)) {
            if (e.kind == ElementKind.VERTICAL) assertEquals(60, cm(e.height)) else assertTrue(cm(e.height) <= 60)
        }
    }

    @Test
    fun p3CrossesVerticalsFirstOxerUpTo70cm() {
        val p3 = COURSES[2]
        assertEquals(listOf("cross", "oxer", "vertical"), ids(kindsOf(p3)))
        assertTrue(cm(maxHeight(p3)) <= 70)
    }

    @Test
    fun p4VerticalsAndOxersMixedUpTo80cm() {
        val p4 = COURSES[3]
        assertEquals(listOf("oxer", "vertical"), ids(kindsOf(p4)))
        assertTrue(cm(maxHeight(p4)) <= 80)
    }

    @Test
    fun p5VerticalsOxersAtLeastOneDoubleCombinationUpTo85cm() {
        val p5 = COURSES[4]
        assertEquals(listOf("oxer", "vertical"), ids(kindsOf(p5)))
        assertTrue(p5.obstacles.count { isCombination(it) } >= 1)
        assertTrue(cm(maxHeight(p5)) <= 85)
    }

    @Test
    fun combinationOnlyInCourse5() {
        for (c in COURSES.take(4)) assertFalse(c.obstacles.any { isCombination(it) })
    }

    @Test
    fun everyCourseGetsHarderOrStaysTheSameHeight() {
        val heights = COURSES.map { maxHeight(it) }
        for (i in 1 until heights.size) assertTrue(heights[i] >= heights[i - 1])
    }

    // ---- Obstacle data in the course ----

    @Test
    fun numbers1ToNInOrderAllDirected() {
        for (c in COURSES) {
            assertEquals(c.obstacles.indices.map { it + 1 }, c.obstacles.map { it.number })
            assertTrue(c.obstacles.all { it.directed })
        }
    }

    @Test
    fun elementIdsAreUniqueAlsoAcrossCoursesAndFreeMode() {
        val ids = (COURSES.flatMap { elementsOf(it) } + elementsOf(FREE_LAYOUT.obstacles)).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (c in COURSES) {
            for (o in c.obstacles) {
                val elementIds = o.elements.map { it.id }
                if (isCombination(o)) {
                    assertEquals(listOf("p${c.id}-${o.number}a", "p${c.id}-${o.number}b"), elementIds)
                } else {
                    assertEquals(listOf("p${c.id}-${o.number}"), elementIds)
                }
            }
        }
    }

    @Test
    fun oxersHaveADepthCrossesAndVerticalsDoNot() {
        for (e in COURSES.flatMap { elementsOf(it) } + elementsOf(FREE_LAYOUT.obstacles)) {
            if (e.kind == ElementKind.OXER) assertTrue(e.spread > 0.5) else assertEquals(0.0, e.spread)
        }
    }

    @Test
    fun combinationBLiesCombiDistanceBehindAInJumpDirection() {
        val combos =
            (COURSES.map { it.obstacles } + listOf(FREE_LAYOUT.obstacles)).flatMap { l ->
                l.filter { isCombination(it) }
            }
        assertTrue(combos.isNotEmpty())
        for (o in combos) {
            val (a, b) = o.elements
            assertEquals(a.rot, b.rot)
            val local = toLocal(a, b.x, b.z)
            assertCloseTo(COMBI_DISTANCE, local.along, 6)
            assertCloseTo(0.0, local.across, 6)
        }
    }

    @Test
    fun obstaclesOnALineStandAtARelatedDistance5To6CanterStrides() {
        var lines = 0
        for (c in COURSES) {
            for (i in 1 until c.obstacles.size) {
                val prev = c.obstacles[i - 1].elements.last()
                val next = c.obstacles[i].elements[0]
                val local = toLocal(prev, next.x, next.z)
                if (prev.rot != next.rot || abs(local.across) > 1e-6 || local.along <= 0) continue
                lines++
                val ok =
                    listOf(5, 6).any { n ->
                        abs(local.along - relatedDistance(n, prev.spread, next.spread)) < 1e-6
                    }
                assertTrue(ok, "${prev.id} -> ${next.id}: ${local.along} m")
            }
        }
        assertTrue(lines >= 8)
    }

    @Test
    fun firstObstacleIsTheLowestInviting() {
        for (c in COURSES) {
            val lowest = elementsOf(c).minOf { it.height }
            assertEquals(lowest, c.obstacles[0].elements[0].height)
        }
    }

    // ---- Arena, approach corridors, lines ----

    @Test
    fun coursesKeepFenceDistanceHaveClearCorridorsNoOverlapAndLinesClear() {
        for (c in COURSES) {
            val lines = listOf(LayoutLine("start", c.start.a, c.start.b), LayoutLine("finish", c.finish.a, c.finish.b))
            assertEquals(emptyList(), checkLayout(c.obstacles, lines), "course ${c.id}")
        }
    }

    @Test
    fun startAndFinishLineAbout6mDirectionPerpendicularToTheLine() {
        for (c in COURSES) {
            for (l in listOf(c.start, c.finish)) {
                val dx = l.b.x - l.a.x
                val dz = l.b.z - l.a.z
                assertCloseTo(6.0, hypot(dx, dz), 6)
                assertCloseTo(1.0, hypot(l.dir.x, l.dir.z), 9)
                assertCloseTo(0.0, dx * l.dir.x + dz * l.dir.z, 9)
            }
        }
    }

    @Test
    fun startPositionAtHaltAFewMetersBeforeTheStartLineFacingTheLine() {
        for (c in COURSES) {
            val m = mid(c.start)
            val p = c.startPose
            val behind = (p.x - m.x) * c.start.dir.x + (p.z - m.z) * c.start.dir.z
            assertTrue(behind <= -3)
            assertTrue(behind >= -7)
            assertCloseTo(c.start.dir.x, sin(p.heading), 9)
            assertCloseTo(c.start.dir.z, cos(p.heading), 9)
            assertTrue(abs(p.x) <= ARENA.width / 2 - 3)
            assertTrue(abs(p.z) <= ARENA.length / 2 - 3)
        }
    }

    @Test
    fun idealLineWaypointsLieWithinTheArenaOneEntryPerLeg() {
        for (c in COURSES) {
            assertEquals(c.obstacles.size + 1, c.track.size)
            for (p in c.track.flatten()) {
                assertTrue(abs(p.x) <= ARENA.width / 2 - 1)
                assertTrue(abs(p.z) <= ARENA.length / 2 - 1)
            }
        }
    }

    @Test
    fun idealLineWaypointsLieInNoObstacle() {
        for (c in COURSES) {
            val fps = elementsOf(c).map { footprint(it) }
            for (p in c.track.flatten()) {
                val dot = LayoutRect(cx = p.x, cz = p.z, ux = 0.0, uz = 1.0, halfAlong = 0.5, halfAcross = 0.5)
                assertFalse(fps.any { rectsOverlap(dot, it) })
            }
        }
    }

    // ---- Rule 41: free mode ----

    private val free = FREE_LAYOUT

    @Test
    fun freeContainsAtLeastOneCrossOneVerticalOneOxerAndOneCombination() {
        val singles = free.obstacles.filter { !isCombination(it) }.flatMap { it.elements }
        val kinds = singles.map { it.kind }.toSet()
        assertTrue(ElementKind.CROSS in kinds)
        assertTrue(ElementKind.VERTICAL in kinds)
        assertTrue(ElementKind.OXER in kinds)
        assertTrue(free.obstacles.any { isCombination(it) })
    }

    @Test
    fun freeHeightsBetween40And85cmSpreadOut() {
        val heights = elementsOf(free.obstacles).map { cm(it.height) }
        assertEquals(40, heights.min())
        assertEquals(85, heights.max())
        assertTrue(heights.toSet().size >= 5)
    }

    @Test
    fun freeUndirectedAndWithoutNumbers() {
        for (o in free.obstacles) {
            assertFalse(o.directed)
            assertNull(o.number)
        }
    }

    @Test
    fun freeRoomToApproachFromBothDirectionsFenceDistanceNoOverlap() {
        assertEquals(emptyList(), checkLayout(free.obstacles))
    }

    @Test
    fun freeStartPositionIsClearOutsideAllCorridors() {
        val p = free.startPose
        val dot = LayoutRect(cx = p.x, cz = p.z, ux = 0.0, uz = 1.0, halfAlong = 1.5, halfAcross = 1.5)
        for (o in free.obstacles) assertFalse(rectsOverlap(dot, corridorOf(o)))
        assertTrue(abs(p.x) <= ARENA.width / 2 - 4)
        assertTrue(abs(p.z) <= ARENA.length / 2 - 4)
        assertCloseTo(1.0, axisOf(Placement(0.0, 0.0, p.heading)).z, 9)
    }

    // ---- courseById ----

    @Test
    fun findsACourseByNumberOrNumericString() {
        assertSame(COURSES[2], courseById(3))
        assertSame(COURSES[1], courseById("2"))
    }

    @Test
    fun fallsBackToTheFirstCourseForUnknownIds() {
        assertSame(COURSES[0], courseById(99))
        assertSame(COURSES[0], courseById(null as Int?))
        assertSame(COURSES[0], courseById("x"))
        assertSame(COURSES[0], courseById(null as String?))
    }

    // ---- parity with the web app (values printed by src/domain/course/courses.js) ----

    private class Golden(
        val id: Int,
        val pace: Pace,
        val allowedTimeS: Int,
        val idealLength: Double,
        val elements: Int,
        val waypoints: Int,
        val elementChecksum: Double,
        val trackChecksum: Double,
    )

    @Test
    fun layoutsMatchTheWebApp() {
        val golden =
            listOf(
                Golden(1, Pace.TROT, 64, 136.49173967136576, 4, 9, 28.099555921538755, 203.89949493661166),
                Golden(2, Pace.CANTER, 50, 192.3975884288003, 5, 12, 29.039379797371936, 38.68956732381494),
                Golden(3, Pace.CANTER, 66, 253.60042149744766, 6, 18, 42.78539816339746, 390.25281791297925),
                Golden(4, Pace.CANTER, 71, 274.3430017957206, 7, 18, 90.28893571891068, 372.74757378365405),
                Golden(5, Pace.CANTER, 88, 337.08255630870093, 9, 24, 91.87433388230812, -90.37157287525383),
            )
        for (g in golden) {
            val c = courseById(g.id)
            assertEquals(g.pace, c.pace)
            assertEquals(g.allowedTimeS, c.allowedTimeS)
            assertCloseTo(g.idealLength, idealLineLength(c), 9)
            assertEquals(g.elements, elementsOf(c).size)
            assertEquals(g.waypoints, c.track.flatten().size)
            val sx = elementsOf(c).fold(0.0) { a, e -> a + e.x + 2 * e.z + 3 * e.rot + 5 * e.height + 7 * e.spread }
            assertCloseTo(g.elementChecksum, sx, 9, "course ${g.id} elements")
            val tr = c.track.flatten().fold(0.0) { a, p -> a + p.x + 2 * p.z }
            assertCloseTo(g.trackChecksum, tr, 9, "course ${g.id} track")
        }
        val p1 = COURSES[0]
        assertEquals(-6.0, p1.startPose.x)
        assertEquals(-28.0, p1.startPose.z)
        assertCloseTo(kotlin.math.PI / 2, p1.startPose.heading, 12)
        assertEquals(Vec2(-13.0, -25.0), p1.finish.a)
        assertEquals(Vec2(-7.0, -25.0), p1.finish.b)
    }

    @Test
    fun freeLayoutMatchesTheWebApp() {
        fun check(
            e: Element,
            id: String,
            kind: ElementKind,
            height: Double,
            spread: Double,
            x: Double,
            z: Double,
        ) = assertEquals(Element(id, kind, height, spread, x, z, 0.0), e)

        val els = elementsOf(free.obstacles)
        assertEquals(6, els.size)
        check(els[0], "f1", ElementKind.CROSS, 0.4, 0.0, 13.0, -12.0)
        check(els[1], "f2", ElementKind.VERTICAL, 0.6, 0.0, 13.0, 12.0)
        check(els[2], "f3", ElementKind.OXER, 0.7, 0.7, -13.0, -12.0)
        check(els[3], "f4", ElementKind.OXER, 0.85, 0.9, -13.0, 12.0)
        check(els[4], "f5a", ElementKind.VERTICAL, 0.65, 0.0, 0.0, -COMBI_DISTANCE / 2)
        check(els[5], "f5b", ElementKind.OXER, 0.75, 0.8, 0.0, COMBI_DISTANCE / 2)
        assertEquals(-6.5, free.startPose.x)
        assertEquals(-26.0, free.startPose.z)
        assertEquals(0.0, free.startPose.heading)
    }
}
