package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Gaits as pure functions: footfall sequence, cadence, duty factor and hoof paths.
// Leg index: 0 = LF (left fore), 1 = RF, 2 = LH (left hind), 3 = RH.
// Cadence (riding theory): walk ~ 55/min, trot ~ 80/min, canter ~ 100/min; speed rises mainly
// through stride length. The rein-back is a slow two-beat diagonal gait (the trot's footfall,
// backwards), here ~ 33-57/min at the backing speeds of TUNING.reinBack.
// No foot sliding: hoof travel during stance L = duty * speed / frequency (the hoof rests
// relative to the ground).

// Speed thresholds come from the simulation tuning, so animation and simulation cannot drift apart.
private val SPEEDS = TUNING.speeds

// Reference speeds (m/s) at which the cadences above are tuned (see `freq` of each gait)
private const val WALK_REF_SPEED = 1.6
private val TROT_REF_SPEED = SPEEDS.trotMedium
private const val CANTER_REF_SPEED = 6.0
private val BACK_REF_SPEED = TUNING.reinBack.maxSpeed

private const val TAU = PI * 2

/** The gaits in the order of the weights. */
val GAIT_KEYS: Array<Gait> = arrayOf(Gait.HALT, Gait.WALK, Gait.TROT, Gait.CANTER, Gait.BACK)

/** The gaits with leg movement (not halt). */
val MOVING_GAITS: Array<Gait> = arrayOf(Gait.WALK, Gait.TROT, Gait.CANTER, Gait.BACK)

private fun powSafe(
    x: Double,
    e: Double,
): Double = max(x, 1e-4).pow(e)

/** A value that depends on the speed (a primitive function type: calling it does not box). */
fun interface SpeedCurve {
    operator fun invoke(v: Double): Double
}

/**
 * Parameters of one gait. `duty` and `freq` are functions of the speed; `lift`, `flex`, `past` and
 * `center` have one value per leg (LF, RF, LH, RH). `reverse`: the hoof path runs forward relative
 * to the body during stance (the body moves backwards).
 */
class GaitSpec(
    val offsets: DoubleArray,
    val duty: SpeedCurve,
    val freq: SpeedCurve,
    val lift: DoubleArray,
    val flex: DoubleArray,
    val past: DoubleArray,
    val center: DoubleArray,
    val sink: Double,
    val offsetsRight: DoubleArray? = null,
    val reverse: Boolean = false,
)

/** The gait table; `GAITS[gait]` for the moving gaits. */
class GaitTable {
    val walk =
        GaitSpec(
            // four-beat: LH -> LF -> RH -> RF
            offsets = doubleArrayOf(0.25, 0.75, 0.0, 0.5),
            duty = { v -> lerp(0.64, 0.58, clamp(v / SPEEDS.walkMax, 0.0, 1.0)) },
            freq = { v -> 0.92 * powSafe(max(v, 0.25) / WALK_REF_SPEED, 0.35) },
            lift = doubleArrayOf(0.1, 0.1, 0.09, 0.09),
            flex = doubleArrayOf(1.05, 1.05, 0.45, 0.45),
            past = doubleArrayOf(0.9, 0.9, 0.7, 0.7),
            center = doubleArrayOf(0.03, 0.03, 0.0, 0.0),
            sink = 0.015,
        )
    val trot =
        GaitSpec(
            // two-beat diagonal: LF + RH, then RF + LH
            offsets = doubleArrayOf(0.0, 0.5, 0.5, 0.0),
            duty = { v -> lerp(0.44, 0.36, clamp((v - SPEEDS.trotMin) / (SPEEDS.trotMax - SPEEDS.trotMin), 0.0, 1.0)) },
            freq = { v -> 1.33 * powSafe(max(v, 1.2) / TROT_REF_SPEED, 0.25) },
            lift = doubleArrayOf(0.2, 0.2, 0.15, 0.15),
            flex = doubleArrayOf(1.75, 1.75, 0.8, 0.8),
            past = doubleArrayOf(1.4, 1.4, 1.1, 1.1),
            center = doubleArrayOf(0.07, 0.07, 0.02, 0.02),
            sink = 0.045,
        )

    /**
     * Rein-back: two-beat diagonal (LF + RH, then RF + LH), no suspension phase. The hoof path runs
     * forward relative to the body during stance (the body moves backwards).
     */
    val back =
        GaitSpec(
            reverse = true,
            offsets = doubleArrayOf(0.0, 0.5, 0.5, 0.0),
            duty = { 0.55 },
            freq = { v -> 0.55 + 0.4 * clamp(v / BACK_REF_SPEED, 0.0, 1.0) },
            lift = doubleArrayOf(0.13, 0.13, 0.1, 0.1),
            flex = doubleArrayOf(1.4, 1.4, 0.65, 0.65),
            past = doubleArrayOf(1.1, 1.1, 0.8, 0.8),
            center = doubleArrayOf(0.03, 0.03, 0.0, 0.0),
            sink = 0.02,
        )

    /** Left lead: RH -> (LH + RF) -> LF -> suspension. Right lead mirrored. */
    val canter =
        GaitSpec(
            offsets = doubleArrayOf(0.47, 0.26, 0.22, 0.0),
            offsetsRight = doubleArrayOf(0.26, 0.47, 0.0, 0.22),
            duty = { v ->
                lerp(0.38, 0.3, clamp((v - SPEEDS.canterMin) / (SPEEDS.canterMax - SPEEDS.canterMin), 0.0, 1.0))
            },
            freq = { v -> 1.67 * powSafe(max(v, 3.0) / CANTER_REF_SPEED, 0.2) },
            lift = doubleArrayOf(0.27, 0.27, 0.2, 0.2),
            flex = doubleArrayOf(1.95, 1.95, 1.0, 1.0),
            past = doubleArrayOf(1.5, 1.5, 1.2, 1.2),
            center = doubleArrayOf(0.1, 0.1, 0.05, 0.05),
            sink = 0.055,
        )

    operator fun get(gait: Gait): GaitSpec =
        when (gait) {
            Gait.WALK -> walk
            Gait.TROT -> trot
            Gait.CANTER -> canter
            Gait.BACK -> back
            Gait.HALT -> error("halt has no leg movement")
        }
}

val GAITS = GaitTable()

// Shapes of the swing phase (u = 0 lift-off ... 1 touch-down, result 0..1): the hoof lifts and the
// joints fold early in the swing and the hoof is placed gently. `ease` keeps the slope at
// lift-off finite (a plain power curve starts vertically, which shows as a snap at 60 fps).
private fun ease(
    u: Double,
    a: Double,
): Double = (u * (1 + a)) / (u + a)

/** Hoof height over the swing. */
fun swingLift(u: Double): Double = sin(PI * ease(u, 2.0))

// Smooth hump over u in [0, 1] with its peak at u = p and zero slope at 0, p and 1
private fun hump(
    u: Double,
    p: Double,
): Double = if (u < p) smoothstep(0.0, 1.0, u / p) else 1 - smoothstep(0.0, 1.0, min(1.0, (u - p) / (1 - p)))

/** Carpus / hock flexion over the swing. */
fun swingFlex(u: Double): Double = hump(u, 0.38)

/** Pastern fold over the swing. */
fun swingPast(u: Double): Double = hump(u, 0.36)

/** Fore/aft travel over the swing (0 ... 1, slow start and end). */
fun swingTravel(u: Double): Double = u - sin(TAU * u) / TAU

/**
 * Correction of the fore/aft travel of the swing that gives it the slope 1 (per unit of u) at both
 * ends and next to nothing in between: add slope * swingEnds(u) to a path that starts and ends
 * with zero slope. Zero at u = 0 and u = 1.
 */
fun swingEnds(u: Double): Double = u * (1 - u).pow(6) - (1 - u) * u.pow(6)

/** Lift scales a bit with speed within the gait (slow walk: flatter). */
fun liftScaleFor(
    gait: Gait,
    v: Double,
): Double =
    when (gait) {
        Gait.WALK -> lerp(0.45, 1.0, clamp(v / 1.4, 0.0, 1.0))
        Gait.BACK -> lerp(0.5, 1.0, clamp(v / BACK_REF_SPEED, 0.0, 1.0))
        else -> 1.0
    }

/** Maximum hoof travel per stance phase (beyond that the leg would overextend). */
const val MAX_STANCE_TRAVEL = 1.15

fun legPhase(
    phi: Double,
    offset: Double,
): Double = (((phi - offset) % 1) + 1) % 1

fun offsetsFor(
    gait: Gait,
    lead: Double,
): DoubleArray {
    val g = GAITS[gait]
    val right = g.offsetsRight
    return if (gait == Gait.CANTER && lead < 0 && right != null) right else g.offsets
}

/** Gait shares (they add up to 1): the cross-fade of the gaits. */
class GaitWeights(
    halt: Double = 0.0,
    walk: Double = 0.0,
    trot: Double = 0.0,
    canter: Double = 0.0,
    back: Double = 0.0,
) {
    private val values = doubleArrayOf(halt, walk, trot, canter, back)

    var halt: Double
        get() = values[0]
        set(v) {
            values[0] = v
        }
    var walk: Double
        get() = values[1]
        set(v) {
            values[1] = v
        }
    var trot: Double
        get() = values[2]
        set(v) {
            values[2] = v
        }
    var canter: Double
        get() = values[3]
        set(v) {
            values[3] = v
        }
    var back: Double
        get() = values[4]
        set(v) {
            values[4] = v
        }

    operator fun get(gait: Gait): Double = values[gait.ordinal]

    operator fun set(
        gait: Gait,
        v: Double,
    ) {
        values[gait.ordinal] = v
    }

    /** Sets all shares to zero. */
    fun clear() = values.fill(0.0)

    companion object {
        /** A weight set with the whole share on [gait]. */
        fun of(gait: Gait): GaitWeights = GaitWeights().also { it[gait] = 1.0 }
    }
}

/** Blended stride frequency (Hz) for weights {walk, trot, canter, back} at speed v (>= 0). */
fun blendedFrequency(
    weights: GaitWeights,
    v: Double,
): Double {
    var f = 0.0
    for (k in MOVING_GAITS) {
        if (weights[k] > 0) f += weights[k] * GAITS[k].freq(v)
    }
    return f
}

/** Result of [legSample]: dz fore/aft relative to neutral (model space), lift, flexion, pastern fold, fetlock drop. */
class LegSample {
    var dz = 0.0
    var y = 0.0
    var flex = 0.0
    var past = 0.0
    var sink = 0.0
    var stance = true
}

/**
 * Hoof path of one leg in one gait. f = (blended) stride frequency, v = speed.
 * Returns dz (fore/aft relative to neutral, model space), y (lift), flex (carpus/hock flexion),
 * past (pastern fold), sink (fetlock drop), stance (true during the stance phase).
 */
fun legSample(
    gait: Gait,
    leg: Int,
    phi: Double,
    v: Double,
    f: Double,
    lead: Double = 1.0,
    out: LegSample = LegSample(),
): LegSample {
    val g = GAITS[gait]
    val d = g.duty(v)
    val p = legPhase(phi, offsetsFor(gait, lead)[leg])
    val travel = if (f > 1e-4) min(MAX_STANCE_TRAVEL, (d * v) / f) else 0.0
    val c = g.center[leg]
    val liftScale = liftScaleFor(gait, v)
    if (p < d) {
        val u = p / d
        out.dz = c + travel / 2 - travel * u
        out.y = 0.0
        out.flex = 0.0
        out.past = 0.0
        out.sink = g.sink * sin(PI * u)
        out.stance = true
    } else {
        val u = (p - d) / (1 - d)
        out.dz = c - travel / 2 + travel * swingTravel(u)
        out.y = g.lift[leg] * liftScale * swingLift(u)
        out.flex = g.flex[leg] * liftScale * swingFlex(u)
        out.past = g.past[leg] * liftScale * swingPast(u)
        out.sink = 0.0
        out.stance = false
    }
    // rein-back: the same cycle mirrored around the neutral position (forward in stance)
    if (g.reverse) out.dz = 2 * c - out.dz
    return out
}

/** Result of [bodySample]: bob, pitch, head nod and roll of the body. */
class BodySample {
    var bob = 0.0
    var pitch = 0.0
    var neck = 0.0
    var roll = 0.0
}

/** Body motion per gait: bob, pitch, head nod, roll. */
fun bodySample(
    gait: Gait,
    phi: Double,
    v: Double,
    lead: Double = 1.0,
    out: BodySample = BodySample(),
): BodySample {
    out.bob = 0.0
    out.pitch = 0.0
    out.neck = 0.0
    out.roll = 0.0
    when (gait) {
        Gait.WALK -> {
            val a = clamp(v / 1.6, 0.0, 1.0)
            out.bob = -0.012 * a * (0.5 + 0.5 * cos(2 * TAU * (phi - 0.12)))
            // head nods twice per cycle, low when a foreleg lands
            out.neck = 0.06 * a * cos(2 * TAU * (phi - 0.3))
            out.roll = 0.018 * a * sin(TAU * (phi - 0.1))
        }

        Gait.TROT -> {
            val d = GAITS.trot.duty(v)
            out.bob = -0.045 * (0.5 + 0.5 * cos(2 * TAU * (phi - d / 2)))
            out.neck = 0.015 * cos(2 * TAU * (phi - d / 2))
            out.roll = 0.006 * sin(TAU * phi)
        }

        Gait.BACK -> {
            // calm: small bob once per beat, the head follows the diagonal steps a little
            val a = clamp(v / BACK_REF_SPEED, 0.0, 1.0)
            out.bob = -0.012 * a * (0.5 + 0.5 * cos(2 * TAU * (phi - 0.1)))
            out.neck = 0.03 * a * cos(2 * TAU * (phi - 0.1))
            out.roll = 0.008 * a * sin(TAU * phi)
        }

        Gait.CANTER -> {
            out.bob = -0.065 * (0.5 + 0.5 * cos(TAU * (phi - 0.36)))
            // rocking: nose up when the hindquarters land, nose down on the leading foreleg
            out.pitch = 0.07 * sin(TAU * (phi - 0.33))
            out.neck = 0.11 * sin(TAU * (phi - 0.28))
            out.roll = 0.012 * lead * sin(TAU * (phi - 0.2))
        }

        Gait.HALT -> {
            // no body motion at halt
        }
    }
    return out
}

/** Smooth approach of a value towards a target (exponential, frame-rate independent). */
fun approach(
    current: Double,
    target: Double,
    rate: Double,
    dt: Double,
): Double = target + (current - target) * exp(-rate * dt)

/** Weight with soft fade-in/out over progress 0..1. */
fun bump(
    p: Double,
    inEnd: Double,
    outStart: Double,
): Double = smoothstep(0.0, inEnd, p) * (1 - smoothstep(outStart, 1.0, p))
