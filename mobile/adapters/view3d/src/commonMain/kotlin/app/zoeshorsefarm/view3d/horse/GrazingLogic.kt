package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.createSpring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

// Behaviour of a grazing horse (pure): grazes with the head down for a long time, now and then
// lifts the head and looks around, and walks a few slow steps to a new spot inside the paddock.
// Randomness comes from an injected rng.
//
// Positions are world x/z of the BODY CENTRE of the horse (the horse object itself has its origin on
// the ground below the forelegs, GRAZING.bodyOffset ahead of the centre, see Grazing.kt). The whole
// horse, in every pose and also while it turns on the spot, lies within GRAZING.bodyRadius of the
// centre, so the fence and the props are kept at that distance. The heading follows the
// simulation: the forward direction is (sin h, cos h) and h grows to the left, so a positive turn
// rate (right turn) lowers it.

/** What a grazer does at the moment. */
enum class GrazerState(
    val id: String,
) {
    GRAZE("graze"),
    LOOK("look"),
    TURN("turn"),
    WALK("walk"),
}

/** Extent (m) of the horse model around its object origin, see [HORSE_EXTENT]. */
class HorseExtent(
    val front: Double,
    val back: Double,
    val side: Double,
)

/**
 * Extent (m) of the horse model around its object origin (ground below the forelegs): in front
 * (head up, the farthest), behind (tail) and to the sides, over all poses of a paddock horse.
 * Measured on the model, and checked against it in GrazingTest.
 */
val HORSE_EXTENT = HorseExtent(front = 1.22, back = 2.05, side = 0.8)

private val BODY_RADIUS = (HORSE_EXTENT.back + HORSE_EXTENT.front) / 2 + 0.075

/** Technical values of the paddock behaviour (look, not game play). */
class GrazingTuning(
    /** s with the head down before something happens. */
    val grazeTime: ClosedFloatingPointRange<Double> = 12.0..30.0,
    /** s with the head up. */
    val lookTime: ClosedFloatingPointRange<Double> = 2.5..6.0,
    /** Chance that a look ends with a few steps (otherwise back to the grass). */
    val lookThenMove: Double = 0.7,
    /** m to the next spot. */
    val stepDistance: ClosedFloatingPointRange<Double> = 2.5..6.0,
    /** m/s, a slow walk. */
    val walkSpeed: Double = 0.85,
    /** m/s^2. */
    val walkAccel: Double = 0.8,
    /** rad/s while turning on the spot. */
    val turnRate: Double = 0.9,
    /** Turn rate per rad of heading error while walking. */
    val steerGain: Double = 1.6,
    /** rad/s. */
    val maxSteer: Double = 0.5,
    /** m: the target is reached. */
    val arrive: Double = 0.3,
    /** m/s: slowest walk before the spot. */
    val creepSpeed: Double = 0.25,
    /** 1/s, how fast the head goes down / up (95 % after ~ 1.8 s). */
    val headOmega: Double = 2.6,
    /**
     * m: the origin of the horse object is this far ahead of the body centre (the centre is the
     * middle between the nose and the tail).
     */
    val bodyOffset: Double = (HORSE_EXTENT.back - HORSE_EXTENT.front) / 2,
    /** m: the whole horse lies within this radius of the body centre (any pose, also while it turns on the spot). */
    val bodyRadius: Double = BODY_RADIUS,
    /** m: the body centre keeps this far from the fence (room to arrive). */
    val margin: Double = BODY_RADIUS + 0.2,
    /** m: the horses keep this far from each other when they pick a spot. */
    val separation: Double = 3.0,
    val candidates: Int = 8,
)

val GRAZING = GrazingTuning()

/** A position in the world (x/z). */
interface Located {
    val x: Double
    val z: Double
}

/** A mutable x/z pair (also the result of the area helpers). */
class XZ(
    override var x: Double = 0.0,
    override var z: Double = 0.0,
) : Located

/** A keep-out circle: the body centre of a horse neither stands in nor walks through it. */
class AvoidCircle(
    val x: Double,
    val z: Double,
    val r: Double,
)

/**
 * Area: a rectangle around ([x], [z]), turned about Y by [rotation]. [avoid]: circles (shelter, trough,
 * ...) that the body centre of a horse neither stands in nor walks through (world coordinates):
 * r = radius of the prop + GRAZING.bodyRadius.
 */
class PaddockArea(
    val x: Double,
    val z: Double,
    val width: Double,
    val depth: Double,
    val rotation: Double = 0.0,
    val avoid: List<AvoidCircle>? = null,
) {
    /** The same area with other keep-out circles. */
    fun withAvoid(circles: List<AvoidCircle>?) = PaddockArea(x, z, width, depth, rotation, circles)
}

fun toLocal(
    area: PaddockArea,
    x: Double,
    z: Double,
    out: XZ = XZ(),
): XZ {
    val dx = x - area.x
    val dz = z - area.z
    val c = cos(area.rotation)
    val s = sin(area.rotation)
    // inverse of the rotation about Y (three.js: x' = x c + z s, z' = -x s + z c)
    out.x = dx * c - dz * s
    out.z = dx * s + dz * c
    return out
}

fun toWorld(
    area: PaddockArea,
    lx: Double,
    lz: Double,
    out: XZ = XZ(),
): XZ {
    val c = cos(area.rotation)
    val s = sin(area.rotation)
    out.x = area.x + lx * c + lz * s
    out.z = area.z - lx * s + lz * c
    return out
}

private val scratch = XZ()

/** Is (x, z) inside the area, [margin] m away from the border? */
fun insideArea(
    area: PaddockArea,
    x: Double,
    z: Double,
    margin: Double = 0.0,
): Boolean {
    val p = toLocal(area, x, z, scratch)
    return abs(p.x) <= area.width / 2 - margin && abs(p.z) <= area.depth / 2 - margin
}

/** Distance from the point (px, pz) to the segment a -> b. */
fun distanceToSegment(
    px: Double,
    pz: Double,
    ax: Double,
    az: Double,
    bx: Double,
    bz: Double,
): Double {
    val dx = bx - ax
    val dz = bz - az
    val len2 = dx * dx + dz * dz
    val t = if (len2 > 0) clamp(((px - ax) * dx + (pz - az) * dz) / len2, 0.0, 1.0) else 0.0
    return hypot(px - (ax + dx * t), pz - (az + dz * t))
}

/** Does the way from -> to cross one of the circles (or does it end in one)? */
private fun blockedByAvoid(
    avoid: List<AvoidCircle>?,
    from: Located,
    to: Located,
): Boolean {
    if (avoid == null) return false
    for (i in avoid.indices) {
        val c = avoid[i]
        if (distanceToSegment(c.x, c.z, from.x, from.z, to.x, to.z) < c.r) return true
    }
    return false
}

private fun between(
    rng: () -> Double,
    range: ClosedFloatingPointRange<Double>,
): Double = range.start + (range.endInclusive - range.start) * rng()

private fun wrapAngle(a: Double): Double = a - PI * 2 * floor(a / (PI * 2) + 0.5)

private fun headingTo(
    dx: Double,
    dz: Double,
): Double = atan2(dx, dz)

/**
 * A point in the area for the next grazing spot: a step away from [from] in a random direction
 * when it fits, as far from the other horses as possible (several candidates). Spots whose way
 * crosses a keep-out circle of the area are skipped; null when no candidate is free. [others]:
 * positions the horse should keep away from ([from] itself is skipped, so a list of all horses
 * can be passed).
 */
fun pickSpot(
    rng: () -> Double,
    area: PaddockArea,
    from: Located,
    others: List<Located> = emptyList(),
    tuning: GrazingTuning = GRAZING,
): XZ? {
    var best: XZ? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (i in 0 until tuning.candidates) {
        val dist = between(rng, tuning.stepDistance)
        val a = rng() * PI * 2
        val rawX = from.x + sin(a) * dist
        val rawZ = from.z + cos(a) * dist
        // clamp into the area (keeps the margin to the fence)
        val p = toLocal(area, rawX, rawZ, scratch)
        p.x = clamp(p.x, -area.width / 2 + tuning.margin, area.width / 2 - tuning.margin)
        p.z = clamp(p.z, -area.depth / 2 + tuning.margin, area.depth / 2 - tuning.margin)
        val spot = toWorld(area, p.x, p.z)
        if (blockedByAvoid(area.avoid, from, spot)) continue
        val moved = hypot(spot.x - from.x, spot.z - from.z)
        var gap = Double.POSITIVE_INFINITY
        for (o in others) {
            if (o !== from) gap = min(gap, hypot(spot.x - o.x, spot.z - o.z))
        }
        // far from the others (up to the separation), and a real step
        val score = min(gap, tuning.separation) + (if (moved > 1.2) 1.0 else 0.0) + rng() * 0.5
        if (score > bestScore) {
            bestScore = score
            best = spot
        }
    }
    return best
}

/** A grazer with its body centre at (x, z) heading [heading]. */
class Grazer(
    override var x: Double,
    override var z: Double,
    var heading: Double,
    rng: () -> Double,
) : Located {
    var state = GrazerState.GRAZE

    /** Not all horses start at the same moment: the first graze lasts a random part of its time. */
    var timer = between(rng, GRAZING.grazeTime) * rng()
    var speed = 0.0
    var turnRate = 0.0

    /** 1 = head on the grass. */
    val headDown: Spring = createSpring(1.0)
    var graze = 1.0
    var target: XZ? = null
    var steps = 0

    /** The speed the walk towards the target wants (set by the walk step). */
    internal var wantSpeed = 0.0
}

/** A grazer with its body centre at (x, z) heading h. rng decides how long the first graze lasts. */
fun createGrazer(
    x: Double,
    z: Double,
    heading: Double = 0.0,
    rng: () -> Double,
): Grazer = Grazer(x, z, heading, rng)

/**
 * Advances a grazer. [others]: the horses (for the choice of the next spot; the grazer itself in
 * the list is skipped). After the step `g.speed` (m/s), `g.turnRate` (rad/s, + = right) and
 * `g.graze` (0..1) are what the horse's update needs.
 */
fun stepGrazer(
    g: Grazer,
    dt: Double,
    area: PaddockArea,
    others: List<Located>,
    rng: () -> Double,
    tuning: GrazingTuning = GRAZING,
): Grazer {
    var wantHeadDown = 0.0
    var wantSpeed = 0.0
    var turn = 0.0
    when (g.state) {
        GrazerState.GRAZE -> {
            wantHeadDown = 1.0
            g.timer -= dt
            if (g.timer <= 0) startLook(g, rng, tuning)
        }

        GrazerState.LOOK -> {
            endLook(g, dt, area, others, rng, tuning)
        }

        GrazerState.TURN -> {
            turn = turnTowardsTarget(g, tuning)
        }

        GrazerState.WALK -> {
            turn = walkTowardsTarget(g, rng, tuning)
            wantSpeed = g.wantSpeed
        }
    }

    // speed and turn rate change gradually
    val accel = tuning.walkAccel * dt
    g.speed += clamp(wantSpeed - g.speed, -accel * 1.5, accel)
    if (g.speed < 1e-3 && wantSpeed == 0.0) g.speed = 0.0
    g.turnRate += (turn - g.turnRate) * (1 - exp(-6 * dt))
    if (abs(g.turnRate) < 1e-3 && turn == 0.0) g.turnRate = 0.0
    g.heading = wrapAngle(g.heading - g.turnRate * dt)
    g.x += sin(g.heading) * g.speed * dt
    g.z += cos(g.heading) * g.speed * dt

    smoothTo(g.headDown, wantHeadDown, tuning.headOmega, dt)
    g.graze = clamp(g.headDown.x, 0.0, 1.0)
    return g
}

private fun endLook(
    g: Grazer,
    dt: Double,
    area: PaddockArea,
    others: List<Located>,
    rng: () -> Double,
    tuning: GrazingTuning,
) {
    g.timer -= dt
    if (g.timer > 0) return
    if (rng() < tuning.lookThenMove) {
        g.target = pickSpot(rng, area, g, others, tuning)
        if (g.target != null) g.state = GrazerState.TURN else startGraze(g, rng, tuning) // every way is blocked: stay
    } else {
        startGraze(g, rng, tuning)
    }
}

/** Turn on the spot (with the head up) until the spot is ahead; returns the turn rate. */
private fun turnTowardsTarget(
    g: Grazer,
    tuning: GrazingTuning,
): Double {
    val target = g.target ?: return 0.0
    val err = wrapAngle(headingTo(target.x - g.x, target.z - g.z) - g.heading)
    if (abs(err) < 0.1) {
        g.state = GrazerState.WALK
        return 0.0
    }
    return -sign(err) * tuning.turnRate * smoothstep(0.05, 0.5, abs(err) + 0.1)
}

/** Walk to the target; returns the turn rate and sets `g.wantSpeed`. */
private fun walkTowardsTarget(
    g: Grazer,
    rng: () -> Double,
    tuning: GrazingTuning,
): Double {
    g.wantSpeed = 0.0
    val target = g.target ?: return 0.0
    val dx = target.x - g.x
    val dz = target.z - g.z
    val dist = hypot(dx, dz)
    if (dist < tuning.arrive) {
        g.target = null
        startGraze(g, rng, tuning)
        return 0.0
    }
    val err = wrapAngle(headingTo(dx, dz) - g.heading)
    // slow down for the last metre
    g.wantSpeed = max(tuning.creepSpeed, tuning.walkSpeed * smoothstep(0.2, 1.4, dist))
    return clamp(-err * tuning.steerGain, -tuning.maxSteer, tuning.maxSteer)
}

private fun startLook(
    g: Grazer,
    rng: () -> Double,
    tuning: GrazingTuning,
) {
    g.state = GrazerState.LOOK
    g.timer = between(rng, tuning.lookTime)
}

private fun startGraze(
    g: Grazer,
    rng: () -> Double,
    tuning: GrazingTuning,
) {
    g.state = GrazerState.GRAZE
    g.timer = between(rng, tuning.grazeTime)
}
