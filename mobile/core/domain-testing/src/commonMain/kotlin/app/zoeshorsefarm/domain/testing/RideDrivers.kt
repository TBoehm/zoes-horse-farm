package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.CourseRun
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.course.Pace
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.RidingSim
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimRules
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.axisOf
import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.domain.sim.headingOf
import app.zoeshorsefarm.domain.sim.movedBackwards

// Domain-level stand-ins for the application's ride session: they run the real simulation with the
// real course run (or the free layout) and do the same bookkeeping the session and the course /
// free mode do (line crossings, scored landings, refusals, rebuilding fallen rails), but without the
// store, the badges and the commands. Test-only, used by the rideability tests with the Autopilot.

/** Fallen rails are rebuilt after a delay; this keeps the pending rebuilds of one ride. */
private class RebuildTimers(
    private val sim: RidingSim,
) {
    private class Pending(
        val elementId: String,
        var left: Double,
    )

    private var pending = ArrayList<Pending>()

    fun cancel(elementId: String) {
        pending.removeAll { it.elementId == elementId }
    }

    fun rebuildIn(
        elementId: String,
        seconds: Double,
    ) {
        cancel(elementId)
        pending.add(Pending(elementId, seconds))
    }

    fun rebuildNow(elementId: String) {
        cancel(elementId)
        sim.rebuild(elementId)
    }

    fun tick(dt: Double) {
        if (pending.isEmpty()) return
        var due = false
        for (r in pending) {
            r.left -= dt
            if (r.left <= 0) {
                sim.rebuild(r.elementId)
                due = true
            }
        }
        if (due) pending = ArrayList(pending.filter { it.left > 0 })
    }
}

/** What one step of a [CourseRider] produced: the sim events and the result once the ride is over. */
class RideStep(
    val events: List<SimEvent>,
    val result: RideResult?,
)

/**
 * Rides a course of [COURSES] step by step with the [Autopilot] (like the web test helper
 * `createCourseRider`, but without the application's ride session).
 * Defaults: the pace of the course (trot: medium trot, canter: medium canter with gallop).
 */
class CourseRider(
    courseId: Int,
    speed: Double? = null,
    canter: Boolean? = null,
    jitterS: Double = 0.0,
    seed: Int = 1,
) {
    val course = COURSES.first { it.id == courseId }
    private val run = CourseRun(course)
    val sim = RidingSim(obstacles = course.obstacles, rules = run.rules, rng = createRng(seed))
    private val timers = RebuildTimers(sim)
    private var clockMs = 0.0
    private var finished = false

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
        sim.reset(course.startPose)
        sim.rebuildAll()
    }

    private fun handle(events: List<SimEvent>) {
        for (e in events) {
            if (e is SimEvent.Landed) {
                val res = run.onLanded(e.elementId, e.dir, e.knocked)
                // the poles of a scored knockdown stay down: drop a pending unscored rebuild
                if (res.scored && e.knocked) timers.cancel(e.elementId)
                val rebuildAfter = res.rebuildAfterS
                if (rebuildAfter != null && rebuildAfter != 0.0) timers.rebuildIn(e.elementId, rebuildAfter)
            } else if (e is SimEvent.Refusal) {
                run.onRefusal(e.elementId, e.dir)
            }
        }
    }

    /** One simulation step with the autopilot's input. */
    fun step(dt: Double = DT): RideStep {
        if (finished) return RideStep(emptyList(), null)
        val prevX = sim.horse.x
        val prevZ = sim.horse.z
        val events = sim.step(dt, pilot.next(sim.horse))
        handle(events)
        // course mode update: the course clock runs while riding, line crossings need the step
        if (run.phase == RunPhase.RIDING) clockMs += dt * 1000
        val horse = sim.horse
        // a line crossed in rein-back does not count (rule 9)
        run.onLineCross(
            prevX,
            prevZ,
            horse.x,
            horse.z,
            clockMs,
            backwards = movedBackwards(prevX, prevZ, horse.x, horse.z, horse.heading),
        )
        run.update(horse, clockMs)
        for (id in run.drainRebuilds()) timers.rebuildNow(id)
        timers.tick(dt)
        pilot.onEvents(events)
        if (run.phase == RunPhase.FINISHED) {
            finished = true
            return RideStep(events, run.result)
        }
        return RideStep(events, null)
    }
}

/** Summary of a whole ride over a course. */
class CourseRideSummary(
    val finished: Boolean,
    val result: RideResult?,
    val timeS: Double,
    val allowedS: Int,
    val events: List<SimEvent>,
    val refusals: Int,
    val knockdowns: Int,
    val jumps: Int,
)

/**
 * Rides a whole course with the autopilot. Defaults: the pace of the course (trot: medium trot,
 * canter: medium canter with gallop); [maxT] defaults to three times the allowed time plus a minute.
 */
fun rideCourse(
    courseId: Int,
    speed: Double? = null,
    canter: Boolean? = null,
    jitterS: Double = 0.0,
    seed: Int = 1,
    maxT: Double? = null,
): CourseRideSummary {
    val rider = CourseRider(courseId, speed, canter, jitterS, seed)
    val limit = maxT ?: (rider.course.allowedTimeS * 3 + 60.0)
    val events = ArrayList<SimEvent>()
    var result: RideResult? = null
    var t = 0.0
    while (t < limit && result == null) {
        val out = rider.step()
        events.addAll(out.events)
        result = out.result
        t += DT
    }
    return CourseRideSummary(
        finished = result != null,
        result = result,
        timeS = if (result != null) result.timeCs / 100.0 else t,
        allowedS = rider.course.allowedTimeS,
        events = events,
        refusals = events.count { it is SimEvent.Refusal },
        knockdowns = events.count { it is SimEvent.RailDown },
        jumps = events.count { it is SimEvent.Landed },
    )
}

/** Summary of a ride over one obstacle of the free layout. */
class FreeRideSummary(
    val finished: Boolean,
    val jumped: List<String>,
    val refusals: Int,
    val knockdowns: Int,
)

/**
 * Rides one obstacle of the free layout in direction [dir] (+1 / -1): starts at rest, in front of
 * the obstacle on its straight run-in, and jumps all its elements in order at medium canter.
 */
fun rideFreeObstacle(
    obstacle: Obstacle,
    dir: Int,
    seed: Int = 1,
    maxT: Double = 30.0,
    jitterS: Double = 0.0,
): FreeRideSummary {
    val ordered = if (dir > 0) obstacle.elements else obstacle.elements.reversed()
    val first = ordered.first()
    val n = axisOf(first)
    val from = Vec2(first.x - dir * n.x * APPROACH_LEN, first.z - dir * n.z * APPROACH_LEN)
    val last = ordered.last()
    val to = Vec2(last.x + dir * n.x * 8, last.z + dir * n.z * 8)
    val heading = headingOf(dir * n.x, dir * n.z)

    val sim = RidingSim(obstacles = FREE_LAYOUT.obstacles, rules = SimRules { _, _ -> true }, rng = createRng(seed))
    val timers = RebuildTimers(sim)
    sim.reset(Pose(from.x, from.z, heading))
    sim.rebuildAll()
    val pilot =
        Autopilot(
            route = listOf(from, to),
            targets = ordered.map { RouteTarget(it, dir) },
            speed = TUNING.speeds.canterMedium,
            canter = true,
            jitterS = jitterS,
            rng = createRng(seed + 1000),
        )
    val events = ArrayList<SimEvent>()
    var t = 0.0
    while (t < maxT && !pilot.done) {
        val out = sim.step(DT, pilot.next(sim.horse))
        // free mode: fallen rails are rebuilt after the tuning delay
        for (e in out) if (e is SimEvent.RailDown) timers.rebuildIn(e.elementId, TUNING.rebuildDelayS)
        timers.tick(DT)
        pilot.onEvents(out)
        events.addAll(out)
        t += DT
    }
    return FreeRideSummary(
        finished = pilot.done,
        jumped = events.filterIsInstance<SimEvent.Landed>().map { it.elementId },
        refusals = events.count { it is SimEvent.Refusal },
        knockdowns = events.count { it is SimEvent.RailDown },
    )
}
