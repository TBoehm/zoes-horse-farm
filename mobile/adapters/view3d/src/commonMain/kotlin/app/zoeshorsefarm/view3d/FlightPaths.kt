package app.zoeshorsefarm.view3d

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Flight paths of the birds and butterflies (pure, no scene model): closed-form functions of time,
// so a frame costs no state and the paths can be tested. Heading h points along (sin h, cos h),
// like the rotations of the obstacles.

private const val TWO_PI = 2 * PI

/** Technical values of the paths (look, no game play). */
class BirdLimits(
    /** Orbit radius of a flock (m). */
    val radius: ClosedFloatingPointRange<Double> = 55.0..95.0,
    /** Altitude above the meadow (m). */
    val altitude: ClosedFloatingPointRange<Double> = 14.0..30.0,
    /** m/s: circling slowly. */
    val speed: ClosedFloatingPointRange<Double> = 4.5..7.0,
    /** Slow rise and fall of a flock (m). */
    val bob: ClosedFloatingPointRange<Double> = 1.2..2.8,
    /** No bird is farther than this from the center of its flock (m, horizontal). */
    val spread: Double = 8.0,
)

val BIRD_LIMITS = BirdLimits()

private class ButterflyLimits {
    // wander radius as a share of the patch radius
    val reachShare = 0.4..0.6
    val maxWander = 2.2 // m
    val height = 0.45..0.95
    val bob = 0.18

    // rad/s of the wander
    val frequency = 0.25..0.45
}

private val BUTTERFLY = ButterflyLimits()

private fun between(
    rng: () -> Double,
    range: ClosedFloatingPointRange<Double>,
): Double = range.start + rng() * (range.endInclusive - range.start)

/** A pose of something that flies; the functions below write into one to avoid allocating per frame. */
class FlightPose {
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0
    var heading: Double = 0.0
    var roll: Double = 0.0
}

/** The fixed place of a bird in its flock: [f] forward, [r] to the right, [u] up (m) and a [phase] for its wobble. */
data class Bird(
    val f: Double,
    val r: Double,
    val u: Double,
    val phase: Double,
)

/**
 * A flock circles around ([cx], [cz]) at [radius], slowly and at its own height; [dir] is the sense
 * of rotation (+-1).
 */
data class Flock(
    val cx: Double,
    val cz: Double,
    val radius: Double,
    val altitude: Double,
    val bob: Double,
    val speed: Double,
    val dir: Int,
    val angle0: Double,
    val phase: Double,
    val birds: List<Bird>,
)

private fun planBird(rng: () -> Double): Bird {
    val f = (rng() - 0.5) * 7
    val r = (rng() - 0.5) * 6
    val u = (rng() - 0.5) * 2
    return Bird(f, r, u, rng() * TWO_PI)
}

/**
 * Plans [count] flocks of [size] birds ([rng] gives numbers in [0, 1), the draws are in the order
 * of the web app).
 */
fun planFlocks(
    rng: () -> Double,
    count: Int,
    size: Int,
): List<Flock> =
    List(count) { i ->
        val birds = List(size) { planBird(rng) }
        val cx = (rng() - 0.5) * 50
        val cz = (rng() - 0.5) * 50
        val radius = between(rng, BIRD_LIMITS.radius)
        val altitude = between(rng, BIRD_LIMITS.altitude)
        val bob = between(rng, BIRD_LIMITS.bob)
        val speed = between(rng, BIRD_LIMITS.speed)
        val angle0 = rng() * TWO_PI
        Flock(cx, cz, radius, altitude, bob, speed, if (i % 2 == 0) 1 else -1, angle0, rng() * TWO_PI, birds)
    }

/** Center of a flock at time [t]: writes x, y, z and heading into [out] and returns it. */
fun flockPose(
    flock: Flock,
    t: Double,
    out: FlightPose,
): FlightPose {
    val angle = flock.angle0 + flock.dir * flock.speed * t / flock.radius
    out.x = flock.cx + cos(angle) * flock.radius
    out.z = flock.cz + sin(angle) * flock.radius
    out.y = flock.altitude + sin(t * 0.21 + flock.phase) * flock.bob
    // tangent of the circle
    out.heading = atan2(-sin(angle) * flock.dir, cos(angle) * flock.dir)
    return out
}

/**
 * Pose of one bird of a flock: its place in the flock, drifting a little, turned along the path
 * and banking into the turn. Writes x, y, z, heading and roll into [out] and returns it.
 */
fun birdPose(
    flock: Flock,
    index: Int,
    t: Double,
    out: FlightPose,
): FlightPose {
    val bird = flock.birds[index]
    flockPose(flock, t, out)
    val forward = bird.f + sin(t * 0.4 + bird.phase) * 0.6
    val right = bird.r + sin(t * 0.33 + bird.phase * 1.7) * 0.5
    val sinH = sin(out.heading)
    val cosH = cos(out.heading)
    out.x += sinH * forward + cosH * right
    out.z += cosH * forward - sinH * right
    out.y += bird.u + sin(t * 0.9 + bird.phase) * 0.35
    out.heading += sin(t * 0.5 + bird.phase) * 0.08
    out.roll = -flock.dir * 0.3 + sin(t * 0.7 + bird.phase) * 0.1
    return out
}

/** A flower patch a butterfly may hover over: center and radius. */
data class PatchAnchor(
    val x: Double,
    val z: Double,
    val radius: Double,
)

/**
 * A butterfly wanders on a slightly irregular ellipse (radii [rx], [rz]) around ([ax], [az]);
 * [reach] is the farthest it gets.
 */
data class Butterfly(
    val ax: Double,
    val az: Double,
    val rx: Double,
    val rz: Double,
    val reach: Double,
    val height: Double,
    val freq: Double,
    val p1: Double,
    val p2: Double,
    val p3: Double,
)

/** Plans [count] butterflies that hover over the given patches (used in turn). */
fun planButterflies(
    rng: () -> Double,
    anchors: List<PatchAnchor>,
    count: Int,
): List<Butterfly> {
    if (anchors.isEmpty()) return emptyList()
    return List(count) { i ->
        val anchor = anchors[i % anchors.size]
        val wander = { min(BUTTERFLY.maxWander, anchor.radius * between(rng, BUTTERFLY.reachShare)) }
        val rx = wander()
        val rz = wander()
        val ax = anchor.x + (rng() - 0.5) * anchor.radius * 0.6
        val az = anchor.z + (rng() - 0.5) * anchor.radius * 0.6
        val height = between(rng, BUTTERFLY.height)
        val freq = between(rng, BUTTERFLY.frequency)
        val p1 = rng() * TWO_PI
        val p2 = rng() * TWO_PI
        Butterfly(ax, az, rx, rz, 1.25 * hypot(rx, rz), height, freq, p1, p2, rng() * TWO_PI)
    }
}

/**
 * Pose of a butterfly at time [t]: writes x, y, z and heading into [out] and returns it. The
 * heading follows the base ellipse, which never stands still, so it turns smoothly even where the
 * small irregularities of the position slow the butterfly down.
 */
fun butterflyPose(
    b: Butterfly,
    t: Double,
    out: FlightPose,
): FlightPose {
    val a = b.freq * t + b.p1
    out.x = b.ax + b.rx * (sin(a) + 0.25 * sin(2.3 * a + b.p2))
    out.z = b.az + b.rz * (cos(a) + 0.25 * sin(1.7 * a + b.p3))
    out.y = b.height + BUTTERFLY.bob * sin(b.freq * 3.1 * t + b.p3)
    out.heading = atan2(b.rx * cos(a), -b.rz * sin(a))
    return out
}
