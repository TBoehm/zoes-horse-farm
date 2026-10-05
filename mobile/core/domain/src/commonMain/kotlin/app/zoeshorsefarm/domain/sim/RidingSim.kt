package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Riding simulation (contract: docs/specs/springreiten-trainer/architecture.md, "Reit-Simulation").
// Pure and deterministic: all random draws go through the injected rng.

/**
 * Player input of one step: steer -1..1 (right +), throttle -1..1, gallop held, jump pressed.
 * Mutable so the input adapter can reuse one instance every frame ([set]); the sim only reads it.
 */
data class SimInput(
    var steer: Double = 0.0,
    var throttle: Double = 0.0,
    var gallop: Boolean = false,
    var jump: Boolean = false,
) {
    /** Overwrites all four values. */
    fun set(
        steer: Double,
        throttle: Double,
        gallop: Boolean,
        jump: Boolean,
    ) {
        this.steer = steer
        this.throttle = throttle
        this.gallop = gallop
        this.jump = jump
    }
}

/** Why the gallop ended without the player's doing. */
enum class GallopEndReason(
    val id: String,
) {
    REFUSAL("refusal"),
    FENCE("fence"),
}

/** Something that happened in one simulation step. */
sealed interface SimEvent {
    /** The gallop was ended by [reason] (the player has to press gallop anew). */
    data class GallopEnded(
        val reason: GallopEndReason,
    ) : SimEvent

    /** Space without an obstacle in reach: the horse hops (does not count). */
    data object Hop : SimEvent

    /** Takeoff over [elementId]; [self] = the horse jumped on its own, [risk] = knockdown risk. */
    data class Takeoff(
        val elementId: String,
        val dir: Int,
        val self: Boolean,
        val risk: Double,
    ) : SimEvent

    /** Pole [rail] of [elementId] fell while jumping in direction [dir]. */
    data class RailDown(
        val elementId: String,
        val rail: Int,
        val dir: Int,
    ) : SimEvent

    /** The jump over [elementId] is over; [knocked] = a pole fell. */
    data class Landed(
        val elementId: String,
        val dir: Int,
        val knocked: Boolean,
    ) : SimEvent

    /** The horse evaded an obstacle without a fault (locked, no-refusal obstacle, hit a stand). */
    data class Swerve(
        val elementId: String,
    ) : SimEvent

    data class Refusal(
        val elementId: String,
        val dir: Int,
        val reason: RefusalReason,
    ) : SimEvent

    /** The horse stopped frontally at the fence. */
    data object FenceStop : SimEvent
}

/** Rules a mode (course run, free mode) gives the simulation. */
fun interface SimRules {
    /** May the horse refuse (and be penalised) at [elementId] jumped in direction [dir]? */
    fun canRefuse(
        elementId: String,
        dir: Int,
    ): Boolean
}

/** Default rules: the horse may refuse everywhere. */
val ALWAYS_REFUSE = SimRules { _, _ -> true }

/**
 * The obstacle the horse is approaching (the nearest one, glossary "approach"). [RidingSim.approach]
 * returns the same instance on every read: read it every frame, do not keep it (use `copy()`).
 */
data class SimApproach(
    var elementId: String = "",
    var dir: Int = 1,
    var distance: Double = 0.0,
    var angle: Double = 0.0,
)

private class Crossing(
    val rail: Int,
    val u: Double,
    val falls: Boolean,
    var done: Boolean = false,
)

private class ActiveJump(
    val el: Element,
    val dir: Int,
    val path: Double,
    val v: Double,
    val peak: Double,
    val crossings: Array<Crossing>,
) {
    var traveled = 0.0
    var knocked = false
}

private class ActiveRefusal(
    val type: RefusalType,
    val elementId: String,
    val decel: Double,
) {
    var t = 0.0
}

private enum class Face { FRONT, SIDE }

private class Maneuver(
    val el: Element,
    val outHeading: Double,
    val origHeading: Double,
) {
    var out = true
    var t = 0.0
    var aligned = false
}

// numeric tolerances
private const val LAST_POINT_EPS = 1e-6
private const val CONTACT_EPS = 1e-6
private const val ALIGN_EPS = 1e-9
private const val RAY_PARALLEL_EPS = 1e-9

private fun sideOf(value: Double): Double = if (value >= 0) 1.0 else -1.0

/**
 * The riding simulation: one horse in an arena with obstacles. Call [step] with the player input
 * every frame; it returns the events of the step (valid copy, not reused). [horse] is live: the
 * simulation mutates it in place, read it every frame. All randomness comes from [rng].
 */
@Suppress("LargeClass") // a direct port of one state machine whose parts share the mutable ride state
class RidingSim(
    obstacles: List<Obstacle> = emptyList(),
    private val rules: SimRules = ALWAYS_REFUSE,
    // Default is a fixed-seed generator: the domain never reads a random source itself; the caller
    // (composition root / ride screen) injects the real random source.
    private val rng: () -> Double = createRng(1),
    tuning: Tuning = TUNING,
) {
    private val t = tuning

    /** The horse as the view needs it; mutated in place by [step] and [reset]. */
    val horse = Horse()

    private val elements = ArrayList<Element>()
    private val byId = HashMap<String, Element>()
    private val railMap = HashMap<String, BooleanArray>()

    // blocked extents per element, same index as `elements` (front margin / rear clearance)
    private var extAlong = DoubleArray(0)
    private var extAcross = DoubleArray(0)
    private var rearAlong = DoubleArray(0)

    private val jumpView = JumpView()
    private val hopView = HopView()
    private val refusalView = RefusalView()

    private var settling = false

    // Speed controller state shared by rein-back and the normal speed update
    private val control = SpeedState()
    private var gallopBlocked = false
    private var fenceStopNormal: Vec2? = null
    private var jump: ActiveJump? = null
    private var hopActive = false
    private var hopT = 0.0
    private var refusal: ActiveRefusal? = null
    private var maneuver: Maneuver? = null
    private val approachView = SimApproach()
    private var approachValid = false

    // scratch objects: the per-frame code never allocates
    private val tmpInfo = ApproachInfo()
    private val tmpZone = Zone()
    private val takeoffZone = Zone()
    private var candEl: Element? = null
    private val candInfo = ApproachInfo()
    private val candZone = Zone()

    // seconds a Space press during landing stays valid for the next obstacle
    private var spaceBuffer = 0.0
    private val locks = HashSet<String>()
    private val armed = HashSet<String>()
    private val events = ArrayList<SimEvent>()

    // sanitized input of the current step (fields instead of an object: no allocation per frame)
    private var inSteer = 0.0
    private var inThrottle = 0.0
    private var inGallop = false

    // scratch of rayHitsBox
    private var rayMin = 0.0
    private var rayMax = 0.0

    /** Rails per element id: true = pole up. Live, rebuilt by [rebuild] / [rebuildAll]. */
    val rails: Map<String, BooleanArray> get() = railMap

    /** The obstacle that is being approached, or null. Refreshed after every [step]. */
    val approach: SimApproach? get() = if (approachValid) approachView else null

    init {
        setObstacles(obstacles)
        reset()
    }

    private fun setObstacles(list: List<Obstacle>) {
        elements.clear()
        byId.clear()
        railMap.clear()
        for (obstacle in list) {
            for (el in obstacle.elements) {
                elements.add(el)
                byId[el.id] = el
                railMap[el.id] = BooleanArray(railLayout(el).size) { true }
            }
        }
        extAlong = DoubleArray(elements.size) { blockExtents(elements[it], t).along }
        extAcross = DoubleArray(elements.size) { blockExtents(elements[it], t).across }
        rearAlong = DoubleArray(elements.size) { blockExtents(elements[it], t, t.reinBack.rearClearance).along }
        armed.clear()
        locks.clear()
        jump = null
        updateApproach()
    }

    /** Puts the horse at a pose and clears every transient state (new ride, restart). */
    fun reset(
        x: Double = 0.0,
        z: Double = 0.0,
        heading: Double = 0.0,
        speed: Double = 0.0,
        gallop: Boolean = false,
    ) {
        horse.x = x
        horse.z = z
        horse.heading = wrapAngle(heading)
        horse.speed = speed
        horse.gallop = gallop
        horse.gait = gaitForSpeed(speed, gallop, t.speeds)
        horse.y = 0.0
        horse.jump = null
        horse.hop = null
        horse.refusal = null
        horse.turnRate = 0.0
        settling = false
        control.backHold = 0.0
        control.backBlocked = false
        // after a restart the horse only gallops after a fresh key press
        gallopBlocked = !gallop
        fenceStopNormal = null
        jump = null
        hopActive = false
        refusal = null
        maneuver = null
        spaceBuffer = 0.0
        locks.clear()
        armed.clear()
        updateApproach()
    }

    /** Puts the horse at [pose] (e.g. the start pose of a mode). */
    fun reset(
        pose: HorsePose,
        speed: Double = 0.0,
        gallop: Boolean = false,
    ) = reset(pose.x, pose.z, pose.heading, speed, gallop)

    /** Puts the poles of [elementId] up again. */
    fun rebuild(elementId: String) {
        railMap[elementId]?.fill(true)
    }

    fun rebuildAll() {
        for (r in railMap.values) r.fill(true)
    }

    // ---- Approach -----------------------------------------------------------

    private fun updateApproach() {
        // A horse that reins back is not approaching anything, whatever it faces
        val el = if (horse.speed < 0 || !findNearestApproach(withinReach = false)) null else candEl
        approachValid = el != null
        if (el != null) {
            approachView.elementId = el.id
            approachView.dir = candInfo.dir
            approachView.distance = candInfo.distance
            approachView.angle = candInfo.angle
        }
    }

    /**
     * Finds the nearest element the horse is approaching, ignoring the one being jumped; with
     * [withinReach] only elements whose takeoff reach has been entered. The result is left in
     * [candEl], [candInfo] and (with [withinReach]) [candZone].
     */
    private fun findNearestApproach(withinReach: Boolean): Boolean {
        candEl = null
        val active = jump
        // indexed loops: an iterator would be allocated on every call
        for (i in elements.indices) {
            val el = elements[i]
            val toward =
                (active == null || active.el !== el) && approachInfoInto(el, horse, t.approachDistance, tmpInfo)
            if (toward && tmpInfo.approaching) considerCandidate(el, withinReach)
        }
        return candEl != null
    }

    private fun considerCandidate(
        el: Element,
        withinReach: Boolean,
    ) {
        if (withinReach) zoneInto(el, horse.speed, t, tmpZone)
        val inReach = !withinReach || tmpInfo.distance <= tmpZone.reach
        if (inReach && (candEl == null || tmpInfo.distance < candInfo.distance)) {
            candEl = el
            candInfo.set(tmpInfo)
            candZone.set(tmpZone)
        }
    }

    // ---- Gallop ---------------------------------------------------------------

    private fun updateGallop() {
        if (!inGallop) gallopBlocked = false
        val want = inGallop && !gallopBlocked && refusal == null
        if (want && !horse.gallop) settling = false
        // Released during the strike-off (still below trotMin): fall back to trot, not to a walk.
        // Forced ends (fence, refusal) go through endGallop and stay halts.
        if (horse.gallop && !want && horse.speed < t.speeds.trotMin) settling = true
        horse.gallop = want
    }

    private fun endGallop(reason: GallopEndReason) {
        horse.gallop = false
        gallopBlocked = true
        events.add(SimEvent.GallopEnded(reason))
    }

    // ---- Jumping ---------------------------------------------------------------

    // `buffered`: a press carried over from the landing; it only jumps when an obstacle is within
    // reach and never turns into a hop.
    private fun pressJump(buffered: Boolean = false) {
        // no jump, no hop and no buffered press while the horse reins back (rule 9)
        if (refusal != null || maneuver != null || horse.speed < 0) {
            spaceBuffer = 0.0
            return
        }
        if (jump != null) {
            // a press shortly before landing is kept for the next obstacle (combination part b)
            if (horse.jump?.phase == JumpPhase.LANDING) spaceBuffer = t.jump.spaceBuffer
            return
        }
        if (findNearestApproach(withinReach = true)) {
            jumpAtCandidate()
        } else if (!buffered) {
            startHop()
        }
    }

    /** Obstacle within reach: jump or nothing (no hop), rules 19, 21. */
    private fun jumpAtCandidate() {
        spaceBuffer = 0.0
        val el = checkNotNull(candEl) { "no candidate" }
        val ok =
            gaitAllows(el, horse.gait) &&
                candInfo.angle <= t.jump.maxAngle &&
                candInfo.distance >= candZone.lastPoint - LAST_POINT_EPS
        if (ok) takeoff(el, candInfo, false)
    }

    private fun startHop() {
        if ((horse.gait == Gait.TROT || horse.gait == Gait.CANTER) && !hopActive) {
            hopActive = true
            hopT = 0.0
            events.add(SimEvent.Hop)
        }
    }

    private fun chooseRail(
        el: Element,
        info: ApproachInfo,
        zone: Zone,
    ): Int {
        val up = railMap.getValue(el.id)
        if (up.size == 1) return if (up[0]) 0 else -1
        val first = if (info.dir > 0) 0 else 1
        val second = 1 - first
        val pref =
            if (info.distance < zone.near) {
                first
            } else if (info.distance > zone.far) {
                second
            } else if (rng() < t.jump.railChoice.firstProbability) {
                first
            } else {
                second
            }
        if (up[pref]) return pref
        return if (up[1 - pref]) 1 - pref else -1
    }

    private fun takeoff(
        el: Element,
        info: ApproachInfo,
        self: Boolean,
    ) {
        zoneInto(el, horse.speed, t, takeoffZone)
        val risk =
            takeoffRisk(
                el,
                TakeoffState(
                    gait = horse.gait,
                    speed = horse.speed,
                    distance = info.distance,
                    angle = info.angle,
                    self = self,
                ),
                t,
            )
        // The knockdown is decided before the jump; in the safe core (risk 0) there is no random draw
        val fallRail = if (risk > 0 && rng() < risk) chooseRail(el, info, takeoffZone) else -1
        val u0 = localAlong(el, horse.x, horse.z) * info.dir
        val uEnd = el.spread / 2 + landingDistance(el, info.distance, t)
        val layout = railLayout(el)
        jump =
            ActiveJump(
                el = el,
                dir = info.dir,
                path = (uEnd - u0) / cos(info.angle),
                v = max(horse.speed, t.jump.flight.minSpeed),
                peak = el.height + t.jump.flight.clearance,
                crossings =
                    Array(layout.size) {
                        val r = layout[it]
                        Crossing(rail = r.rail, u = r.along * info.dir, falls = r.rail == fallRail)
                    },
            )
        hopActive = false
        armed.remove(el.id)
        horse.turnRate = 0.0
        events.add(SimEvent.Takeoff(el.id, info.dir, self, risk))
        syncJumpView(0.0)
    }

    private fun syncJumpView(s: Double) {
        val f = t.jump.flight
        val active = checkNotNull(jump) { "syncJumpView without a jump" }
        val phase: JumpPhase
        val progress: Double
        if (s < f.takeoffShare) {
            phase = JumpPhase.TAKEOFF
            progress = s / f.takeoffShare
        } else if (s < 1 - f.landingShare) {
            phase = JumpPhase.FLIGHT
            progress = (s - f.takeoffShare) / (1 - f.takeoffShare - f.landingShare)
        } else {
            phase = JumpPhase.LANDING
            progress = (s - (1 - f.landingShare)) / f.landingShare
        }
        jumpView.phase = phase
        jumpView.progress = clamp(progress, 0.0, 1.0)
        jumpView.elementId = active.el.id
        horse.jump = jumpView
        horse.y = active.peak * sin(PI * s)
    }

    private fun crossRails(
        active: ActiveJump,
        u: Double,
    ) {
        val up = railMap.getValue(active.el.id)
        for (c in active.crossings) {
            if (c.done || u < c.u) continue
            c.done = true
            if (c.falls && up[c.rail]) {
                up[c.rail] = false
                active.knocked = true
                events.add(SimEvent.RailDown(active.el.id, c.rail, active.dir))
            }
        }
    }

    private fun advanceJump(
        active: ActiveJump,
        dt: Double,
    ) {
        advance(horse, active.v, dt)
        active.traveled += active.v * dt
        val s = min(1.0, active.traveled / active.path)
        val u = localAlong(active.el, horse.x, horse.z) * active.dir
        crossRails(active, if (s >= 1) Double.POSITIVE_INFINITY else u)
        if (s >= 1) {
            events.add(SimEvent.Landed(active.el.id, active.dir, active.knocked))
            jump = null
            horse.jump = null
            horse.y = 0.0
            return
        }
        syncJumpView(s)
    }

    // ---- Last takeoff point, refusal, evasion ------------------------

    private fun checkLastPoints() {
        candEl = null
        for (i in elements.indices) {
            val el = elements[i]
            if (approachInfoInto(el, horse, t.approachDistance, tmpInfo) && tmpInfo.approaching) {
                considerLastPoint(el)
            } else {
                armed.remove(el.id)
            }
        }
        val best = candEl
        if (best != null) decide(best, candInfo)
    }

    private fun considerLastPoint(el: Element) {
        if (isArmedAtLastPoint(el, tmpInfo) && (candEl == null || tmpInfo.distance < candInfo.distance)) {
            candEl = el
            candInfo.set(tmpInfo)
        }
    }

    /** Arms an element while the horse is before its last takeoff point; true once it is past it, armed. */
    private fun isArmedAtLastPoint(
        el: Element,
        info: ApproachInfo,
    ): Boolean {
        zoneInto(el, horse.speed, t, tmpZone)
        if (info.distance > tmpZone.lastPoint) {
            armed.add(el.id)
            return false
        }
        return armed.contains(el.id)
    }

    private fun decide(
        el: Element,
        info: ApproachInfo,
    ) {
        armed.remove(el.id)
        if (locks.contains(el.id) || !rules.canRefuse(el.id, info.dir)) {
            // Locked or no-refusal obstacle: never self-jump, evade without a fault
            startManeuver(el, Face.FRONT, info.crossing)
            events.add(SimEvent.Swerve(el.id))
            return
        }
        val gaitOk = gaitAllows(el, horse.gait)
        if (!gaitOk || horse.speed < selfMinSpeed(el, t)) {
            refuse(el, info, if (gaitOk) RefusalReason.SPEED else RefusalReason.GAIT, RefusalType.STOP)
        } else if (info.angle > t.jump.maxAngle) {
            refuse(el, info, RefusalReason.ANGLE, RefusalType.RUNOUT)
        } else {
            takeoff(el, info, true)
        }
    }

    private fun refuse(
        el: Element,
        info: ApproachInfo,
        reason: RefusalReason,
        type: RefusalType,
    ) {
        events.add(SimEvent.Refusal(el.id, info.dir, reason))
        endGallop(GallopEndReason.REFUSAL)
        settling = false
        locks.add(el.id)
        hopActive = false
        if (type == RefusalType.STOP) {
            val room = max(t.refusal.minStopRoom, info.distance - t.refusal.stopMargin)
            refusal =
                ActiveRefusal(
                    type,
                    el.id,
                    max(t.refusal.stopDecelMin, (horse.speed * horse.speed) / (2 * room)),
                )
        } else {
            refusal = ActiveRefusal(type, el.id, 0.0)
            startManeuver(el, Face.FRONT, info.crossing)
        }
    }

    private fun startManeuver(
        el: Element,
        face: Face,
        offset: Double,
    ) {
        val fx = sin(horse.heading)
        val fz = cos(horse.heading)
        // jump axis n = (sin rot, cos rot), cross axis t = (-cos rot, sin rot)
        val nx = sin(el.rot)
        val nz = cos(el.rot)
        val tx = -nz
        val tz = nx
        val fa = fx * nx + fz * nz
        val fc = fx * tx + fz * tz
        val out =
            if (face == Face.FRONT) {
                val side = if (abs(fc) > t.refusal.driftSide) sideOf(fc) else sideOf(offset)
                headingOf(tx * side, tz * side)
            } else {
                val side = if (abs(fa) > t.refusal.driftSide) sideOf(fa) else sideOf(offset)
                headingOf(nx * side, nz * side)
            }
        maneuver = Maneuver(el, outHeading = out, origHeading = horse.heading)
    }

    private fun steerManeuver(
        m: Maneuver,
        dt: Double,
    ) {
        val target = if (m.out) m.outHeading else m.origHeading
        val diff = wrapAngle(target - horse.heading)
        val maxStep = t.refusal.maneuverTurnRate * dt
        val turn = clamp(diff, -maxStep, maxStep)
        horse.heading = wrapAngle(horse.heading + turn)
        horse.turnRate = -turn / dt
        m.aligned = abs(diff - turn) < ALIGN_EPS
    }

    /** One axis of the ray/box test; narrows [rayMin]/[rayMax]. False when the ray misses the box. */
    private fun slab(
        p: Double,
        d: Double,
        h: Double,
    ): Boolean {
        if (abs(d) < RAY_PARALLEL_EPS) return !(p < -h || p > h)
        var t1 = (-h - p) / d
        var t2 = (h - p) / d
        if (t1 > t2) {
            val swap = t1
            t1 = t2
            t2 = swap
        }
        rayMin = max(rayMin, t1)
        rayMax = min(rayMax, t2)
        return !(rayMin > rayMax)
    }

    /** Does the ray (start p, direction d, in element coordinates) hit the box around 0? */
    private fun rayHitsBox(
        a: Double,
        c: Double,
        da: Double,
        dc: Double,
        halfA: Double,
        halfC: Double,
    ): Boolean {
        rayMin = 0.0
        rayMax = Double.POSITIVE_INFINITY
        return slab(a, da, halfA) && slab(c, dc, halfC)
    }

    private fun maneuverClear(m: Maneuver): Boolean {
        val el = m.el
        val i = elements.indexOf(el)
        val along = localAlong(el, horse.x, horse.z)
        val across = localAcross(el, horse.x, horse.z)
        val fx = sin(m.origHeading)
        val fz = cos(m.origHeading)
        val nx = sin(el.rot)
        val nz = cos(el.rot)
        val margin = t.refusal.clearMargin
        return !rayHitsBox(
            along,
            across,
            fx * nx + fz * nz,
            fx * -nz + fz * nx,
            extAlong[i] + margin,
            extAcross[i] + margin,
        )
    }

    private fun updateManeuver(
        m: Maneuver,
        dt: Double,
    ) {
        m.t += dt
        if (m.out) {
            if (maneuverClear(m)) m.out = false
        } else if (m.aligned) {
            endManeuver()
            return
        }
        if (m.t > t.refusal.maneuverTimeout) endManeuver()
    }

    private fun endManeuver() {
        maneuver = null
        horse.turnRate = 0.0
    }

    private fun updateRefusal(
        r: ActiveRefusal,
        dt: Double,
    ) {
        r.t += dt
        if (r.type == RefusalType.STOP) {
            if (r.t >= t.refusal.stopDuration) {
                horse.speed = 0.0
                horse.gait = Gait.HALT
                refusal = null
            }
        } else if (maneuver == null && r.t >= t.refusal.runoutDuration) {
            refusal = null
        }
    }

    // ---- Collision with obstacles and fence ----------------------------------------

    private fun constrainObstacles(
        prevX: Double,
        prevZ: Double,
    ) {
        val active = jump
        for (i in elements.indices) {
            if (active == null || active.el !== elements[i]) constrainAgainst(i, prevX, prevZ)
        }
    }

    /** The side of the blocked area the horse ran into, from where it came and where it is now. */
    private fun contactFace(
        i: Int,
        qAlong: Double,
        qAcross: Double,
        pAlong: Double,
        pAcross: Double,
    ): Face =
        when {
            abs(qAlong) >= extAlong[i] - CONTACT_EPS -> Face.FRONT
            abs(qAcross) >= extAcross[i] - CONTACT_EPS -> Face.SIDE
            extAlong[i] - abs(pAlong) <= extAcross[i] - abs(pAcross) -> Face.FRONT
            else -> Face.SIDE
        }

    /** Pushes the horse out of the blocked area of element [i] and makes it evade (rule 22). */
    private fun constrainAgainst(
        i: Int,
        prevX: Double,
        prevZ: Double,
    ) {
        val el = elements[i]
        val ea = extAlong[i]
        val ec = extAcross[i]
        val pAlong = localAlong(el, horse.x, horse.z)
        val pAcross = localAcross(el, horse.x, horse.z)
        if (abs(pAlong) >= ea || abs(pAcross) >= ec) return
        val qAlong = localAlong(el, prevX, prevZ)
        val qAcross = localAcross(el, prevX, prevZ)
        val face = contactFace(i, qAlong, qAcross, pAlong, pAcross)
        // the point is pushed out through the face it came in through (else the nearer one)
        val fromAlong = if (abs(qAlong) >= ea - CONTACT_EPS) qAlong else pAlong
        val fromAcross = if (abs(qAcross) >= ec - CONTACT_EPS) qAcross else pAcross
        val a = if (face == Face.FRONT) sideOf(fromAlong) * ea else pAlong
        val c = if (face == Face.SIDE) sideOf(fromAcross) * ec else pAcross
        // back to world coordinates: x = el.x + a * n.x + c * t.x, with n = (sin, cos), t = (-cos, sin)
        horse.x = el.x + a * sin(el.rot) + c * -cos(el.rot)
        horse.z = el.z + a * cos(el.rot) + c * sin(el.rot)
        // Horse hits a stand/obstacle without jumping: evade sideways (rule 22). Rule 22 has no
        // gait condition, so even a walking horse swerves instead of treading on the spot.
        if (isIdle() && horse.speed >= t.speeds.haltBelow) {
            startManeuver(el, face, if (face == Face.FRONT) pAcross else pAlong)
            events.add(SimEvent.Swerve(el.id))
        }
    }

    private fun isIdle() = jump == null && maneuver == null && refusal == null

    private fun rearInside(
        i: Int,
        rx: Double,
        rz: Double,
    ): Boolean {
        // the tail reaches further back than the front margin: use the rear clearance along the poles
        val el = elements[i]
        return abs(localAlong(el, rx, rz)) < rearAlong[i] && abs(localAcross(el, rx, rz)) < extAcross[i]
    }

    /**
     * The hindquarters must not pass through an obstacle while the horse reins back: a step that
     * would put the rear point into a blocked area is undone (position and heading, so turning
     * into the obstacle is held back too). It also holds when the rear point already is inside, e.g.
     * right after a landing: the horse cannot back through the obstacle. Turning on the spot
     * and riding forward are not touched (the rear may swing past an obstacle there).
     */
    private fun holdRearBack(
        prevX: Double,
        prevZ: Double,
        prevHeading: Double,
    ) {
        if (jump != null || horse.speed >= 0) return
        val rx = horse.x - sin(horse.heading) * t.horse.rearLength
        val rz = horse.z - cos(horse.heading) * t.horse.rearLength
        for (i in elements.indices) {
            if (!rearInside(i, rx, rz)) continue
            horse.x = prevX
            horse.z = prevZ
            horse.heading = prevHeading
            horse.turnRate = 0.0
            return
        }
    }

    private fun stopBacking() {
        horse.speed = 0.0
        horse.gait = Gait.HALT
        control.speed = 0.0
        control.backBlocked = true
    }

    private fun handleFence(dt: Double) {
        val speedBefore = horse.speed
        val res = applyFence(horse, t, allowStop = jump == null, dt = dt)
        val stopped = fenceStopNormal
        if (res != null && res.frontal) {
            val n = res.normal
            val repeat = stopped != null && stopped.x == n.x && stopped.z == n.z
            horse.speed = 0.0
            horse.gait = Gait.HALT
            horse.turnRate = 0.0
            settling = false
            maneuver = null
            hopActive = false
            if (refusal?.type == RefusalType.RUNOUT) refusal = null
            if (!repeat && speedBefore >= t.speeds.haltBelow) {
                events.add(SimEvent.FenceStop)
                endGallop(GallopEndReason.FENCE)
            } else if (horse.gallop) {
                endGallop(GallopEndReason.FENCE)
            }
            fenceStopNormal = n
        } else if (stopped != null) {
            val fx = sin(horse.heading)
            val fz = cos(horse.heading)
            val gap = if (stopped.x != 0.0) arenaMaxX(t) - stopped.x * horse.x else arenaMaxZ(t) - stopped.z * horse.z
            if (gap > t.fence.releaseGap || fx * stopped.x + fz * stopped.z < cos(t.fence.frontalAngle)) {
                fenceStopNormal = null
            }
        }
    }

    // ---- Step ---------------------------------------------------------------------

    private fun releaseLocks() {
        if (locks.isEmpty()) return
        val iterator = locks.iterator()
        while (iterator.hasNext()) {
            val el = byId[iterator.next()]
            // same measure as the approach: distance from the leading edge along the jump axis
            val along = if (el != null) abs(localAlong(el, horse.x, horse.z)) - el.spread / 2 else 0.0
            if (el == null || along > t.approachDistance) iterator.remove()
        }
    }

    private fun syncView() {
        val hopCfg = t.jump.hop
        if (hopActive) {
            val p = clamp(hopT / hopCfg.duration, 0.0, 1.0)
            hopView.progress = p
            horse.hop = hopView
            if (jump == null) horse.y = hopCfg.height * sin(PI * p)
        } else {
            horse.hop = null
            if (jump == null) horse.y = 0.0
        }
        val r = refusal
        if (r != null) {
            val dur = if (r.type == RefusalType.STOP) t.refusal.stopDuration else t.refusal.runoutDuration
            refusalView.type = r.type
            refusalView.progress = clamp(r.t / dur, 0.0, 1.0)
            refusalView.elementId = r.elementId
            horse.refusal = refusalView
        } else {
            horse.refusal = null
        }
    }

    /** A Space press that was kept during the landing is replayed while the buffer lasts. */
    private fun replayBufferedPress(dt: Double) {
        if (spaceBuffer <= 0) return
        spaceBuffer = max(0.0, spaceBuffer - dt)
        if (spaceBuffer > 0 && jump == null) pressJump(buffered = true)
    }

    /** Speed and steering on the ground: refusal stop, rein-back, normal control, evasion. */
    private fun controlOnGround(dt: Double) {
        val r = refusal
        if (r != null && r.type == RefusalType.STOP) {
            horse.speed = max(0.0, horse.speed - r.decel * dt)
            horse.turnRate = 0.0
            return
        }
        control.speed = horse.speed
        control.gallop = horse.gallop
        control.settling = settling
        // rein-back only from a calm halt: never during a refusal or an evasion
        val backThrottle = if (r != null || maneuver != null) 0.0 else inThrottle
        if (!updateReinBack(control, backThrottle, dt, t)) {
            updateSpeed(control, if (r != null) 0.0 else inThrottle, dt, t)
        }
        horse.speed = control.speed
        settling = control.settling
        val m = maneuver
        if (m != null) steerManeuver(m, dt) else updateSteering(horse, inSteer, dt, t)
    }

    /** Moves the horse for one substep (jump flight or ground control); returns the intended back distance. */
    private fun moveHorse(dt: Double): Double {
        val active = jump
        if (active != null) {
            advanceJump(active, dt)
            return 0.0
        }
        controlOnGround(dt)
        horse.gait = gaitForSpeed(horse.speed, horse.gallop, t.speeds)
        // distance the rein-back intends to cover in this step (0 when not backing)
        val backDistance = if (horse.speed < 0) -horse.speed * dt else 0.0
        advance(horse, horse.speed, dt)
        return backDistance
    }

    /** Fence or obstacle held the hindquarters back: the horse stops stepping back. */
    private fun stopBackingIfBlocked(
        prevX: Double,
        prevZ: Double,
        backDistance: Double,
    ) {
        if (backDistance <= 0 || horse.speed >= 0) return
        val moved = (prevX - horse.x) * sin(horse.heading) + (prevZ - horse.z) * cos(horse.heading)
        if (moved < backDistance * t.reinBack.blockedShare) stopBacking()
    }

    private fun updateTimedStates(dt: Double) {
        maneuver?.let { updateManeuver(it, dt) }
        refusal?.let { updateRefusal(it, dt) }
        if (hopActive) {
            hopT += dt
            if (hopT >= t.jump.hop.duration) hopActive = false
        }
    }

    private fun substep(dt: Double) {
        val prevX = horse.x
        val prevZ = horse.z
        val prevHeading = horse.heading
        replayBufferedPress(dt)
        val backDistance = moveHorse(dt)
        constrainObstacles(prevX, prevZ)
        holdRearBack(prevX, prevZ, prevHeading)
        handleFence(dt)
        stopBackingIfBlocked(prevX, prevZ, backDistance)
        updateTimedStates(dt)
        if (isIdle() && horse.speed >= 0) checkLastPoints()
        releaseLocks()
        syncView()
    }

    /**
     * One simulation step of [dt] seconds (clamped to `sim.maxDt`, split into substeps).
     * @return the events of the step, in order; a new list (empty when nothing happened)
     */
    fun step(
        dt: Double,
        input: SimInput,
    ): List<SimEvent> = step(dt, input.steer, input.throttle, input.gallop, input.jump)

    /** [step] without input: the horse keeps its course and pace. */
    fun step(dt: Double): List<SimEvent> = step(dt, 0.0, 0.0, gallop = false, jump = false)

    /** [step] with the input as plain values (no object to allocate per frame). */
    fun step(
        dt: Double,
        steer: Double,
        throttle: Double,
        gallop: Boolean,
        jump: Boolean,
    ): List<SimEvent> {
        events.clear()
        inSteer = if (steer.isFinite()) clamp(steer, -1.0, 1.0) else 0.0
        inThrottle = if (throttle.isFinite()) clamp(throttle, -1.0, 1.0) else 0.0
        inGallop = gallop
        val total = if (dt.isFinite()) clamp(dt, 0.0, t.sim.maxDt) else 0.0
        if (total > 0) {
            updateGallop()
            if (jump) pressJump()
            val n = max(1, ceil(total / t.sim.substep - 1e-9).toInt())
            for (i in 0 until n) substep(total / n)
        }
        updateApproach()
        return if (events.isEmpty()) emptyList() else events.toList()
    }

    /**
     * Takeoff zone of an element for a given speed (default: the horse's own), or null for an
     * unknown element. [dir] is accepted for symmetry with the web API and does not change the zone.
     */
    @Suppress("UnusedParameter") // `dir` is kept for symmetry with the web API (the zone does not depend on it)
    fun zoneFor(
        elementId: String,
        dir: Int = 1,
        speed: Double = horse.speed,
    ): Zone? {
        val el = byId[elementId] ?: return null
        return zoneForElement(el, speed, t)
    }

    /**
     * Allocation-free [zoneFor]: fills [out] with the takeoff zone of [elementId] at [speed] and
     * returns true, or returns false (and leaves [out]) for an unknown element.
     */
    fun zoneInto(
        elementId: String,
        speed: Double,
        out: Zone,
    ): Boolean {
        val el = byId[elementId] ?: return false
        zoneInto(el, speed, t, out)
        return true
    }
}
