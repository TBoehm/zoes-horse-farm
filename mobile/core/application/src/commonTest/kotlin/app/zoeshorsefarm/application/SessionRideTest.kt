package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.testing.rideCourse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The autopilot rides every course through the real ride session. The domain's rideability tests
// do the same with stand-ins for the session; both must agree on what happens (same seeds).
class SessionRideTest {
    @Test
    fun everyCourseIsFinishedThroughTheSessionExactlyOnce() {
        for (course in COURSES) {
            val summary = rideCourseThroughSession(course.id)
            assertTrue(summary.finished, "course ${course.id} not finished")
            assertEquals(1, summary.finishedCommands, "course ${course.id}")
        }
    }

    @Test
    fun theSessionRideMatchesTheDomainStandIn() {
        for (course in COURSES) {
            val viaSession = rideCourseThroughSession(course.id)
            val viaDomain = rideCourse(course.id)
            assertNotNull(viaDomain.result, "stand-in, course ${course.id}")
            assertEquals(viaDomain.result, viaSession.result, "result of course ${course.id}")
            assertEquals(viaDomain.jumps, viaSession.events.count { it is SimEvent.Landed }, "jumps of ${course.id}")
            assertEquals(
                viaDomain.refusals,
                viaSession.events.count { it is SimEvent.Refusal },
                "refusals ${course.id}",
            )
        }
    }

    @Test
    fun aFinishedRideIsSavedAndUnlocksTheNextCourse() {
        val rider = SessionCourseRider(1)
        var finished = false
        var t = 0.0
        while (t < rider.course.allowedTimeS * 3 + 60.0 && !finished) {
            finished = rider.step().commands.any { it is RideCommand.Finished }
            t += RIDER_DT
        }
        assertTrue(finished)
        assertEquals(1, rider.store.progress.finishedRides)
        assertEquals(2, rider.store.progress.unlocked)
        assertTrue(rider.store.progress.jumps >= rider.course.obstacles.size)
    }
}
