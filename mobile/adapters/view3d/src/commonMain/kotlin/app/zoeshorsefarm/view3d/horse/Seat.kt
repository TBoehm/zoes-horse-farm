package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.snapSpring
import app.zoeshorsefarm.shared.stepSpring
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Rider seat model (pure): pelvis rise/forward shift, torso lean and rein-hand position in rider
// space, blended from the horse's motion state.

private const val RISE = 0
private const val FORWARD = 1
private const val LEAN = 2
private const val HAND_X = 3
private const val HAND_Y = 4
private const val HAND_Z = 5
private const val FOOT_FORWARD = 6
private const val KEY_COUNT = 7

private fun seatKeys(
    rise: Double,
    forward: Double,
    lean: Double,
    handX: Double,
    handY: Double,
    handZ: Double,
    footForward: Double,
) = doubleArrayOf(rise, forward, lean, handX, handY, handZ, footForward)

private val SIT = seatKeys(0.0, 0.0, 0.1, 0.09, 0.2, 0.36, 0.0)
private val TROT_SEAT = seatKeys(0.0, 0.0, 0.25, 0.09, 0.19, 0.38, 0.0)
private val LIGHT = seatKeys(0.05, 0.03, 0.5, 0.09, 0.15, 0.43, 0.0)
private val TWO_POINT = seatKeys(0.12, 0.12, 1.25, 0.1, 0.1, 0.62, -0.03)
private val BACK = seatKeys(0.0, -0.03, -0.1, 0.09, 0.26, 0.3, 0.04)

/**
 * The seat of the rider: pelvis [rise] and [forward] shift (m), torso [lean] (rad), the rein hands
 * [handX], [handY], [handZ] and the [footForward] shift of the feet (m), in rider space. The
 * values `release`, `horsePitch`, `sway` and `roll` come with them.
 */
class SeatPose {
    internal val v = DoubleArray(KEY_COUNT)

    var rise: Double
        get() = v[RISE]
        set(x) {
            v[RISE] = x
        }
    var forward: Double
        get() = v[FORWARD]
        set(x) {
            v[FORWARD] = x
        }
    var lean: Double
        get() = v[LEAN]
        set(x) {
            v[LEAN] = x
        }
    var handX: Double
        get() = v[HAND_X]
        set(x) {
            v[HAND_X] = x
        }
    var handY: Double
        get() = v[HAND_Y]
        set(x) {
            v[HAND_Y] = x
        }
    var handZ: Double
        get() = v[HAND_Z]
        set(x) {
            v[HAND_Z] = x
        }
    var footForward: Double
        get() = v[FOOT_FORWARD]
        set(x) {
            v[FOOT_FORWARD] = x
        }
    var release = 0.0
    var horsePitch = 0.0
    var sway = 0.0
    var roll = 0.0
}

/**
 * The horse's motion as the rider sees it (a reused object): gait [weights], stride phase [phi],
 * the lead blend, jump/hop/stop weights, the jump parameter [jumpJ], the body [pitch] and neck angle.
 */
class RiderContext {
    var weights = GaitWeights(halt = 1.0)
    var phi = 0.0
    var lead = 1.0
    var jumpWeight = 0.0
    var jumpJ = 0.0
    var hopWeight = 0.0
    var stopWeight = 0.0
    var pitch = 0.0
    var neck = 0.0
    var speed = 0.0
}

// rise during one diagonal beat, sit during the other: once per stride
private fun posting(phi: Double): Double = 0.5 - 0.5 * cos(2 * PI * phi)

private fun blendInto(
    out: DoubleArray,
    src: DoubleArray,
    w: Double,
) {
    for (k in 0 until KEY_COUNT) out[k] += src[k] * w
}

private fun lerpTo(
    out: DoubleArray,
    src: DoubleArray,
    t: Double,
) {
    if (t <= 0) return
    for (k in 0 until KEY_COUNT) out[k] += (src[k] - out[k]) * t
}

/**
 * Crest release (0..1): the hands slide forward along the horse's neck while it stretches over the
 * fence (flight) and come back as it lands and the rider takes up the contact again.
 */
fun crestRelease(
    j: Double,
    jumpWeight: Double = 1.0,
): Double = jumpWeight * smoothstep(0.7, 1.35, j) * (1 - smoothstep(1.85, 2.55, j))

/** Landing absorption (0..1): the seat sinks a little when the forehand touches down. */
private fun landingAbsorb(
    j: Double,
    jumpWeight: Double,
): Double = jumpWeight * smoothstep(1.85, 2.15, j) * (1 - smoothstep(2.3, 2.9, j))

private const val RELEASE_HAND_Z = 0.1
private const val RELEASE_HAND_Y = -0.045
private const val RELEASE_HAND_X = 0.01
private const val ABSORB_RISE = -0.04
private const val ABSORB_FORWARD = -0.015

private val scratchWeights = GaitWeights()

/**
 * Splits the seat in a calm part ([base]: follows from gait weights, fold, release, stop - this is
 * what the seat filter smooths) and an immediate part ([osc]: the posting beat, the canter rhythm,
 * the balance against the horse's pitch - these follow the horse exactly). Both are added to get
 * the pose. [ctx] (optional) = horse motion. Without it the gait comes from `state.gait`.
 */
fun riderSeatParts(
    state: Horse,
    ctx: RiderContext?,
    base: SeatPose,
    osc: SeatPose,
): SeatPose {
    val weights =
        ctx?.weights ?: scratchWeights.also {
            it.clear()
            it[state.gait] = 1.0
        }
    val phi = ctx?.phi ?: 0.0
    base.v.fill(0.0)
    osc.v.fill(0.0)
    blendInto(base.v, SIT, weights.halt + weights.walk + weights.back)
    blendInto(base.v, TROT_SEAT, weights.trot)
    blendInto(base.v, LIGHT, weights.canter)
    // the rhythmic parts are scaled like the base pose: they fade out in the two-point seat
    val r = posting(phi)
    val wt = weights.trot
    val wc = weights.canter
    osc.rise = 0.1 * r * wt + 0.012 * sin(2 * PI * phi) * wc
    osc.forward = 0.07 * r * wt
    osc.lean = 0.08 * r * wt

    val j = if (ctx != null) ctx.jumpJ else jumpParam(state.jump)
    val jumpW =
        if (ctx != null) {
            ctx.jumpWeight
        } else if (state.jump != null) {
            1.0
        } else {
            0.0
        }
    val fold = jumpW * smoothstep(0.15, 0.9, j) * (1 - smoothstep(2.3, 3.0, j))
    val hopW = ctx?.hopWeight ?: if (state.hop != null) 1.0 else 0.0
    val hopFold = hopW * 0.5
    val keep = 1 - min(1.0, fold + hopFold)
    lerpTo(base.v, TWO_POINT, min(1.0, fold + hopFold))
    // release and absorption act on top of the folded seat
    val release = crestRelease(j, jumpW) + 0.4 * hopW
    val absorb = landingAbsorb(j, jumpW)
    base.handZ += RELEASE_HAND_Z * release
    base.handY += RELEASE_HAND_Y * release
    base.handX += RELEASE_HAND_X * release
    base.rise += ABSORB_RISE * absorb
    base.forward += ABSORB_FORWARD * absorb
    val stop = ctx?.stopWeight ?: 0.0
    lerpTo(base.v, BACK, stop)
    val stopKeep = 1 - min(1.0, stop)
    for (k in 0 until KEY_COUNT) osc.v[k] *= keep * stopKeep

    base.release = release
    base.horsePitch = ctx?.pitch ?: 0.0
    // keep the upper body balanced against the horse's pitch (nose up -> fold more)
    osc.lean -= base.horsePitch * 0.5
    base.sway = 0.05 * weights.walk * sin(4 * PI * phi)
    base.roll = 0.0
    return base
}

private val scratchBase = SeatPose()
private val scratchOsc = SeatPose()

/** Seat for the current frame, unfiltered (see [riderSeatParts] and [SeatFilter]). */
fun riderSeat(
    state: Horse,
    ctx: RiderContext? = null,
    out: SeatPose = SeatPose(),
): SeatPose {
    val osc = scratchOsc
    riderSeatParts(state, ctx, out, osc)
    for (k in 0 until KEY_COUNT) out.v[k] += osc.v[k]
    return out
}

// How fast the calm part follows its target (rad/s of a critically damped spring): about 60-80 ms
// time constant, so a sudden change of gait weights, fold or stop turns into a quick, soft move.
private val FILTER_OMEGA = doubleArrayOf(16.0, 16.0, 13.0, 15.0, 15.0, 15.0, 14.0)

/**
 * Stateful seat: the calm part of [riderSeatParts] goes through critically damped springs, the
 * immediate part is added unfiltered. Keeps every seat change (SIT <-> posting <-> LIGHT <->
 * TWO_POINT <-> BACK) free of jumps, whatever the horse state does from one frame to the next.
 */
class SeatFilter {
    private val springs: Array<Spring> = Array(KEY_COUNT) { createSpring(0.0) }
    private var started = false

    fun step(
        dt: Double,
        state: Horse,
        ctx: RiderContext?,
        out: SeatPose = SeatPose(),
    ): SeatPose {
        riderSeatParts(state, ctx, scratchBase, scratchOsc)
        for (k in 0 until KEY_COUNT) {
            if (!started) {
                snapSpring(springs[k], scratchBase.v[k])
            } else {
                stepSpring(springs[k], scratchBase.v[k], FILTER_OMEGA[k], 1.0, dt)
            }
            out.v[k] = springs[k].x + scratchOsc.v[k]
        }
        started = true
        out.release = scratchBase.release
        out.horsePitch = scratchBase.horsePitch
        out.sway = scratchBase.sway
        out.roll = scratchBase.roll
        return out
    }
}

fun createSeatFilter(): SeatFilter = SeatFilter()
