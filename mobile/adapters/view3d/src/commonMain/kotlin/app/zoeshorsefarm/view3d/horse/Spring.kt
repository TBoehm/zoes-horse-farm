package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.stepSpring
import kotlin.math.exp
import kotlin.math.tanh

// Damped springs and spring chains (pure). Used for the secondary motion of mane, forelock and
// tail and for smoothing parameters.
//
// The spring step itself is in :core:shared (the rider uses it, too).

/** Largest equilibrium offset of a hair segment (rad); keeps the hair from folding over. */
private const val MAX_OFFSET = 1.1

/** Critically damped smoothing of a value towards a target (~ 95 % after 4.7 / omega seconds). */
fun smoothTo(
    s: Spring,
    target: Double,
    omega: Double,
    dt: Double,
): Spring = stepSpring(s, target, omega, 1.0, dt)

/** Soft limit: ~ x for small values, saturates at +-max (keeps spikes from kicking the hair). */
fun softClamp(
    x: Double,
    max: Double,
): Double = max * tanh(x / max)

/**
 * Settings of a chain of hanging hair segments. Gains are radians of deflection per m/s^2 of body
 * acceleration (`gainFwd`, `gainUp`, `gainLat`, one per segment); `omega0` and `omegaTail` are the
 * natural frequencies of the first and the last segment, `couple` (0..1) the share of the parent's
 * angular acceleration that a segment feels, `windGain` the share of the breeze per segment.
 */
class HairConfig(
    val count: Int,
    val omega0: Double,
    val omegaTail: Double,
    val zeta: Double,
    val gainFwd: DoubleArray,
    val gainUp: DoubleArray,
    val gainLat: DoubleArray,
    val couple: Double,
    val windGain: DoubleArray? = null,
)

/** One segment of a hair chain: angles `pitch` (fore/aft) and `sway` (sideways) as springs. */
class HairSegment(
    val omega: Double,
) {
    val pitch: Spring = createSpring()
    val sway: Spring = createSpring()
    var accP = 0.0
    var accS = 0.0
}

/**
 * Chain of hanging hair segments (tail, mane, forelock). Each segment k has two angles: `pitch`
 * (about the lateral axis, fore/aft) and `sway` (sideways). The inertial force of the body
 * acceleration pushes them away from their rest pose; later segments have a lower natural
 * frequency (they lag more) and also feel the angular acceleration of their parent segment, so a
 * motion runs through the chain like a wave.
 */
class HairChain(
    val cfg: HairConfig,
) {
    val segments: List<HairSegment> =
        List(cfg.count) { k ->
            HairSegment(
                if (cfg.count > 1) cfg.omega0 + ((cfg.omegaTail - cfg.omega0) * k) / (cfg.count - 1) else cfg.omega0,
            )
        }
}

fun createHairChain(cfg: HairConfig): HairChain = HairChain(cfg)

/** Body acceleration in the body frame (m/s^2): fwd = forward, up = vertical, lat = towards +X. */
class HairDrive(
    var fwd: Double = 0.0,
    var up: Double = 0.0,
    var lat: Double = 0.0,
)

/** Extra offsets of the hair from a breeze (rad). */
class HairWind(
    var pitch: Double = 0.0,
    var sway: Double = 0.0,
)

/**
 * Steps a hair chain. [drive] is the body acceleration in the body frame, [wind] extra offsets (rad).
 * Hair swings against the acceleration: + fwd pushes the segments back (+ pitch).
 */
fun stepHairChain(
    chain: HairChain,
    drive: HairDrive,
    dt: Double,
    wind: HairWind? = null,
): HairChain {
    val cfg = chain.cfg
    if (!(dt > 0)) return chain
    var parentAccP = 0.0
    var parentAccS = 0.0
    for (k in chain.segments.indices) {
        val s = chain.segments[k]
        val windGain = cfg.windGain?.get(k) ?: 1.0
        val wp = if (wind != null) wind.pitch * windGain else 0.0
        val ws = if (wind != null) wind.sway * windGain else 0.0
        // equilibrium offset of the spring for the (constant during this step) inertial force
        val w2 = s.omega * s.omega
        val tp =
            clamp(
                cfg.gainFwd[k] * drive.fwd + cfg.gainUp[k] * drive.up - (cfg.couple * parentAccP) / w2 + wp,
                -MAX_OFFSET,
                MAX_OFFSET,
            )
        val ts = clamp(cfg.gainLat[k] * drive.lat - (cfg.couple * parentAccS) / w2 + ws, -MAX_OFFSET, MAX_OFFSET)
        val vp = s.pitch.v
        val vs = s.sway.v
        stepSpring(s.pitch, tp, s.omega, cfg.zeta, dt)
        stepSpring(s.sway, ts, s.omega, cfg.zeta, dt)
        // angular acceleration of this segment feeds the next one
        s.accP = (s.pitch.v - vp) / dt
        s.accS = (s.sway.v - vs) / dt
        parentAccP = s.accP
        parentAccS = s.accS
    }
    return chain
}

/** Kicks a chain (e.g. a tail swish): adds an angular velocity to the sway of all segments. */
fun kickChain(
    chain: HairChain,
    pitchV: Double,
    swayV: Double,
) {
    chain.segments.forEachIndexed { k, s ->
        val f = 1 + 0.25 * k
        s.pitch.v += pitchV * f
        s.sway.v += swayV * f
    }
}

/** Limits and time constants of the [AccelEstimator]. */
class AccelLimits(
    /** s, smoothing of the accelerations. */
    val smooth: Double,
    /** s, smoothing of the speed before it is differentiated. */
    val speedTau: Double,
    val fwd: Double,
    val up: Double,
    val lat: Double,
    val turnAcc: Double,
)

val ACCEL_LIMITS = AccelLimits(smooth = 0.05, speedTau = 0.1, fwd = 10.0, up = 60.0, lat = 10.0, turnAcc = 25.0)

/** Kinematics of a frame: speed (m/s along the heading, - when backing), turnRate (rad/s, + = right), y (m). */
class AccelInput(
    var speed: Double = 0.0,
    var turnRate: Double = 0.0,
    var y: Double = 0.0,
)

/**
 * Body acceleration estimator (pure, no allocation): feeds on the body kinematics of a frame and
 * returns smoothed acceleration `fwd`, `up`, `lat` in the body frame.
 */
class AccelEstimator {
    var primed = false

    /** Low-pass filtered speed. */
    var speed = 0.0
    var prevSpeed = 0.0
    var prevTurn = 0.0
    var prevY = 0.0
    var vy = 0.0
    var fwd = 0.0
    var up = 0.0
    var lat = 0.0
    var turnAcc = 0.0
}

fun createAccelEstimator(): AccelEstimator = AccelEstimator()

/** Per-frame estimate; the smoothing time constants are in seconds. */
fun stepAccelEstimator(
    est: AccelEstimator,
    input: AccelInput,
    dt: Double,
    limits: AccelLimits = ACCEL_LIMITS,
): AccelEstimator {
    if (!(dt > 0)) return est
    val speed = input.speed
    val turn = input.turnRate
    val y = input.y
    if (!est.primed) {
        est.primed = true
        est.speed = speed
        est.prevSpeed = speed
        est.prevTurn = turn
        est.prevY = y
        return est
    }
    val k = 1 - exp(-dt / limits.smooth)
    // The speed is filtered before it is differentiated: a stop that the sim does in one frame
    // (frontal fence) becomes a short strong deceleration instead of a spike of one frame, which
    // keeps its impulse (the change of speed) and lets the hair swing.
    est.speed += (speed - est.speed) * (1 - exp(-dt / limits.speedTau))
    val aFwd = (est.speed - est.prevSpeed) / dt
    val vy = (y - est.prevY) / dt
    val aUp = (vy - est.vy) / dt
    val turnAcc = (turn - est.prevTurn) / dt
    est.prevSpeed = est.speed
    est.prevTurn = turn
    est.prevY = y
    est.vy = vy
    est.fwd += (softClamp(aFwd, limits.fwd) - est.fwd) * k
    est.up += (softClamp(aUp, limits.up) - est.up) * k
    est.turnAcc += (softClamp(turnAcc, limits.turnAcc) - est.turnAcc) * k
    // centripetal acceleration towards the inside of the turn: + = to the right (-X)
    est.lat += (softClamp(speed * turn, limits.lat) - est.lat) * k
    return est
}
