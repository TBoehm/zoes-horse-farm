package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

// Jump rules per element: takeoff zone, target ranges, jumpability, knockdown risk
// (concept rules 15-20). Pure stateless functions.

private const val DEG = PI / 180

/** Target speed range of the safe core (m/s). */
data class SpeedBand(
    val min: Double,
    val max: Double,
)

/**
 * Takeoff zone and reach in m before the leading edge: reach > far > near > lastPoint > 0.
 * `center` is the middle of the zone. Mutable so the per-frame code can reuse one instance with
 * [zoneInto].
 */
data class Zone(
    var far: Double = 0.0,
    var near: Double = 0.0,
    var lastPoint: Double = 0.0,
    var reach: Double = 0.0,
    var center: Double = 0.0,
) {
    /** Copies all fields of [other] into this one. */
    fun set(other: Zone) {
        far = other.far
        near = other.near
        lastPoint = other.lastPoint
        reach = other.reach
        center = other.center
    }
}

/** State at the moment of a takeoff, input of [takeoffRisk]. */
data class TakeoffState(
    val gait: Gait,
    val speed: Double,
    val distance: Double,
    val angle: Double,
    val self: Boolean = false,
)

/** A pole of an element with its local position along n. */
data class RailPosition(
    val rail: Int,
    val along: Double,
)

/** Half extent of the blocked area of an element (local coordinates). */
data class BlockExtents(
    val along: Double,
    val across: Double,
)

/** Difficulty 0..1 from height and spread. */
fun difficultyOf(
    element: Element,
    tuning: Tuning,
): Double {
    val d = tuning.jump.difficulty
    val raw = (element.height - d.heightRef + d.spreadWeight * element.spread) / d.range
    return clamp(raw, 0.0, 1.0)
}

/** Jumpability by gait (rule 16). */
fun gaitAllows(
    element: Element,
    gait: Gait,
): Boolean =
    when (gait) {
        Gait.CANTER -> true
        Gait.TROT -> element.kind == ElementKind.CROSS
        else -> false
    }

/** Target speed range of the safe core. */
fun speedBand(
    element: Element,
    tuning: Tuning,
): SpeedBand {
    val b = tuning.jump.speedBand
    val s = tuning.speeds
    if (element.kind == ElementKind.CROSS) return SpeedBand(b.crossMin, b.crossMax)
    val min = max(s.canterMin, b.base + b.perHeight * element.height + b.perSpread * element.spread)
    return SpeedBand(min, min(s.canterMax, min + b.width))
}

/** Minimum speed for a self jump at the last takeoff point (rule 20). */
fun selfMinSpeed(
    element: Element,
    tuning: Tuning,
): Double {
    val b = tuning.jump.speedBand
    if (element.kind == ElementKind.CROSS) return b.crossSelfMin
    return speedBand(element, tuning).min - b.selfMargin
}

/** Angle tolerance of the safe core (rad). */
fun safeAngle(
    element: Element,
    tuning: Tuning,
): Double {
    val a = tuning.jump.safeAngle
    return a.base - a.perDifficulty * difficultyOf(element, tuning)
}

/** Half time window of the takeoff zone (s). */
fun zoneWindow(
    element: Element,
    tuning: Tuning,
): Double {
    val w = tuning.jump.window
    if (element.kind == ElementKind.CROSS) return w.cross
    val v =
        w.base -
            w.perHeight * max(0.0, element.height - w.heightRef) -
            w.perSpread * element.spread
    return max(w.min, v)
}

/**
 * Allocation-free [zoneForElement]: fills [out] with the takeoff zone of [element] at [speed];
 * depends on kind, height, spread and speed.
 */
fun zoneInto(
    element: Element,
    speed: Double,
    tuning: Tuning,
    out: Zone,
) {
    val j = tuning.jump
    val z = j.zone
    val v = max(if (speed.isNaN()) 0.0 else speed, z.minSpeed)
    val center =
        max(
            z.minCenter,
            z.base +
                z.perHeight * element.height +
                z.perSpread * element.spread +
                z.perSpeed * (v - z.speedRef),
        )
    val half = zoneWindow(element, tuning) * v
    val far = center + half
    val near = max(z.minNear, center - half)
    out.far = far
    out.near = near
    out.lastPoint =
        min(
            near * j.lastPoint.maxShareOfNear,
            max(j.lastPoint.min, near - j.lastPoint.lead * v),
        )
    out.reach = far + max(j.reachMin, j.reachLead * v)
    out.center = center
}

/** Takeoff zone and reach for [element] at [speed]; depends on kind, height, spread and speed. */
fun zoneForElement(
    element: Element,
    speed: Double,
    tuning: Tuning,
): Zone = Zone().also { zoneInto(element, speed, tuning, it) }

/**
 * Knockdown risk of a takeoff (rules 15, 18, 19, 20).
 * In the safe core (gait ok, angle <= tolerance, speed in target range, distance in the zone)
 * it is exactly 0. Outside it rises monotonically with every deviation, scaled by difficulty.
 * A self jump (`self`) always carries an additional base risk.
 */
fun takeoffRisk(
    element: Element,
    takeoff: TakeoffState,
    tuning: Tuning,
): Double {
    val r = tuning.jump.risk
    if (!gaitAllows(element, takeoff.gait) || takeoff.angle > tuning.jump.maxAngle) return r.max
    val zone = zoneForElement(element, takeoff.speed, tuning)
    val band = speedBand(element, tuning)
    val difficulty = difficultyOf(element, tuning)
    val severity = r.severityBase + r.severityGain * difficulty
    val dv = max(0.0, max(band.min - takeoff.speed, takeoff.speed - band.max))
    val dd = max(0.0, max(zone.near - takeoff.distance, takeoff.distance - zone.far))
    val da = max(0.0, takeoff.angle - safeAngle(element, tuning)) / DEG
    var clean = 1.0
    clean *= 1 - min(r.factorCap, severity * (r.perSpeed * dv))
    clean *= 1 - min(r.factorCap, severity * (r.perDistance * dd))
    clean *= 1 - min(r.factorCap, severity * (r.perDegree * da))
    if (takeoff.self) clean *= 1 - min(r.factorCap, r.selfBase + r.selfPerDifficulty * difficulty)
    return min(r.max, 1 - clean)
}

/** Poles of an element with their local position along n (rail 0 at -spread/2). */
fun railLayout(element: Element): List<RailPosition> {
    if (element.kind == ElementKind.OXER) {
        val hs = element.spread / 2
        return listOf(RailPosition(0, -hs), RailPosition(1, hs))
    }
    return listOf(RailPosition(0, 0.0))
}

/**
 * Half extent of the blocked area (local) that the horse may not enter without jumping.
 * [alongMargin] is the distance kept before/behind the poles (front margin by default).
 */
fun blockExtents(
    element: Element,
    tuning: Tuning,
    alongMargin: Double = tuning.horse.frontMargin,
): BlockExtents =
    BlockExtents(
        along = element.spread / 2 + alongMargin,
        across = POLE_LENGTH / 2 + STAND_WIDTH + tuning.horse.halfWidth,
    )

/** Landing distance behind the rear pole. */
fun landingDistance(
    element: Element,
    takeoffDistance: Double,
    tuning: Tuning,
): Double {
    val f = tuning.jump.flight
    return clamp(
        f.landBase + f.landPerTakeoff * takeoffDistance + f.landPerHeight * element.height,
        f.landMin,
        f.landMax,
    )
}
