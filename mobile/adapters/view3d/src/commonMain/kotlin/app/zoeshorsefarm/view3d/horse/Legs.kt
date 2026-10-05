package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Hoof model of the four legs (pure): one stride phase per leg, hoof paths from gait parameters
// that are blended over time, and planted hooves.
//
// Why not blend the poses of two gaits? A hoof that is in stance in one gait and in swing in the
// other would be averaged to a position that matches neither, and in stance it would slide over the
// ground. Here every leg has ONE path at any time:
//  - its phase q follows the stride phase of the commanded gait; when the commanded gait or the
//    canter lead changes the legs approach the new phases gradually (they speed up or slow down a
//    little, in stance and in swing), so every leg stays on a plausible path and nothing pops;
//  - the shape of the path (duty factor, lift, flexion, stride length) is the weighted mix of the
//    gait parameters; the mix is smooth because the gait weights are;
//  - in stance the hoof is anchored: its position relative to the body is where it touched down
//    minus the distance the body has travelled since, so it never slides, whatever the speed, the
//    cadence or the gait mix do; the swing path runs from where the hoof left the ground to where
//    it should touch down, so it is continuous at both ends;
//  - when the horse stops, legs in swing finish their step, legs in stance stay planted and are
//    brought square one at a time with a small step (like a horse shuffling its feet).
//
// Leg index: 0 = LF, 1 = RF, 2 = LH, 3 = RH. dz is the fore/aft offset of the hoof in model space.

// How fast a leg approaches the phase of the commanded gait (1/s)
private const val PHASE_GAIN = 5.0

// a leg may run up to this share of the cadence faster ...
private const val PHASE_FAST = 0.3

// ... or (in swing only) slower than the cadence
private const val PHASE_SLOW = 0.45

// Square-up step of a leg that stands away from its neutral position when the horse has stopped
private const val SQUARE_TIME = 0.42 // s
private const val SQUARE_MIN_OFFSET = 0.05 // m: shorter offsets stay as they are
private const val SQUARE_MAX_SPEED = 0.15 // m/s: only when the horse really stands
private const val SQUARE_LIFT = 0.07
private const val SQUARE_FLEX = 0.9
private const val SQUARE_PAST = 0.7

private const val FREEZE_SPEED = 0.1 // m/s: below this a stopped horse plants its legs

// Longest stance travel of a hoof (m): what the legs can reach with the IK of HorseView.kt, with a
// margin to the full stretch: a hoof that touches down or lifts off at the full stretch makes the
// joints snap (the IK angle follows acos of the reach). A faster gait shortens the duty factor
// instead of stretching the stride (real horses do the same).
private const val REACH_TRAVEL = 0.85
private const val MAX_TRAVEL = REACH_TRAVEL * 1.1 // m: the planted hoof never goes further than this

private const val CONTACT_Y = 0.004 // m: lower than this the hoof counts as on the ground
private const val MIN_PEAK = 0.02 // m: a swing lower than this does not make a footfall (stepping in place)

// Share of the ground speed at which the hoof leaves and meets the ground (1 would match the
// stance exactly but moves the hoof faster than 0.13 m per frame in the middle of a canter swing)
private const val GROUND_SPEED_SHARE = 0.3
private const val SHAPE_TAU = 0.1 // s: smoothing of the speed-dependent shape of the steps
private const val HOLD_TAU = 0.5 // s: release time of the step amplitude when the moving gaits fade out
private const val RB_EPS = 0.02 // keeps the rein-back share continuous when the moving gaits fade out
private const val HALT_SINK = 0.008 // fetlock drop of a standing horse

/** Footfalls of one step (leg indices); a reused list, so that nothing is allocated per frame. */
class FootfallList {
    private val items = IntArray(MAX_FALLS)

    var size = 0
        private set

    operator fun get(i: Int): Int = items[i]

    fun add(leg: Int) {
        if (size < MAX_FALLS) items[size++] = leg
    }

    fun clear() {
        size = 0
    }

    fun toList(): List<Int> = List(size) { items[it] }

    private companion object {
        const val MAX_FALLS = 8
    }
}

/**
 * One leg: the outputs (read by the adapter) `dz`, `y`, `flex`, `past`, `sink`, `stance`, `c`
 * and the state of its step cycle.
 */
class Leg {
    var dz = 0.0
    var y = 0.0
    var flex = 0.0
    var past = 0.0
    var sink = 0.0
    var stance = true
    var c = 1.0

    /** Phase of the leg: stance q < duty, swing duty <= q < 1. */
    var q = 0.2
    var inStance = true

    /** dz at touchdown. */
    var anchor = 0.0

    /** Distance the body has moved (forwards +) since touchdown. */
    var travel = 0.0

    /** dz at lift-off. */
    var from = 0.0

    /** Highest lift of the current swing. */
    var peak = 0.0
    var squaring = false
    var squareT = 0.0
    var squareFrom = 0.0
}

/** Blended gait parameters (reused every frame). */
class GaitBlend {
    /** Share of the moving gaits in the mix. */
    var s = 0.0

    /** Share of the rein-back among them. */
    var rb = 0.0
    var sink = 0.0
    val c = DoubleArray(4)
    val lift = DoubleArray(4)
    val flex = DoubleArray(4)
    val past = DoubleArray(4)
}

/** What [stepLegs] needs to know about the horse in this frame. */
class LegInput {
    var w = GaitWeights()

    /** Speed (>= 0). */
    var v = 0.0

    /** Signed speed along the heading (- when backing). */
    var vs = 0.0

    /** Speed for the cadence. */
    var vf = 0.0

    /** A gait is commanded; false at halt. */
    var moving = false

    /** Commanded moving gait, for the phase offsets. */
    var gait = Gait.WALK

    /** +1 left, -1 right. */
    var lead = 1.0

    /** Stride phase of the horse. */
    var phi = 0.0
}

/** The four legs with the blended gait parameters. */
class LegModel {
    val legs: List<Leg> = List(4) { Leg() }

    /** Cadence of the commanded gait (strides/s). */
    var fn = 1.0
    var duty = 0.6
    var hold = 0.0
    var primed = false

    /** Smoothed stride length (m). */
    var stride = 0.0

    /** Smoothed signed speed (m/s), for the speed of the hoof at lift-off and touch-down. */
    var vsm = 0.0

    /** Amplitudes per unit of the moving gaits' share (last known). */
    val shapeLift = DoubleArray(4)
    val shapeFlex = DoubleArray(4)
    val shapePast = DoubleArray(4)
    val blend = GaitBlend()
    internal val ctx = StepContext()
}

fun createLegModel(): LegModel = LegModel()

/**
 * Mix of the gait parameters for gait weights [w] at speed v. Amplitudes (center, lift, flexion,
 * pastern) are weighted sums, so they fade out with the share of the moving gaits; duty factor
 * and cadence are normalised averages.
 */
fun blendGait(
    model: LegModel,
    w: GaitWeights,
    v: Double,
    vf: Double,
    dt: Double = 0.0,
): LegModel {
    val p = model.blend
    p.s = 0.0
    p.sink = 0.0
    for (i in 0 until 4) {
        p.c[i] = 0.0
        p.lift[i] = 0.0
        p.flex[i] = 0.0
        p.past[i] = 0.0
    }
    var duty = 0.0
    var fn = 0.0
    for (g in MOVING_GAITS) {
        val wg = w[g]
        if (wg < 1e-4) continue
        val spec = GAITS[g]
        val ls = liftScaleFor(g, v)
        p.s += wg
        val f = spec.freq(vf)
        duty += wg * min(spec.duty(v), (REACH_TRAVEL * f) / max(v, 1e-3))
        fn += wg * f
        p.sink += wg * spec.sink
        for (i in 0 until 4) {
            p.c[i] += wg * spec.center[i]
            p.lift[i] += wg * spec.lift[i] * ls
            p.flex[i] += wg * spec.flex[i] * ls
            p.past[i] += wg * spec.past[i] * ls
        }
    }
    // Duty factor, step amplitude and stride length depend on the speed, and the sim may change the
    // speed from one frame to the next (a stop at the fence): smooth them, so that a step in
    // progress does not jump.
    val k = if (dt > 0) 1 - exp(-dt / SHAPE_TAU) else 1.0
    if (p.s >= 1e-4) {
        model.duty = if (model.primed) model.duty + (duty / p.s - model.duty) * k else duty / p.s
        model.fn = fn / p.s
    }
    p.rb = w.back / (p.s + RB_EPS)
    // The amplitude of the steps fades out slower than the moving gaits do, so that a step in
    // progress when the horse stops is still lifted (and does not scrape over the ground).
    model.hold = max(p.s, model.hold * exp(-dt / HOLD_TAU))
    if (p.s >= 1e-3) {
        val kk = if (model.primed) k else 1.0
        for (i in 0 until 4) {
            model.shapeLift[i] += (p.lift[i] / p.s - model.shapeLift[i]) * kk
            model.shapeFlex[i] += (p.flex[i] / p.s - model.shapeFlex[i]) * kk
            model.shapePast[i] += (p.past[i] / p.s - model.shapePast[i]) * kk
        }
        model.primed = true
    }
    for (i in 0 until 4) {
        p.lift[i] = model.shapeLift[i] * model.hold
        p.flex[i] = model.shapeFlex[i] * model.hold
        p.past[i] = model.shapePast[i] * model.hold
    }
    return model
}

/**
 * Advances the legs by [dt] (call [blendGait] first). Touch-downs of real steps are pushed to
 * [falls] (leg indices).
 */
fun stepLegs(
    model: LegModel,
    input: LegInput,
    dt: Double,
    falls: FootfallList?,
) {
    val p = model.blend // blendGait() has been called for this frame
    val strideNow = if (p.s >= 1e-4) min(REACH_TRAVEL, (model.duty * input.v) / model.fn) else 0.0
    val shape = if (dt > 0) 1 - exp(-dt / SHAPE_TAU) else 1.0
    model.stride += (strideNow - model.stride) * shape
    model.vsm += (input.vs - model.vsm) * shape
    val ctx = model.ctx
    ctx.input = input
    ctx.offsets = offsetsFor(input.gait, input.lead)
    ctx.baseSink = input.w.halt * HALT_SINK
    ctx.dt = dt
    for (i in 0 until 4) {
        val leg = model.legs[i]
        if (leg.squaring) {
            stepSquare(leg, dt, ctx.baseSink)
        } else {
            stepCycle(model, leg, i, falls)
        }
        val lifted = leg.y >= CONTACT_Y
        leg.stance = !lifted
        leg.c = if (lifted) 1 - min(1.0, leg.y / 0.02) else 1.0
    }
    // bring a leg that stands away from the neutral position square, one at a time
    if (!input.moving && input.v < SQUARE_MAX_SPEED && allPlanted(model.legs)) startSquareStep(model)
}

private fun startSquareStep(model: LegModel) {
    var worst = -1
    var best = SQUARE_MIN_OFFSET
    for (i in 0 until 4) {
        val off = abs(model.legs[i].dz)
        if (off > best) {
            best = off
            worst = i
        }
    }
    if (worst >= 0) {
        val leg = model.legs[worst]
        leg.squaring = true
        leg.squareT = 0.0
        leg.squareFrom = leg.dz
    }
}

/** Do all legs stand on the ground (no step, no square-up step in progress)? */
fun allPlanted(legs: List<Leg>): Boolean {
    for (i in legs.indices) if (!legs[i].inStance || legs[i].squaring) return false
    return true
}

private fun stepSquare(
    leg: Leg,
    dt: Double,
    baseSink: Double,
) {
    leg.squareT += dt
    val u = clamp(leg.squareT / SQUARE_TIME, 0.0, 1.0)
    leg.dz = leg.squareFrom * (1 - swingTravel(u))
    leg.y = SQUARE_LIFT * swingLift(u)
    leg.flex = SQUARE_FLEX * swingFlex(u)
    leg.past = SQUARE_PAST * swingPast(u)
    leg.sink = baseSink
    if (u >= 1) {
        leg.squaring = false
        leg.inStance = true
        leg.anchor = 0.0
        leg.travel = 0.0
        leg.q = 0.05
        leg.dz = 0.0
        leg.y = 0.0
        leg.flex = 0.0
        leg.past = 0.0
    }
}

/** Per-frame values that every leg's step needs (one reused object per model). */
internal class StepContext {
    var input = LegInput()
    var offsets = DoubleArray(4)
    var baseSink = 0.0
    var dt = 0.0
}

private fun stepCycle(
    model: LegModel,
    leg: Leg,
    i: Int,
    falls: FootfallList?,
) {
    val ctx = model.ctx
    val input = ctx.input
    val dt = ctx.dt
    val p = model.blend
    // rein-back: the cycle runs the other way (the hoof lands behind its neutral position)
    val sgn = 1 - 2 * p.rb
    val d = model.duty
    val c = p.c[i]
    // the hoof stays where it is on the ground, so it moves back by what the body travels
    if (leg.inStance) leg.travel = clamp(leg.travel + input.vs * dt, -MAX_TRAVEL, MAX_TRAVEL)

    // 1. phase: follow the commanded gait; at halt only a step in progress is finished
    val rate = phaseRate(model, leg, i)
    var q = leg.q + rate * dt
    var wrapped = false
    if (q >= 1) {
        q -= 1
        wrapped = true
    }
    leg.q = q

    // 2. touch-down at the end of the swing, lift-off at the end of the stance
    if (!leg.inStance && wrapped) {
        leg.inStance = true
        leg.anchor = c + (sgn * model.stride) / 2
        leg.travel = 0.0
        if (leg.peak >= MIN_PEAK) falls?.add(i)
    } else if (leg.inStance) {
        // lift-off at the end of the stance, or when the hoof is as far back as the leg reaches
        // (e.g. when the horse sets off faster than its legs were prepared for)
        val rear = sgn * (leg.anchor - leg.travel - c)
        if (q >= d || rear < -REACH_TRAVEL / 2) {
            leg.inStance = false
            leg.from = leg.anchor - leg.travel
            leg.peak = 0.0
            if (q < d) leg.q = d
        }
    }

    // 3. hoof path
    if (leg.inStance) {
        leg.dz = leg.anchor - leg.travel
        leg.y = 0.0
        leg.flex = 0.0
        leg.past = 0.0
        leg.sink = ctx.baseSink + p.sink * sin(PI * clamp(q / d, 0.0, 1.0))
    } else {
        swingPath(model, leg, i, q, sgn, ctx.baseSink)
    }
}

/** Rate of the phase of a leg (strides/s): follows the commanded gait; at halt only a step in progress is finished. */
private fun phaseRate(
    model: LegModel,
    leg: Leg,
    i: Int,
): Double {
    val ctx = model.ctx
    val input = ctx.input
    val fn = model.fn
    if (input.moving) {
        var err = legPhase(input.phi, ctx.offsets[i]) - leg.q
        err -= floor(err + 0.5)
        // in stance a leg can only hurry (it would overextend if it waited), in swing it can also wait
        val corr = clamp(PHASE_GAIN * err, if (leg.inStance) 0.0 else -PHASE_SLOW * fn, PHASE_FAST * fn)
        return fn + corr
    }
    // a horse that is still gliding keeps stepping; at a stand a step in progress is finished
    return if (!leg.inStance || input.v > FREEZE_SPEED) fn else 0.0
}

private fun swingPath(
    model: LegModel,
    leg: Leg,
    i: Int,
    q: Double,
    sgn: Double,
    baseSink: Double,
) {
    val p = model.blend
    val d = model.duty
    val u = clamp((q - d) / (1 - d), 0.0, 1.0)
    val target = p.c[i] + (sgn * model.stride) / 2
    // The hoof leaves and meets the ground moving back relative to the body, as the ground does
    // (see swingEnds), so that its speed is nearly continuous at lift-off and touch-down: a hoof
    // that stops dead at touch-down and sets off at once in stance makes the joints of the leg
    // turn by half a radian in one frame.
    val slope = (-model.vsm * (1 - d) * GROUND_SPEED_SHARE) / model.fn
    leg.dz = leg.from + (target - leg.from) * swingTravel(u) + slope * swingEnds(u)
    leg.y = p.lift[i] * swingLift(u)
    leg.flex = p.flex[i] * swingFlex(u)
    leg.past = p.past[i] * swingPast(u)
    leg.sink = baseSink
    if (leg.y > leg.peak) leg.peak = leg.y
}
