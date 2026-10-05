package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// Life signs of the horse (pure): spring-follow dynamics of tail, mane and forelock that are driven
// by the horse's real acceleration (starts and stops, turns, bobbing, landings), blinking,
// breathing with flaring nostrils, tail swishes and the shapes of the idle head gestures.
// HorseView.kt turns the result into bone rotations and shader uniforms.

// Hair dynamics. Gains are radians of deflection per m/s^2 of body acceleration; the hair is
// under-damped (zeta < 1) so that it swings over and settles again. Later segments are softer.
private val TAIL =
    HairConfig(
        count = 5,
        omega0 = 15.0,
        omegaTail = 8.5,
        zeta = 0.32,
        gainFwd = doubleArrayOf(0.012, 0.022, 0.027, 0.028, 0.028),
        gainUp = doubleArrayOf(0.006, 0.01, 0.01, 0.008, 0.006),
        gainLat = doubleArrayOf(0.01, 0.03, 0.038, 0.04, 0.04),
        windGain = doubleArrayOf(0.4, 0.7, 1.0, 1.2, 1.3),
        couple = 0.25,
    )
private val MANE =
    HairConfig(
        count = 5,
        omega0 = 14.0,
        omegaTail = 8.0,
        zeta = 0.34,
        gainFwd = doubleArrayOf(0.035, 0.05, 0.055, 0.055, 0.05),
        gainUp = doubleArrayOf(0.01, 0.014, 0.016, 0.016, 0.014),
        gainLat = doubleArrayOf(0.025, 0.035, 0.04, 0.04, 0.035),
        windGain = doubleArrayOf(0.5, 0.8, 1.0, 1.1, 1.1),
        couple = 0.3,
    )
private val FORELOCK =
    HairConfig(
        count = 1,
        omega0 = 13.0,
        omegaTail = 13.0,
        zeta = 0.3,
        gainFwd = doubleArrayOf(0.05),
        gainUp = doubleArrayOf(0.02),
        gainLat = doubleArrayOf(0.04),
        windGain = doubleArrayOf(1.0),
        couple = 0.0,
    )

// Lever of the neck for the acceleration that the mane feels from the nodding of the head (m)
private const val NECK_LEVER = 0.9

// A tail swish: angular velocity added to the sway of the tail segments (rad/s)
private val SWISH_INTERVAL = 4.0..10.0
private const val SWISH_VELOCITY = 3.2
private const val SWISH_MAX_SPEED = 1.2

// Breathing rate (Hz): standing calmly ... cantering (about one breath per stride)
private const val BREATH_IDLE = 0.26
private const val BREATH_WALK = 0.5
private const val BREATH_TROT = 0.9
private const val BREATH_CANTER = 1.55

/** What [stepLife] needs to know about the horse in this frame. */
class LifeInput(
    /** m/s, signed. */
    var speed: Double = 0.0,
    /** rad/s, + = right. */
    var turnRate: Double = 0.0,
    /** Height of the body, m. */
    var bodyY: Double = 0.0,
    /** rad, + = down. */
    var neckAngle: Double = 0.0,
    var weights: GaitWeights = GaitWeights(halt = 1.0),
    /** 0..1: approaching a jump. */
    var alert: Double = 0.0,
    /** 0..1; null: derived from the gait weights. */
    var exertion: Double? = null,
)

/** The life signs of one horse. [rng]: random numbers for blinking and tail swishes. */
class Life(
    rng: () -> Double,
) {
    val body = createAccelEstimator()
    val neck = createAccelEstimator()
    val tail = createHairChain(TAIL)
    val mane = createHairChain(MANE)
    val forelock = createHairChain(FORELOCK)
    val drive = HairDrive()
    val maneDrive = HairDrive()
    val wind = HairWind()
    var time = 0.0
    var breathPhase = 0.0

    /** Sin of the breathing cycle. */
    var breath = 0.0

    /** Nostril flare 0..1. */
    var flare = 0.0

    /** Eyelids 0 (open) ... 1 (closed). */
    var blinkClosure = 0.0
    val blinker = createBlinkScheduler(rng)
    val swish = createRandomTimer(rng, SWISH_INTERVAL)
    var swishDir = 1.0
    internal val bodyInput = AccelInput()
    internal val neckInput = AccelInput()
}

fun createLife(rng: () -> Double): Life = Life(rng)

/** Advances the life signs by [dt]. */
fun stepLife(
    life: Life,
    dt: Double,
    input: LifeInput,
): Life {
    if (!(dt > 0)) return life
    life.time += dt
    val t = life.time
    val w = input.weights

    // acceleration of the body and of the neck in the body frame
    life.bodyInput.speed = input.speed
    life.bodyInput.turnRate = input.turnRate
    life.bodyInput.y = input.bodyY
    stepAccelEstimator(life.body, life.bodyInput, dt)
    life.neckInput.speed = 0.0
    life.neckInput.turnRate = 0.0
    life.neckInput.y = input.neckAngle * NECK_LEVER
    stepAccelEstimator(life.neck, life.neckInput, dt)
    val d = life.drive
    d.fwd = life.body.fwd
    d.up = life.body.up
    d.lat = life.body.lat
    val md = life.maneDrive
    md.fwd = d.fwd
    md.up = d.up + life.neck.up
    md.lat = d.lat

    // a light breeze that never lets the hair rest completely
    life.wind.pitch = 0.025 * sin(t * 1.3) + 0.015 * sin(t * 2.9 + 1.7)
    life.wind.sway = 0.05 * sin(t * 0.9 + 0.4) + 0.025 * sin(t * 2.3)
    stepHairChain(life.tail, d, dt, life.wind)
    stepHairChain(life.mane, md, dt, life.wind)
    stepHairChain(life.forelock, md, dt, life.wind)

    // tail swish: only while the horse is calm
    val calm = w.halt + w.walk
    if (life.swish.step(dt) && calm > 0.9 && abs(input.speed) < SWISH_MAX_SPEED) {
        life.swishDir = -life.swishDir
        kickChain(life.tail, 0.0, life.swishDir * SWISH_VELOCITY)
    }

    // breathing: faster with the gait; in the canter it is tied to the stride
    val rate =
        w.halt * BREATH_IDLE + w.back * BREATH_WALK + w.walk * BREATH_WALK + w.trot * BREATH_TROT +
            w.canter * BREATH_CANTER
    life.breathPhase = (life.breathPhase + rate * dt) % 1
    life.breath = sin(2 * PI * life.breathPhase)
    val effort = clamp(input.exertion ?: (w.walk * 0.25 + w.trot * 0.6 + w.canter), 0.0, 1.0)
    life.flare = clamp(0.15 + 0.3 * (0.5 + 0.5 * life.breath) + 0.55 * effort, 0.0, 1.0)

    life.blinkClosure = life.blinker.step(dt, input.alert)
    return life
}

/** Head and neck offsets of an idle gesture (radians, added to the base pose); all zero at weight 0. */
class HeadGesture {
    var yaw = 0.0
    var pitch = 0.0
    var neck = 0.0
}

/** Head and neck offsets of the idle gesture [g] (from the gesture scheduler), see [HeadGesture]. */
fun gestureHead(
    g: GestureState?,
    out: HeadGesture = HeadGesture(),
): HeadGesture {
    out.yaw = 0.0
    out.pitch = 0.0
    out.neck = 0.0
    val id = g?.id
    if (g == null || id == null || g.weight <= 0) return out
    val k = g.weight
    when (id) {
        GestureId.SHAKE -> {
            // a quick shake from side to side that dies away
            val decay = 1 - smoothstep(0.2, g.duration, g.t)
            out.yaw = k * 0.32 * decay * sin(2 * PI * 3.4 * g.t)
            out.pitch = k * 0.08 * decay * sin(2 * PI * 6.8 * g.t)
            out.neck = k * 0.03
        }

        GestureId.TOSS -> {
            // the head flips up and comes down again
            val up = smoothstep(0.0, 0.28, g.t) * (1 - smoothstep(0.28, 0.9, g.t))
            out.pitch = -k * 0.5 * up
            out.neck = -k * 0.2 * up
            out.yaw = k * 0.06 * sin(2 * PI * 1.5 * g.t) * up
        }

        GestureId.PAW -> {
            // looks down at the hoof
            out.neck = k * 0.08 * smoothstep(0.0, 0.4, g.t)
            out.pitch = k * 0.05
        }
    }
    return out
}
