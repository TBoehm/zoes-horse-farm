package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.RefusalType
import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.stepSpring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max

// Horse motion state (pure): gait blending, stride phase, hoof paths per leg, body motion,
// jump/hop/refusal weights, idle gestures and footfall events. The adapter (HorseView.kt) turns
// this into bone rotations. Rule 24: everything here changes continuously - the gait weights are
// critically damped springs, the legs keep their own phase and plant their hooves (Legs.kt), jump
// and refusal weights follow smooth progress curves.

// Cross-fade of the gaits: a critically damped spring reaches 95 % after 4.7 / omega ~ 0.47 s
private const val GAIT_OMEGA = 10.0

// The hop pose follows its envelope through a critically damped spring (no quick onset)
private const val HOP_OMEGA = 18.0

// Canter lead: a flying change when the turn clearly goes the other way for a while
private const val LEAD_MIN_TURN = 0.3
private const val LEAD_DELAY = 0.4
private const val LEAD_BLEND_OMEGA = 7.0

// Jump: weight of the jump pose as a function of the jump parameter J (0 take-off ... 3 landed):
// it comes in during the first half of the take-off and goes out during the landing, so there is
// no snap at either end. The weight may not change faster than this per second (a jump that
// starts in the middle, e.g. after a restart).
private const val JUMP_IN = 0.5
private const val JUMP_OUT_FROM = 2.4
private const val JUMP_OUT_TO = 3.0

// 14 made the quick tuck-in of a trot jump turn the forearm by 0.6 rad per frame
private const val JUMP_SLEW = 11.0

// Landing events: progress of the landing phase when the forehand and the hindquarters touch down
private const val LAND_FRONT = 0.3
private const val LAND_HIND = 0.6
private const val LAND_HIND_STRENGTH = 0.65

// Idle gestures only while the horse stands still
private const val GESTURE_MAX_SPEED = 0.05

/**
 * Body bend (rad) for a turn rate (rad/s, + = right): bends towards the inside, so + = to the
 * left (+X) is negative for a right turn. Gain tuned for the agile steering (SRT-009) so that
 * it is still graded at trot/canter and only saturates at the fastest turns on the spot.
 */
fun turnBend(turn: Double): Double = clamp(-turn * 0.15, -0.35, 0.35)

/**
 * Lean (rad, + = to the right) into a turn: the centripetal acceleration v*w gives the physical
 * lean atan(v*w / g); the game takes about a third of it (at most 0.3 rad = 17 degrees) because
 * turns are much tighter than in reality (SRT-009) and the full physical lean would look like
 * falling over.
 */
fun turnLean(
    speed: Double,
    turn: Double,
): Double = clamp(0.35 * atan((speed * turn) / 9.81), -0.3, 0.3)

/** Weight of the jump pose for the jump parameter J. */
private fun jumpWeightFor(j: Double): Double =
    smoothstep(0.0, JUMP_IN, j) * (1 - smoothstep(JUMP_OUT_FROM, JUMP_OUT_TO, j))

/** Body motion of the gait mix: bob, pitch, head nod and roll. */
class BodyMotion {
    var bob = 0.0
    var pitch = 0.0
    var neck = 0.0
    var roll = 0.0
}

/** Strength of a landing in this step (0 = none). */
class LandingStrength {
    var front = 0.0
    var hind = 0.0
}

/**
 * The motion state of a horse. [rng]: random numbers for the idle gestures (without it there are
 * none).
 */
class Motion(
    rng: (() -> Double)? = null,
) {
    val weights = GaitWeights(halt = 1.0)
    private val springs: Array<Spring> = Array(GAIT_KEYS.size) { createSpring(if (it == 0) 1.0 else 0.0) }
    var phi = 0.0
    var freq = 0.0

    /** +1 left lead, -1 right lead (the one the legs follow). */
    var lead = 1.0

    /** Lead as a smooth value, for body roll and rider. */
    var leadBlend = 1.0
    var leadTimer = 0.0
    private val leadSpring = createSpring(1.0)
    val model = createLegModel()
    val legs: List<Leg> = model.legs
    var moving = false
    val body = BodyMotion()
    var jumpWeight = 0.0
    var jumpJ = 0.0
    var hopWeight = 0.0
    private val hopSpring = createSpring()
    var hopJ = 0.0
    var stopWeight = 0.0
    var runoutWeight = 0.0
    var runoutDir = 1.0
    val landing = LandingStrength()
    private var landPrev = -1.0

    /** Body bend, + = to the left (+X). */
    var bend = 0.0

    /** Lean, + = to the right (rotation.z). */
    var lean = 0.0
    var speed = 0.0
    var time = 0.0
    val gesture: GestureScheduler? = if (rng != null) GestureScheduler(rng) else null

    /** Reused result of [step] (no allocation per frame). */
    val falls = FootfallList()

    private val tmpBody = BodySample()
    private val target = GaitWeights()
    private val legInput = LegInput()

    /**
     * Advance one time step. [state] = sim.horse; [graze] (0..1) is how much the horse grazes.
     * Returns the leg indices whose hoof touched down during this step (0 LF, 1 RF, 2 LH, 3 RH).
     * The list is reused by the next call (copy it to keep it).
     */
    fun step(
        dt: Double,
        state: Horse,
        graze: Double = 0.0,
    ): FootfallList {
        val gait = state.gait
        // speed of the gait cycles (never negative); the rein-back has a negative sim speed
        val v = abs(state.speed)
        val turn = state.turnRate
        time += dt
        speed = v

        // turning on the spot: walking steps without forward travel
        val spin = smoothstep(0.15, 0.6, abs(turn))
        moving = gait != Gait.HALT || spin > 0.5
        stepLead(gait, turn, dt)
        stepWeights(gait, spin, dt)

        // stride phase: cadence of the commanded gait (calm walk cadence when turning at halt); at
        // halt it fades with the moving gaits
        val vf = if (gait == Gait.HALT) max(v, 0.9 * abs(turn)) else v
        blendGait(model, weights, v, vf, dt)
        freq = if (moving) model.fn else blendedFrequency(weights, vf)
        phi = (phi + freq * dt) % 1

        stepJump(state, dt)
        stepRefusal(state, turn, dt)
        stepLanding(state)

        // turns: bend into the turn, lean inwards (centripetal)
        bend = approach(bend, turnBend(turn), 4.0, dt)
        lean = approach(lean, turnLean(state.speed, turn), 4.0, dt)

        stepFeet(state, vf, dt)
        stepGesture(graze, v, dt)
        stepBody(gait, v)
        return falls
    }

    /** Canter lead: chosen when striking off, changed on the fly when the turn goes the other way. */
    private fun stepLead(
        gait: Gait,
        turn: Double,
        dt: Double,
    ) {
        if (gait == Gait.CANTER) {
            val want =
                if (turn > 0.05) {
                    -1.0
                } else if (turn < -0.05) {
                    1.0
                } else {
                    0.0
                }
            if (weights.canter < 0.05) {
                if (want != 0.0) lead = want
                leadTimer = 0.0
            } else if (want != 0.0 && want != lead && abs(turn) > LEAD_MIN_TURN) {
                leadTimer += dt
                if (leadTimer >= LEAD_DELAY) {
                    lead = want
                    leadTimer = 0.0
                }
            } else {
                leadTimer = max(0.0, leadTimer - 2 * dt)
            }
        } else {
            leadTimer = 0.0
        }
        smoothTo(leadSpring, lead, LEAD_BLEND_OMEGA, dt)
        leadBlend = if (abs(leadSpring.x - lead) < 1e-3) lead else leadSpring.x
    }

    /** Gait weights follow the commanded gait through critically damped springs. */
    private fun stepWeights(
        gait: Gait,
        spin: Double,
        dt: Double,
    ) {
        target.clear()
        if (gait == Gait.HALT) {
            target.walk = spin
            target.halt = 1 - spin
        } else {
            target[gait] = 1.0
        }
        var sum = 0.0
        for (i in GAIT_KEYS.indices) {
            val s = springs[i]
            stepSpring(s, target[GAIT_KEYS[i]], GAIT_OMEGA, 1.0, dt)
            if (s.x < 0) s.x = 0.0
            sum += s.x
        }
        for (i in GAIT_KEYS.indices) {
            val s = springs[i]
            s.x /= sum
            weights[GAIT_KEYS[i]] = s.x
        }
    }

    /** Jump and hop weights. */
    private fun stepJump(
        state: Horse,
        dt: Double,
    ) {
        val jump = state.jump
        if (jump != null) {
            jumpJ = jumpParam(jump)
            val wanted = jumpWeightFor(jumpJ)
            val maxStep = JUMP_SLEW * dt
            jumpWeight += clamp(wanted - jumpWeight, -maxStep, maxStep)
        } else {
            // interrupted jump (restart): fade the pose out
            jumpWeight = approach(jumpWeight, 0.0, 6.0, dt)
            if (jumpWeight < 1e-3) {
                jumpWeight = 0.0
                jumpJ = 0.0
            }
        }
        var hopTarget = 0.0
        val hop = state.hop
        if (hop != null) {
            val p = clamp(hop.progress, 0.0, 1.0)
            hopJ = 3 * p
            hopTarget = bump(p, 0.15, 0.75)
        }
        smoothTo(hopSpring, hopTarget, HOP_OMEGA, dt)
        hopWeight = clamp(hopSpring.x, 0.0, 1.0)
    }

    /** Refusal weights: the stop and the run-out. */
    private fun stepRefusal(
        state: Horse,
        turn: Double,
        dt: Double,
    ) {
        val ref = state.refusal
        val stopT = if (ref != null && ref.type == RefusalType.STOP) bump(ref.progress, 0.12, 0.6) else 0.0
        stopWeight = approach(stopWeight, stopT, 12.0, dt)
        if (ref != null && ref.type == RefusalType.RUNOUT) {
            if (runoutWeight < 0.02 && abs(turn) > 0.01) runoutDir = if (turn > 0) -1.0 else 1.0
            runoutWeight = approach(runoutWeight, bump(ref.progress, 0.2, 0.7), 8.0, dt)
        } else {
            runoutWeight = approach(runoutWeight, 0.0, 8.0, dt)
        }
    }

    /** Landing: the forehand and then the hindquarters touch down (dust, sound). */
    private fun stepLanding(state: Horse) {
        landing.front = 0.0
        landing.hind = 0.0
        val jump = state.jump
        if (jump != null && jump.phase == JumpPhase.LANDING) {
            val p = clamp(jump.progress, 0.0, 1.0)
            if (landPrev >= 0) {
                if (landPrev < LAND_FRONT && p >= LAND_FRONT) landing.front = 1.0
                if (landPrev < LAND_HIND && p >= LAND_HIND) landing.hind = LAND_HIND_STRENGTH
            }
            landPrev = p
        } else {
            landPrev = -1.0
        }
    }

    /** Hoof paths and ground contact per leg. */
    private fun stepFeet(
        state: Horse,
        vf: Double,
        dt: Double,
    ) {
        falls.clear()
        legInput.w = weights
        legInput.v = speed
        legInput.vs = state.speed
        legInput.vf = vf
        legInput.moving = moving
        legInput.gait = if (state.gait == Gait.HALT) Gait.WALK else state.gait
        legInput.lead = lead
        legInput.phi = phi
        stepLegs(model, legInput, dt, falls)
        val quiet = state.gait == Gait.HALT || jumpWeight > 0.3 || hopWeight > 0.4
        if (quiet) falls.clear()
    }

    /** Idle gestures (hoof scrape) only while the horse stands still. */
    private fun stepGesture(
        graze: Double,
        v: Double,
        dt: Double,
    ) {
        val scheduler = gesture ?: return
        val calm =
            !moving &&
                !(graze > 0.05) &&
                v < GESTURE_MAX_SPEED &&
                weights.halt > 0.95 &&
                isStill() &&
                allPlanted(legs)
        val g = scheduler.step(dt, calm)
        if (g.id == GestureId.PAW && g.weight > 0) scrapeHoof(legs[g.leg], g)
    }

    private fun isStill(): Boolean = jumpWeight < 0.01 && hopWeight < 0.01 && stopWeight < 0.01 && runoutWeight < 0.01

    /** Body bob, pitch, head nod and roll of the gait mix. */
    private fun stepBody(
        gait: Gait,
        v: Double,
    ) {
        val b = body
        b.bob = 0.0
        b.pitch = 0.0
        b.neck = 0.0
        b.roll = 0.0
        for (g in MOVING_GAITS) {
            val wg = weights[g]
            if (wg < 1e-4) continue
            bodySample(g, phi, if (g == Gait.WALK && gait == Gait.HALT) 0.3 else v, leadBlend, tmpBody)
            b.bob += wg * tmpBody.bob
            b.pitch += wg * tmpBody.pitch
            b.neck += wg * tmpBody.neck
            b.roll += wg * tmpBody.roll
        }
    }
}

/** Advance one time step of [m] for the simulation horse [state]; see [Motion.step]. */
fun stepMotion(
    m: Motion,
    dt: Double,
    state: Horse,
    graze: Double = 0.0,
): FootfallList = m.step(dt, state, graze)

fun createMotion(rng: (() -> Double)? = null): Motion = Motion(rng)

/**
 * Hoof scrape of a foreleg: the leg reaches forward, drags back twice along the ground and is
 * set down again. g.weight (0..1, smooth) scales the whole motion, so it starts and ends without
 * a pop.
 */
private fun scrapeHoof(
    leg: Leg,
    g: GestureState,
) {
    val reach = smoothstep(0.0, 0.4, g.t) * (1 - smoothstep(g.duration - 0.45, g.duration, g.t))
    val drag = 0.5 + 0.5 * cos(2 * PI * 1.7 * (g.t - 0.4)) // 1 = forward, 0 = back
    val k = g.weight * reach
    leg.dz += k * (0.16 + 0.2 * drag)
    leg.y += k * (0.05 + 0.07 * drag)
    leg.flex += k * (0.9 + 0.3 * drag)
    leg.past += k * 0.7
}

/** Base neck carriage per gait (+ = lower/forward). */
fun neckCarriage(weights: GaitWeights): Double =
    weights.halt * 0.04 + weights.walk * 0.12 + weights.trot * 0.0 + weights.canter * -0.05 + weights.back * 0.1
