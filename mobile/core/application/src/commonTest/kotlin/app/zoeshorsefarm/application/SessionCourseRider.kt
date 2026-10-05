package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.modes.CourseMode
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Pace
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.domain.testing.Autopilot
import app.zoeshorsefarm.domain.testing.courseTargets
import app.zoeshorsefarm.domain.testing.routeOf

// Session-level counterpart of the domain's CourseRider (tests/support/autopilot.js,
// createCourseRider / rideCourse): the autopilot rides a course through the REAL ride session
// (course mode, store, badges, commands). It lives in this module's tests because the testing
// modules may not depend on each other.

const val RIDER_DT = 1.0 / 60

/**
 * Rides course [courseId] step by step with the autopilot through a [RideSession].
 * Defaults: the pace of the course (trot: medium trot, canter: medium canter with gallop).
 */
class SessionCourseRider(
    courseId: Int,
    val store: FakeStore = FakeStore(),
    speed: Double? = null,
    canter: Boolean? = null,
    jitterS: Double = 0.0,
    seed: Int = 1,
) {
    val course = COURSES.first { it.id == courseId }
    val session = RideSession(CourseMode(courseId), store, FixedClock(), createRng(seed))
    private val pilot: Autopilot

    init {
        val trot = course.pace == Pace.TROT
        pilot =
            Autopilot(
                route = routeOf(course),
                targets = courseTargets(course),
                speed = speed ?: if (trot) TUNING.speeds.trotMedium else TUNING.speeds.canterMedium,
                canter = canter ?: !trot,
                jitterS = jitterS,
                rng = createRng(seed + 1000),
            )
    }

    /** One step with the autopilot's input; the result is reused by the next step. */
    fun step(): StepResult {
        val input: SimInput = pilot.next(session.view.horse)
        val out = session.step(RIDER_DT, input)
        pilot.onEvents(out.events)
        return out
    }
}

/** Summary of a whole ride through the session. */
class SessionRideSummary(
    val finished: Boolean,
    val result: RideResult?,
    val events: List<SimEvent>,
    val finishedCommands: Int,
)

/** Rides a whole course with the autopilot through the real ride session. */
fun rideCourseThroughSession(
    courseId: Int,
    seed: Int = 1,
): SessionRideSummary {
    val rider = SessionCourseRider(courseId, seed = seed)
    val limit = rider.course.allowedTimeS * 3 + 60.0
    val events = ArrayList<SimEvent>()
    var result: RideResult? = null
    var finishedCommands = 0
    var t = 0.0
    while (t < limit && result == null) {
        val out = rider.step()
        events.addAll(out.events)
        for (command in out.commands) {
            if (command is RideCommand.Finished) {
                finishedCommands += 1
                result = command.params.result
            }
        }
        t += RIDER_DT
    }
    return SessionRideSummary(result != null, result, events, finishedCommands)
}
