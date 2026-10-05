package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.RidingSim
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.SimRules
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Tuning
import app.zoeshorsefarm.domain.sim.axisOf
import app.zoeshorsefarm.domain.sim.blockExtents
import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.domain.sim.crossAxisOf
import app.zoeshorsefarm.domain.sim.headingOf
import app.zoeshorsefarm.domain.sim.toLocal
import app.zoeshorsefarm.domain.sim.wrapAngle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Helpers for the sim tests (test support, not production code).

const val DEG = PI / 180
const val DT = 1.0 / 60

private var nextId = 1

/** Creates an element; oxers get [spread] (default 0.7 m), every other kind a spread of 0. */
fun makeElement(
    kind: ElementKind,
    height: Double,
    id: String? = null,
    spread: Double = 0.7,
    x: Double = 0.0,
    z: Double = 0.0,
    rot: Double = 0.0,
): Element =
    Element(
        id = id ?: "e${nextId++}",
        kind = kind,
        height = height,
        spread = if (kind == ElementKind.OXER) spread else 0.0,
        x = x,
        z = z,
        rot = rot,
    )

/** One undirected obstacle without a number per element. */
fun obstaclesOf(vararg elements: Element): List<Obstacle> = elements.map { Obstacle(null, listOf(it), false) }

fun obstaclesOf(elements: List<Element>): List<Obstacle> = elements.map { Obstacle(null, listOf(it), false) }

/** RNG wrapper that counts the number of draws. */
class CountingRng(
    seed: Int,
) : () -> Double {
    private val base = createRng(seed)

    var calls = 0
        private set

    override fun invoke(): Double {
        calls++
        return base()
    }
}

/** A sim over [elements] (one undirected obstacle each) or over explicit [obstacles]. */
fun makeSim(
    elements: List<Element>,
    seed: Int = 1,
    rng: (() -> Double)? = null,
    canRefuse: ((String, Int) -> Boolean)? = null,
    obstacles: List<Obstacle>? = null,
    tuning: Tuning = TUNING,
): RidingSim =
    RidingSim(
        obstacles = obstacles ?: obstaclesOf(elements),
        rules = SimRules { id, dir -> canRefuse?.invoke(id, dir) ?: true },
        rng = rng ?: createRng(seed),
        tuning = tuning,
    )

/**
 * Places the horse [distance] m before the leading edge (direction [dir]), with course angle
 * [angle] (positive = course drifts toward +t) and lateral offset [crossing] at the obstacle plane.
 */
fun placeBefore(
    sim: RidingSim,
    el: Element,
    distance: Double,
    angle: Double = 0.0,
    dir: Int = 1,
    crossing: Double = 0.0,
    speed: Double = 0.0,
    gallop: Boolean = false,
) {
    val n = axisOf(el)
    val t = crossAxisOf(el)
    val halfSpread = el.spread / 2
    val along = -dir * (halfSpread + distance)
    val fAcross = sin(angle)
    val fAlong = dir * cos(angle)
    val across = crossing - (fAcross * abs(along)) / cos(angle)
    val x = el.x + along * n.x + across * t.x
    val z = el.z + along * n.z + across * t.z
    val heading = headingOf(fAlong * n.x + fAcross * t.x, fAlong * n.z + fAcross * t.z)
    sim.reset(x, z, heading, speed, gallop)
}

/** Result of [drive]: all events, the simulated time and the number of steps. */
class DriveResult(
    val events: List<SimEvent>,
    val t: Double,
    val steps: Int,
)

/**
 * Runs the sim. [input] is a function (sim, t) -> input; [until] (sim, events of the step, t) ends
 * early; [onStep] sees every step.
 */
fun drive(
    sim: RidingSim,
    input: (RidingSim, Double) -> SimInput,
    until: ((RidingSim, List<SimEvent>, Double) -> Boolean)? = null,
    maxT: Double = 15.0,
    dt: Double = DT,
    onStep: ((RidingSim, List<SimEvent>, Double) -> Unit)? = null,
): DriveResult {
    val events = ArrayList<SimEvent>()
    var t = 0.0
    var steps = 0
    while (t < maxT) {
        val ev = sim.step(dt, input(sim, t))
        events.addAll(ev)
        t += dt
        steps++
        onStep?.invoke(sim, ev, t)
        if (until != null && until(sim, ev, t)) break
    }
    return DriveResult(events, t, steps)
}

/** [drive] with a constant input. */
fun drive(
    sim: RidingSim,
    input: SimInput,
    until: ((RidingSim, List<SimEvent>, Double) -> Boolean)? = null,
    maxT: Double = 15.0,
    dt: Double = DT,
    onStep: ((RidingSim, List<SimEvent>, Double) -> Unit)? = null,
): DriveResult = drive(sim, { _, _ -> input }, until, maxT, dt, onStep)

/** Input that presses Space as soon as the horse is [distanceFn] m before the leading edge. */
fun pressAt(
    base: SimInput,
    distanceFn: (RidingSim, SimApproach) -> Double?,
): (RidingSim, Double) -> SimInput {
    var pressed = false
    return { sim, _ ->
        val a = sim.approach
        var press = false
        if (!pressed && a != null && sim.horse.jump == null) {
            val target = distanceFn(sim, a)
            if (target != null && a.distance <= target) {
                pressed = true
                press = true
            }
        }
        base.copy(jump = press)
    }
}

/** [pressAt] with a fixed distance. */
fun pressAt(
    base: SimInput,
    distance: Double,
): (RidingSim, Double) -> SimInput = pressAt(base) { _, _ -> distance }

/** The events of one type, in order. */
inline fun <reified T : SimEvent> List<SimEvent>.ofType(): List<T> = filterIsInstance<T>()

/** Turns on the spot at halt until the heading [heading] is reached. */
fun turnInPlace(
    sim: RidingSim,
    heading: Double,
): DriveResult =
    drive(
        sim,
        { s, _ -> SimInput(steer = if (wrapAngle(s.horse.heading - heading) > 0) 1.0 else -1.0) },
        until = { s, _, _ -> abs(wrapAngle(s.horse.heading - heading)) < 0.03 },
        maxT = 10.0,
    )

/** Brings the horse to a halt with S. */
fun brakeToHalt(sim: RidingSim): DriveResult =
    drive(sim, SimInput(throttle = -1.0), until = { s, _, _ -> s.horse.speed == 0.0 }, maxT = 10.0)

/** True if the reference point is inside an element's blocked area (horse inside the obstacle). */
fun insideBlock(
    sim: RidingSim,
    el: Element,
): Boolean {
    val ext = blockExtents(el, TUNING)
    val p = toLocal(el, sim.horse.x, sim.horse.z)
    return abs(p.along) < ext.along - 1e-6 && abs(p.across) < ext.across - 1e-6
}
