package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.POLE_LENGTH
import app.zoeshorsefarm.domain.sim.Placement
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertClose(
    expected: Double,
    actual: Double,
    tolerance: Double = 1e-9,
) = assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

class CrossingArrowTest {
    private val line = Line(a = Vec2(0.0, 0.0), b = Vec2(0.0, 6.0), dir = Vec2(1.0, 0.0))

    @Test
    fun startsAndEndsOnTheMiddleOfTheLinePointingInTheRidingDirection() {
        val (from, to) = crossingArrow(line, 4.0)
        // the middle of the line is (0, 3); riding direction +x
        assertClose(-2.0, from.x)
        assertClose(3.0, from.z)
        assertClose(2.0, to.x)
        assertClose(3.0, to.z)
    }

    @Test
    fun followsTheDirectionOfEveryStartAndFinishLineOfTheCourses() {
        for (course in COURSES) {
            for (l in listOf(course.start, course.finish)) {
                val (from, to) = crossingArrow(l, 4.0)
                val dx = to.x - from.x
                val dz = to.z - from.z
                assertClose(4.0, hypot(dx, dz))
                assertClose(4.0, dx * l.dir.x + dz * l.dir.z)
                // crosses the line at right angles
                val lx = l.b.x - l.a.x
                val lz = l.b.z - l.a.z
                assertClose(0.0, dx * lx + dz * lz)
            }
        }
    }
}

class FlagPointsTest {
    private fun at(rot: Double) = Placement(x = 2.0, z = -5.0, rot = rot)

    @Test
    fun jumpingTowardsPlusZTheRightHandSideIsMinusX() {
        val (red, white) = flagPoints(at(0.0))
        assertTrue(red.x < 2)
        assertTrue(white.x > 2)
        assertClose(-5.0, red.z)
        assertClose(-5.0, white.z)
    }

    @Test
    fun jumpingTowardsPlusXTheRightHandSideIsPlusZ() {
        val (red, white) = flagPoints(at(PI / 2))
        assertTrue(red.z > -5)
        assertTrue(white.z < -5)
        assertClose(2.0, red.x)
    }

    @Test
    fun jumpingTowardsMinusZTheRightHandSideIsPlusX() {
        val (red, white) = flagPoints(at(PI))
        assertTrue(red.x > 2)
        assertTrue(white.x < 2)
    }

    @Test
    fun theFlagsStandJustOutsideThePoleEndsSymmetricToTheElement() {
        val (red, white) = flagPoints(at(0.7))
        val dRed = hypot(red.x - 2, red.z + 5)
        val dWhite = hypot(white.x - 2, white.z + 5)
        assertClose(dRed, dWhite)
        assertTrue(dRed > POLE_LENGTH / 2)
    }

    @Test
    fun everyCourseElementHasTheRedFlagOnTheRightOfTheRider() {
        for (course in COURSES) {
            for (obstacle in course.obstacles) {
                for (el in obstacle.elements) {
                    val red = flagPoints(el).red
                    // right-hand vector of a rider facing n = (sin, cos): n x up = (-cos, sin)
                    val dot = (red.x - el.x) * -cos(el.rot) + (red.z - el.z) * sin(el.rot)
                    assertTrue(dot > 0)
                }
            }
        }
    }
}

class CoursePlanTest {
    private val course = COURSES[0]
    private val plan =
        buildCoursePlan(course, width = 520.0, height = 297.0, startLabel = "Start", finishLabel = "Finish")

    private inline fun <reified T : PlanShape> shapes() = plan.shapes.filterIsInstance<T>()

    @Test
    fun scalesTheArenaToFitWithTheFourteenPixelPadding() {
        val expected = min((520.0 - 28) / ARENA.length, (297.0 - 28) / ARENA.width)
        assertClose(expected, plan.scale)
        assertEquals(520.0, plan.width)
        assertEquals(297.0, plan.height)
    }

    @Test
    fun theArenaIsTheFirstShapeAndCentredLengthToTheRight() {
        val arena = plan.shapes.first() as PlanRoundRect
        assertClose(ARENA.length * plan.scale, arena.width)
        assertClose(ARENA.width * plan.scale, arena.height)
        assertClose(520.0 / 2 - ARENA.length / 2 * plan.scale, arena.x)
        assertClose(297.0 / 2 - ARENA.width / 2 * plan.scale, arena.y)
        assertEquals(10.0, arena.radius)
    }

    @Test
    fun mapsAWorldPointWithLengthToTheRightAndWidthUpwards() {
        val (px, py) = plan.project(x = 5.0, z = 10.0)
        assertClose(260.0 + 10 * plan.scale, px)
        assertClose(148.5 - 5 * plan.scale, py)
    }

    @Test
    fun startAndFinishLinesAreDashedAndLabelled() {
        val dashed = shapes<PlanLine>().filter { it.dash != null }
        assertEquals(2, dashed.size)
        assertEquals(listOf(6.0, 5.0), dashed[0].dash)
        val labels = shapes<PlanLabel>().map { it.text }
        assertTrue("Start" in labels)
        assertTrue("Finish" in labels)
    }

    @Test
    fun withoutLabelsNoLineLabelIsDrawn() {
        val bare = buildCoursePlan(course, 520.0, 297.0, startLabel = null, finishLabel = null)
        val texts = bare.shapes.filterIsInstance<PlanLabel>().map { it.text }
        assertEquals(course.obstacles.mapNotNull { it.number }.map { it.toString() }, texts)
    }

    @Test
    fun everyElementHasItsPolesAndTwoFlagsRedFirst() {
        val flags = shapes<PlanCircle>().filter { it.stroke == PlanColors.FLAG_STROKE }
        val elements = course.obstacles.sumOf { it.elements.size }
        assertEquals(elements * 2, flags.size)
        assertEquals(PlanColors.FLAG_RED, flags[0].fill)
        assertEquals(PlanColors.FLAG_WHITE, flags[1].fill)
        val poles = shapes<PlanLine>().filter { it.color == PlanColors.POLE }
        val expected = course.obstacles.flatMap { it.elements }.sumOf { if (it.spread > 0) 2 else 1 }
        assertEquals(expected, poles.size)
        assertTrue(poles.all { it.roundCap })
    }

    @Test
    fun everyNumberedObstacleGetsANumberBadge() {
        val badges = shapes<PlanLabel>().filter { it.color == PlanColors.NUMBER }
        assertEquals(course.obstacles.mapNotNull { it.number }.map { it.toString() }, badges.map { it.text })
        assertTrue(badges.all { it.middleBaseline })
    }

    @Test
    fun theSizesGrowWithTheScaleOnABigPlan() {
        val big = buildCoursePlan(course, 1400.0, 800.0, "Start", "Finish")
        val small = plan
        val bigFlag = big.shapes.filterIsInstance<PlanCircle>().first { it.fill == PlanColors.FLAG_RED }
        val smallFlag = small.shapes.filterIsInstance<PlanCircle>().first { it.fill == PlanColors.FLAG_RED }
        assertTrue(bigFlag.radius > smallFlag.radius)
        assertClose(maxOf(3.0, big.scale * 0.4), bigFlag.radius)
    }

    @Test
    fun everyCourseCanBeDrawn() {
        for (c in COURSES) {
            val drawn = buildCoursePlan(c, 520.0, 297.0, "Start", "Finish")
            assertTrue(drawn.shapes.size > 10, "course ${c.id}")
        }
    }
}
