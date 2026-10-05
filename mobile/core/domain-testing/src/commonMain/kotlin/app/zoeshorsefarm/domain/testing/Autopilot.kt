package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.approachInfo
import app.zoeshorsefarm.domain.sim.axisOf
import app.zoeshorsefarm.domain.sim.headingOf
import app.zoeshorsefarm.domain.sim.wrapAngle
import app.zoeshorsefarm.domain.sim.zoneForElement
import app.zoeshorsefarm.shared.clamp
import kotlin.math.hypot
import kotlin.math.min

// Deterministic "autopilot" rider for rideability tests (test-only helper, not production code).
// It steers the real simulation: pure pursuit along the course track, perpendicular approach,
// speed hold and a jump press inside the take-off zone.

/** Straight run-in before the first element of an obstacle (m). */
const val APPROACH_LEN = 14.0

// the route runs this far beyond the finish line (m)
private const val OVERSHOOT = 4.0

// a route segment counts as passed this far before its end (m)
private const val NEAR_END = 1.0

private fun mid(line: Line) = Vec2((line.a.x + line.b.x) / 2, (line.a.z + line.b.z) / 2)

/**
 * Riding route of a course as a polyline: start pose -> start line -> track waypoints -> straight
 * approach point (perpendicular to the element) -> element centers -> ... -> finish line -> overshoot.
 */
fun routeOf(
    course: Course,
    approachLen: Double = APPROACH_LEN,
): List<Vec2> {
    val points = ArrayList<Vec2>()
    points.add(Vec2(course.startPose.x, course.startPose.z))
    points.add(mid(course.start))
    course.obstacles.forEachIndexed { i, obstacle ->
        points.addAll(course.track.getOrElse(i) { emptyList() })
        obstacle.elements.forEachIndexed { part, el ->
            if (part == 0) {
                val n = axisOf(el)
                val prev = points.last()
                val room = (el.x - prev.x) * n.x + (el.z - prev.z) * n.z
                val len = min(approachLen, room)
                if (len > 1) points.add(Vec2(el.x - n.x * len, el.z - n.z * len))
            }
            points.add(Vec2(el.x, el.z))
        }
    }
    points.addAll(course.track.getOrElse(course.obstacles.size) { emptyList() })
    val end = mid(course.finish)
    points.add(end)
    points.add(Vec2(end.x + course.finish.dir.x * OVERSHOOT, end.z + course.finish.dir.z * OVERSHOOT))
    return points
}

/** An element the autopilot has to jump, and in which direction (+1 / -1). */
data class RouteTarget(
    val el: Element,
    val dir: Int,
)

/** Elements of a course in riding order, all jumped in direction +1. */
fun courseTargets(course: Course): List<RouteTarget> =
    course.obstacles.flatMap { o -> o.elements.map { RouteTarget(it, 1) } }

/** Point `ahead` meters further along the polyline from the projection of p on segment s. */
private fun lookaheadPoint(
    points: List<Vec2>,
    s: Int,
    px: Double,
    pz: Double,
    ahead: Double,
): Vec2 {
    var remaining = ahead
    var ax = points[s].x
    var az = points[s].z
    val bx = points[s + 1].x
    val bz = points[s + 1].z
    val len = hypot(bx - ax, bz - az).let { if (it == 0.0) 1.0 else it }
    val t = clamp(((px - ax) * (bx - ax) + (pz - az) * (bz - az)) / (len * len), 0.0, 1.0)
    ax += (bx - ax) * t
    az += (bz - az) * t
    var i = s
    while (true) {
        val nx = points[i + 1].x
        val nz = points[i + 1].z
        val d = hypot(nx - ax, nz - az)
        if (d >= remaining || i + 2 >= points.size) {
            val k = if (d > 0) min(1.0, remaining / d) else 1.0
            return Vec2(ax + (nx - ax) * k, az + (nz - az) * k)
        }
        remaining -= d
        ax = nx
        az = nz
        i++
    }
}

/**
 * @param route polyline of the riding route
 * @param targets elements to jump, in order
 * @param speed target speed (m/s)
 * @param canter ride with the gallop input on
 * @param jitterS press time jitter, uniform within +-jitterS seconds (needs [rng])
 * @param rng seeded random source for the jitter
 * @param lookahead pure pursuit lookahead (m)
 */
class Autopilot(
    private val route: List<Vec2>,
    private val targets: List<RouteTarget>,
    private val speed: Double,
    private val canter: Boolean,
    private val jitterS: Double = 0.0,
    private val rng: () -> Double = { 0.5 },
    private val lookahead: Double = 3.5,
) {
    private var segment = 0
    private var index = 0
    private var pressed = false
    private var steps = 0
    private val delays = HashMap<Int, Double>()

    private fun delayFor(i: Int): Double = delays.getOrPut(i) { (rng() * 2 - 1) * jitterS }

    private fun steer(horse: Horse): Double {
        while (segment + 2 < route.size) {
            val a = route[segment]
            val b = route[segment + 1]
            val len = hypot(b.x - a.x, b.z - a.z).let { if (it == 0.0) 1.0 else it }
            val along = ((horse.x - a.x) * (b.x - a.x) + (horse.z - a.z) * (b.z - a.z)) / len
            if (along < len - NEAR_END) break
            segment++
        }
        val target = lookaheadPoint(route, segment, horse.x, horse.z, lookahead)
        val error = wrapAngle(headingOf(target.x - horse.x, target.z - horse.z) - horse.heading)
        return clamp(-3 * error, -1.0, 1.0)
    }

    /** Space as soon as the horse is in the middle of the take-off zone of the element due next. */
    private fun wantsJump(horse: Horse): Boolean {
        val target = targets.getOrNull(index)
        if (target == null || pressed || horse.jump != null || horse.speed < TUNING.speeds.trotMin) return false
        val info = approachInfo(target.el, horse, TUNING.approachDistance)
        if (info == null || !info.approaching || info.dir != target.dir) return false
        val zone = zoneForElement(target.el, horse.speed, TUNING)
        val aim = (zone.near + zone.far) / 2 - delayFor(index) * horse.speed
        if (info.distance > aim) return false
        pressed = true
        return true
    }

    /** All targets are landed. */
    val done: Boolean get() = index >= targets.size

    /** Next input for the current horse state. */
    fun next(horse: Horse): SimInput {
        val first = steps++ == 0
        val steer = steer(horse)
        val throttle = clamp((speed - horse.speed) * 2, -1.0, 1.0)
        // one step without gallop first: after a restart the horse gallops only on a fresh press
        val gallop = canter && !first
        return SimInput(steer = steer, throttle = throttle, gallop = gallop, jump = wantsJump(horse))
    }

    /** Feed the events of the last step back (landing -> next element, refusal -> press again). */
    fun onEvents(events: List<SimEvent>) {
        for (e in events) {
            if (e is SimEvent.Landed && e.elementId == targets.getOrNull(index)?.el?.id) {
                index++
                pressed = false
            }
            if (e is SimEvent.Refusal) pressed = false
        }
    }
}
