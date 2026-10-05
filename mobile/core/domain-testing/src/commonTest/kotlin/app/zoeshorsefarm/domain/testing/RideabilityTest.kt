package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.sim.SimEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Rideability of the courses and the free layout with the real simulation (concept rules 15-22,
// 25, 31, 33, 41; SRT-004 "course 1 can be done at trot without time faults").
// The autopilot is deterministic; the sloppy variant jitters the jump timing with a seeded rng.
// The web test rides through the application's ride session; here the domain-level drivers of
// this module stand in for it (same sim, course run and rebuild rules).
class RideabilityTest {
    private fun jumpsOf(course: Course) = course.obstacles.sumOf { it.elements.size }

    private fun summary(
        id: Int,
        ride: CourseRideSummary,
    ) = "course $id: finished=${ride.finished} t=${ride.timeS}s allowed=${ride.allowedS}s ${ride.result?.faults} " +
        "refusals=${ride.refusals} knockdowns=${ride.knockdowns}"

    // ---- course 1 at trot ----

    private val course1 by lazy { rideCourse(1) }

    @Test
    fun course1AtTrotFinishesWith0FaultsAndWithoutTimeFaultsRule33() {
        val ride = course1
        assertTrue(ride.finished, summary(1, ride))
        val result = assertNotNull(ride.result)
        assertEquals(Faults(knockdowns = 0, refusals = 0, timeFaults = 0, total = 0), result.faults, summary(1, ride))
        assertEquals(3, result.stars)
        assertTrue(ride.timeS <= ride.allowedS)
    }

    @Test
    fun course1AtTrotJumpsEveryObstacleExactlyOnce() {
        assertEquals(jumpsOf(COURSES[0]), course1.jumps)
    }

    // ---- courses 2 to 5 at medium canter ----

    private val canterRides by lazy { COURSES.filter { it.id > 1 }.associate { it.id to rideCourse(it.id) } }

    @Test
    fun coursesAtMediumCanterHaveNoKnockdownAndNoRefusalAndFinishWithinTheAllowedTime() {
        for (course in COURSES.filter { it.id > 1 }) {
            val ride = canterRides.getValue(course.id)
            val text = summary(course.id, ride)
            assertTrue(ride.finished, text)
            assertEquals(0, ride.knockdowns, text)
            assertEquals(0, ride.refusals, text)
            assertTrue(ride.timeS <= ride.allowedS, text)
            val result = assertNotNull(ride.result)
            assertEquals(0, result.faults.total)
            assertEquals(3, result.stars)
        }
    }

    @Test
    fun coursesAtMediumCanterJumpEachElementOnce() {
        for (course in COURSES.filter { it.id > 1 }) {
            assertEquals(jumpsOf(course), canterRides.getValue(course.id).jumps, "course ${course.id}")
        }
    }

    // ---- combination of course 5 ----

    @Test
    fun combinationOfCourse5IsJumpedAThenBAtCanterWithBothLandedAndNoRefusal() {
        val course = COURSES.first { it.id == 5 }
        val combo = course.obstacles.first { it.elements.size == 2 }
        val ride = canterRides.getValue(5)
        val landed = ride.events.filterIsInstance<SimEvent.Landed>().map { it.elementId }
        val (a, b) = combo.elements.map { it.id }
        assertTrue(a in landed)
        assertEquals(landed.indexOf(a) + 1, landed.indexOf(b))
        assertEquals(emptyList(), ride.events.filterIsInstance<SimEvent.Refusal>())
    }

    // ---- free layout (rule 41) ----

    @Test
    fun freeLayoutEveryObstacleIsJumpedCleanlyInBothDirectionsWithTheAutopilot() {
        for (obstacle in FREE_LAYOUT.obstacles) {
            for (dir in listOf(1, -1)) {
                val name = "${obstacle.elements[0].id} ($dir)"
                val ride = rideFreeObstacle(obstacle, dir)
                assertTrue(ride.finished, name)
                assertEquals(0, ride.refusals, name)
                assertEquals(0, ride.knockdowns, name)
                val expected = obstacle.elements.map { it.id }
                assertEquals(if (dir > 0) expected else expected.reversed(), ride.jumped, name)
            }
        }
    }

    // ---- sloppy rider (rule 15: forgiving for a 9-year-old) ----

    @Test
    fun course1AtTrotWithTimingJitterOf015sStillFinishesSeeds1To8() {
        for (seed in 1..8) {
            val ride = rideCourse(1, jitterS = 0.15, seed = seed)
            val text = summary(1, ride)
            assertTrue(ride.finished, text)
            assertEquals(0, ride.refusals, text)
            assertEquals(0, assertNotNull(ride.result).faults.knockdowns, text)
            assertTrue(ride.timeS <= ride.allowedS, text)
        }
    }

    // ---- autopilot sanity (negative controls) ----

    @Test
    fun runsIntoARefusalWhenItTrotsACourseWithVerticalsRule16() {
        val ride = rideCourse(2, canter = false, speed = 3.2, maxT = 80.0)
        assertTrue(ride.refusals > 0)
        assertEquals(false, ride.finished)
    }

    @Test
    fun knocksPolesOrRefusesWhenTheTimingIsFarOffJitterOf03sOnCourse5() {
        val faults =
            (1..5).map { seed ->
                val ride = rideCourse(5, jitterS = 0.3, seed = seed)
                ride.knockdowns + ride.refusals
            }
        assertTrue(faults.max() > 0)
    }

    // ---- the route of the autopilot ----

    @Test
    fun theRouteRunsFromTheStartPoseOverTheStartLineToBeyondTheFinishLine() {
        for (course in COURSES) {
            val route = routeOf(course)
            assertEquals(course.startPose.x, route.first().x)
            assertEquals(course.startPose.z, route.first().z)
            val finishMid = (course.finish.a.x + course.finish.b.x) / 2
            assertTrue(route.size > course.obstacles.size + 3)
            // the last point lies 4 m beyond the finish line center in riding direction
            assertEquals(finishMid + course.finish.dir.x * 4, route.last().x, 1e-9)
            assertEquals(jumpsOf(course), courseTargets(course).size)
        }
    }
}
