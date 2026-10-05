package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.domain.testing.DeltaTracker
import app.zoeshorsefarm.domain.testing.FRAME
import app.zoeshorsefarm.domain.testing.Sequences
import app.zoeshorsefarm.domain.testing.runScript
import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Rule 24: gait changes, canter lead change, jumps, refusals and the halt run without visible
// popping. The tests drive the animation through scripted rides at 60 fps and measure what changes
// from one frame to the next.

// Largest per-frame change of the motion state (pure model, 60 fps). The steady canter at full
// speed reaches about 0.10 m for a hoof, so these leave a margin of 20-30 % and nothing more.
private val LIMITS =
    mapOf(
        "dz" to 0.13, // m, fore/aft hoof position relative to the body
        "y" to 0.07, // m, hoof height
        "flex" to 0.42, // rad, carpus/hock flexion
        "past" to 0.35, // rad, pastern fold
        "sink" to 0.03, // m
        "bob" to 0.012, // m
        "pitch" to 0.03, // rad
        "neck" to 0.045, // rad
        "roll" to 0.02, // rad
        "weight" to 0.08, // gait weights (cross-fade of about 0.5 s)
        "jumpWeight" to 0.25,
        "hopWeight" to 0.2,
        "stopWeight" to 0.2,
        "runoutWeight" to 0.12,
        "bend" to 0.03,
        "lean" to 0.03,
        "leadBlend" to 0.1,
    )

// a planted hoof moves less than this relative to the ground per frame (m)
private const val SLIDE = 0.001

private fun fixed2(x: Double): String {
    val cents = round(abs(x) * 100).toLong()
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

private class MotionTrack(
    val tr: DeltaTracker,
    val slideMax: Double,
    val slideWhere: String,
)

private fun legValue(
    leg: Leg,
    channel: String,
): Double =
    when (channel) {
        "dz" -> leg.dz
        "y" -> leg.y
        "flex" -> leg.flex
        "past" -> leg.past
        else -> leg.sink
    }

private fun weightsOf(
    m: Motion,
    name: String,
): Double =
    when (name) {
        "halt" -> m.weights.halt
        "walk" -> m.weights.walk
        "trot" -> m.weights.trot
        "canter" -> m.weights.canter
        else -> m.weights.back
    }

private fun trackMotion(name: String): MotionTrack {
    val m = createMotion(createRng(5))
    val tr = DeltaTracker()
    var slideMax = 0.0
    var slideWhere = ""
    val prevWorld = DoubleArray(4)
    val prevPlanted = BooleanArray(4)
    val seen = BooleanArray(4)
    var bodyZ = 0.0
    runScript(
        Sequences.all.getValue(name),
        { dt, state ->
            stepMotion(m, dt, state)
            bodyZ += state.speed * dt
        },
        { _, step, t ->
            val tag = "$name#$step"
            m.legs.forEachIndexed { k, leg ->
                for (c in listOf("dz", "y", "flex", "past", "sink")) tr.sample("$c$k", legValue(leg, c), t, tag)
                val world = bodyZ + leg.dz
                val planted = leg.y == 0.0 && !leg.squaring
                if (seen[k] && planted && prevPlanted[k]) {
                    val d = abs(world - prevWorld[k])
                    if (d > slideMax) {
                        slideMax = d
                        slideWhere = "leg $k $tag@${fixed2(t)}s"
                    }
                }
                prevWorld[k] = world
                prevPlanted[k] = planted
                seen[k] = true
            }
            tr.sample("body.bob", m.body.bob, t, tag)
            tr.sample("body.pitch", m.body.pitch, t, tag)
            tr.sample("body.neck", m.body.neck, t, tag)
            tr.sample("body.roll", m.body.roll, t, tag)
            for (k in listOf("halt", "walk", "trot", "canter", "back")) {
                tr.sample("weight.$k", weightsOf(m, k), t, tag)
            }
            tr.sample("jumpWeight", m.jumpWeight, t, tag)
            tr.sample("hopWeight", m.hopWeight, t, tag)
            tr.sample("stopWeight", m.stopWeight, t, tag)
            tr.sample("runoutWeight", m.runoutWeight, t, tag)
            tr.sample("bend", m.bend, t, tag)
            tr.sample("lean", m.lean, t, tag)
            tr.sample("leadBlend", m.leadBlend, t, tag)
        },
    )
    return MotionTrack(tr, slideMax, slideWhere)
}

private fun limitFor(channel: String): Double? {
    val base = channel.replace(Regex("[0-3]$"), "")
    return when {
        base.startsWith("body.") -> LIMITS[base.substring(5)]
        base.startsWith("weight.") -> LIMITS["weight"]
        else -> LIMITS[base]
    }
}

class MotionContinuityTest {
    @Test
    fun `no popping of hooves and body and weights in every sequence`() {
        for (name in Sequences.all.keys) {
            val track = trackMotion(name)
            for ((channel, value) in track.tr.max) {
                val limit = assertNotNull(limitFor(channel), "unknown channel $channel")
                assertTrue(
                    value <= limit,
                    "$name: $channel changes by $value per frame at ${track.tr.where[channel]} (limit $limit)",
                )
            }
        }
    }

    @Test
    fun `planted hooves do not slide in any sequence`() {
        for (name in Sequences.all.keys) {
            val track = trackMotion(name)
            assertTrue(track.slideMax <= SLIDE, "$name: slide ${track.slideMax} at ${track.slideWhere}")
        }
    }

    @Test
    fun `the checker catches a pop such as a gait weight that jumps`() {
        val m = createMotion()
        val tr = DeltaTracker()
        val state = Horse(gait = Gait.CANTER, speed = 6.0)
        repeat(60) { stepMotion(m, FRAME, state) }
        for (i in 0 until 20) {
            stepMotion(m, FRAME, state)
            // what the old exponential cross-fade at rate 5 did on the first frame of a change
            tr.sample("weight.trot", if (i == 10) 0.35 else m.weights.trot, i * FRAME)
        }
        assertTrue(tr.max.getValue("weight.trot") > LIMITS.getValue("weight"))
    }

    @Test
    fun `cross-fades the gaits over about half a second`() {
        val m = createMotion()
        val state = Horse(gait = Gait.WALK, speed = 1.5)
        repeat(120) { stepMotion(m, FRAME, state) }
        state.gait = Gait.TROT
        state.speed = 3.0
        var t95 = 0.0
        var t10 = 0.0
        for (i in 1..90) {
            stepMotion(m, FRAME, state)
            if (t10 == 0.0 && m.weights.trot > 0.1) t10 = i * FRAME
            if (t95 == 0.0 && m.weights.trot > 0.95) t95 = i * FRAME
        }
        assertTrue(t10 > 0.05, "starts gently") // starts gently
        assertTrue(t95 > 0.3)
        assertTrue(t95 < 0.6)
    }
}

class CanterLeadChangeTest {
    private class Canter(
        val m: Motion,
        val blend: List<Double>,
        val leads: List<Double>,
    )

    private fun canter(
        turnA: Double,
        turnB: Double,
        seconds: Int = 3,
    ): Canter {
        val m = createMotion()
        val state = Horse(gait = Gait.CANTER, speed = 6.0, turnRate = turnA)
        repeat(180) { stepMotion(m, FRAME, state) }
        state.turnRate = turnB
        val blend = ArrayList<Double>()
        val leads = ArrayList<Double>()
        repeat(seconds * 60) {
            stepMotion(m, FRAME, state)
            blend.add(m.leadBlend)
            leads.add(m.lead)
        }
        return Canter(m, blend, leads)
    }

    @Test
    fun `starts on the lead of the turn and keeps it`() {
        assertEquals(1.0, canter(-0.5, -0.5, 1).m.lead)
        assertEquals(-1.0, canter(0.5, 0.5, 1).m.lead)
    }

    @Test
    fun `changes the lead when the turn goes the other way for a while gradually and not as a flip`() {
        val c = canter(-0.5, 0.6)
        assertEquals(1.0, c.leads.first())
        assertEquals(-1.0, c.leads.last())
        val first = c.leads.indexOf(-1.0)
        assertTrue(first / 60.0 > 0.3, "not at once")
        assertEquals(-1.0, c.blend.last())
        for (i in 1 until c.blend.size) assertTrue(abs(c.blend[i] - c.blend[i - 1]) < 0.1)
        // the blend passes through the values in between
        assertTrue(c.blend.any { it > -0.5 && it < 0.5 })
    }

    @Test
    fun `keeps the lead through a short swerve the other way`() {
        val m = createMotion()
        val state = Horse(gait = Gait.CANTER, speed = 6.0, turnRate = -0.5)
        repeat(180) { stepMotion(m, FRAME, state) }
        state.turnRate = 0.8
        repeat(12) { stepMotion(m, FRAME, state) } // 0.2 s
        state.turnRate = -0.5
        repeat(180) { stepMotion(m, FRAME, state) }
        assertEquals(1.0, m.lead)
    }

    @Test
    fun `the legs follow the new lead`() {
        // left lead RH to LH+RF to LF, right lead LH to RH+LF to RF
        val m = createMotion()
        val state = Horse(gait = Gait.CANTER, speed = 6.0, turnRate = -0.5)
        repeat(180) { stepMotion(m, FRAME, state) }
        state.turnRate = 0.6
        repeat(4 * 60) { stepMotion(m, FRAME, state) }
        assertEquals(-1.0, m.lead)
        val seq = ArrayList<Int>()
        repeat(2 * 60) { seq.addAll(stepMotion(m, FRAME, state).toList()) }
        val lf = 0
        val rf = 1
        val lh = 2
        val rh = 3
        val i = seq.indexOf(lh)
        assertEquals(listOf(rh, lf).sorted(), listOf(seq[i + 1], seq[i + 2]).sorted())
        assertEquals(rf, seq[i + 3])
    }
}

// --- the whole animation: bones, hooves and body of the real horse --------------------------

private val LEG_BONES = Regex("^(L|R)(scapula|humerus|forearm|fcannon|fpastern|femur|tibia|hcannon|hpastern)$")
private val PASTERN = listOf("Lfpastern", "Rfpastern", "Lhpastern", "Rhpastern")
private val HOOF_REST =
    listOf(REST.front.hoof, REST.front.hoof, REST.hind.hoof, REST.hind.hoof).mapIndexed { i, h ->
        Vec3(if (i % 2 == 0) h[0] else -h[0], h[1], h[2])
    }
private val FETLOCK_REST =
    listOf(REST.front.fetlock, REST.front.fetlock, REST.hind.fetlock, REST.hind.fetlock).mapIndexed { i, h ->
        Vec3(if (i % 2 == 0) h[0] else -h[0], h[1], h[2])
    }

// Bones that carry no leg may turn this much per frame, leg joints more (the canter folds the
// carpus by 100 degrees in a tenth of a second). The old animation reached 1.0-1.2 rad on leg joints.
// A leg joint also may not change its angular velocity by more than LEG_ACCEL per frame: a joint
// that speeds up or stops dead within a frame is a visible pop even when its step is small (the
// first difference alone let one-frame V-kinks of 0.5-0.8 rad through).
private const val ROT_LIMIT_BODY = 0.15
private const val ROT_LIMIT_LEG = 0.35
private const val LEG_ACCEL = 0.3 // rad, change of the per-frame rotation from one frame to the next
private const val ROOT_STEP = 0.08 // m per frame (the take-off rotates the body about the hind feet)
private const val HOOF_STEP = 0.2 // m per frame, hoof relative to the body
private const val IK_SLIDE = 0.004 // m per frame, planted hoof relative to the ground (IK error)

// What the joint limiter may change (see the limiter probe). Measured: corrections of 0.10-0.15 rad in
// the steady canter and up to 0.22 rad at the start of the canter and in the lead change; a
// swinging hoof deviates by up to 5.5 cm from the unlimited pose (12.6 cm in the run-out).
private const val LIMITER_CORRECTION = 0.25 // rad, per joint and frame
private const val PLANTED_CORRECTION = 1e-4 // rad: a planted leg is not limited at all
private const val HOOF_DEVIATION = 0.065 // m, swinging hoof vs. the unlimited pose

// the run-out measures 12.6 cm; the brake of the refusal that starts in mid-canter 9 cm
private val HOOF_DEVIATION_BY_SEQUENCE = mapOf("runout" to 0.14, "refusalStop" to 0.1) // m

private class WorstValue(
    var v: Double = 0.0,
    var w: String = "",
)

/**
 * Largest per-frame rotation (first difference) and largest change of that rotation from one
 * frame to the next (second difference) of leg joints. Leg bones turn about X only, so the signed
 * rotation.x is the angle.
 */
private class LegTracker {
    private class Prev(
        var x: Double,
        var d: Double?,
    )

    private val prev = HashMap<Bone, Prev>()
    val step = WorstValue()
    val accel = WorstValue()

    fun sample(
        bone: Bone,
        tag: String,
    ) {
        val x = bone.rotation.x
        val p = prev[bone]
        if (p == null) {
            prev[bone] = Prev(x, null)
            return
        }
        val d = x - p.x
        if (abs(d) > step.v) {
            step.v = abs(d)
            step.w = "${bone.name} $tag"
        }
        val before = p.d
        if (before != null && abs(d - before) > accel.v) {
            accel.v = abs(d - before)
            accel.w = "${bone.name} $tag"
        }
        p.x = x
        p.d = d
    }
}

/** The IK of the left foreleg gets +1 rad on the forearm at the given call (one-frame pop). */
private object IkPop {
    var at = -1
    var calls = 0
}

private class LimiterRecord(
    val correction: Double,
    val maxSpeed: Double,
)

private class HoofSample(
    val pos: Vec3,
    val swinging: Boolean,
)

private class HorseTrack(
    val body: WorstValue,
    val legs: LegTracker,
    val hoofStep: Double,
    val hoofSlide: Double,
    val hoofWhere: String,
    val rootStep: Double,
    val rootWhere: String,
    val hoofFrames: List<List<HoofSample>>,
    val limiter: List<LimiterRecord>,
)

private fun bonesOf(root: Node): List<Bone> {
    val bones = ArrayList<Bone>()
    root.traverse { if (it is Bone) bones.add(it) }
    return bones
}

private fun quatAngle(
    a: Quat,
    b: Quat,
) = 2 * acos(min(1.0, abs(a.dot(b))))

/** Follows the bones, the hooves and the root of a horse frame by frame (see [trackHorse]). */
private class HorseTracker(
    private val name: String,
    private val horse: HorseView,
) {
    private val m = horse.motion
    private val bones = bonesOf(horse.group)
    private val byName = bones.associateBy { it.name }
    private val prevQ = HashMap<Bone, Quat>()
    val body = WorstValue()
    val legs = LegTracker()
    private val prevZ = DoubleArray(4)
    private val prevY = DoubleArray(4)
    private val prevWorld = DoubleArray(4)
    private val prevPlanted = BooleanArray(4)
    private var seen = false
    var hoofStep = 0.0
    var hoofSlide = 0.0
    var hoofWhere = ""
    private var rootPrev: Vec3? = null
    var rootStep = 0.0
    var rootWhere = ""
    private val v = Vec3()
    val hoofFrames = ArrayList<List<HoofSample>>() // per frame and leg: the hoof in the horse frame
    private var z = 0.0

    fun advance(
        dt: Double,
        state: Horse,
    ) {
        horse.update(dt, state)
        z += state.speed * dt
    }

    fun sample(
        state: Horse,
        step: Int,
        t: Double,
    ) {
        val tag = "$name#$step@${fixed2(t)}s"
        sampleBones(tag)
        sampleHooves(state, tag)
        sampleRoot(tag)
    }

    private fun sampleBones(tag: String) {
        for (b in bones) {
            if (LEG_BONES.matches(b.name)) {
                legs.sample(b, tag)
            } else {
                val q = prevQ[b]
                // the eyelids close within three frames (a blink)
                if (q != null && !b.name.endsWith("lid")) {
                    val angle = quatAngle(q, b.quaternion)
                    if (angle > body.v) {
                        body.v = angle
                        body.w = "${b.name} $tag"
                    }
                }
                prevQ[b] = b.quaternion.clone()
            }
        }
    }

    // planted hooves are only comparable on a straight line (a turn moves them in the horse frame)
    private fun isPoseFree(state: Horse): Boolean =
        m.jumpWeight < 0.01 &&
            m.hopWeight < 0.01 &&
            m.stopWeight < 0.01 &&
            m.runoutWeight < 0.01 &&
            abs(state.turnRate) < 0.3

    private fun sampleHooves(
        state: Horse,
        tag: String,
    ) {
        val poseFree = isPoseFree(state)
        val frameHooves = ArrayList<HoofSample>()
        hoofFrames.add(frameHooves)
        for (k in 0 until 4) {
            v.copy(HOOF_REST[k]).sub(FETLOCK_REST[k]).applyMatrix4(byName.getValue(PASTERN[k]).matrixWorld)
            frameHooves.add(HoofSample(v.clone(), m.legs[k].y > 0))
            val planted = m.legs[k].y == 0.0 && !m.legs[k].squaring && poseFree
            if (seen) compareHoof(k, planted, tag)
            prevZ[k] = v.z
            prevY[k] = v.y
            prevWorld[k] = v.z + z
            prevPlanted[k] = planted
        }
        seen = true
    }

    private fun compareHoof(
        k: Int,
        planted: Boolean,
        tag: String,
    ) {
        hoofStep = max(hoofStep, hypot(v.z - prevZ[k], v.y - prevY[k]))
        if (planted && prevPlanted[k]) {
            val s = abs(v.z + z - prevWorld[k])
            if (s > hoofSlide) {
                hoofSlide = s
                hoofWhere = "leg $k $tag"
            }
        }
    }

    private fun sampleRoot(tag: String) {
        val p = byName.getValue("root").position
        val before = rootPrev
        if (before != null) {
            val d = p.distanceTo(before)
            if (d > rootStep) {
                rootStep = d
                rootWhere = tag
            }
        }
        rootPrev = p.clone()
    }
}

/**
 * The joint limiter smooths every output of the leg IK, so a pop in the IK would be spread over a few
 * frames and the limits of the leg joints would stay green. The tests therefore also look at what the
 * limiter changes: it records |output - IK target| of every call, and with [unlimited] it returns the
 * IK target unchanged (the unlimited pose).
 */
private fun trackHorse(
    name: String,
    unlimited: Boolean = false,
): HorseTrack {
    val records = ArrayList<LimiterRecord>()
    val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, rng = createRng(3))
    horse.limiter =
        LimiterFn { state, target, dt, maxSpeed, maxAccel ->
            if (unlimited) {
                snapJoint(state, target)
            } else {
                val out = limitJoint(state, target, dt, maxSpeed, maxAccel)
                records.add(LimiterRecord(abs(out - target), maxSpeed))
                out
            }
        }
    horse.frontSolved = { out -> if (IkPop.calls++ == IkPop.at) out[2] += 1.0 }
    val tracker = HorseTracker(name, horse)
    runScript(
        Sequences.all.getValue(name),
        { dt, state -> tracker.advance(dt, state) },
        { state, step, t -> tracker.sample(state, step, t) },
    )
    horse.dispose()
    return HorseTrack(
        tracker.body,
        tracker.legs,
        tracker.hoofStep,
        tracker.hoofSlide,
        tracker.hoofWhere,
        tracker.rootStep,
        tracker.rootWhere,
        tracker.hoofFrames,
        records,
    )
}

private class LimiterEffect(
    val correction: Double,
    val planted: Double,
    val deviation: Double,
    val where: String,
    val calls: Int,
)

/**
 * What the joint limiter did in a sequence: its largest correction, the largest correction of a
 * planted leg (the limiter gives a planted leg the larger room, see STANCE_LIMIT_FACTOR) and how
 * far a swinging hoof is from where the unlimited IK pose puts it.
 */
private fun limiterEffect(name: String): LimiterEffect {
    val limited = trackHorse(name)
    val unlimited = trackHorse(name, unlimited = true)
    val stanceSpeed = limited.limiter.maxOf { it.maxSpeed }
    val swingSpeed = limited.limiter.minOf { it.maxSpeed }
    var correction = 0.0
    var planted = 0.0
    for (r in limited.limiter) {
        if (r.maxSpeed == stanceSpeed && stanceSpeed > swingSpeed) {
            planted = maxOf(planted, r.correction)
        } else {
            correction = maxOf(correction, r.correction)
        }
    }
    var deviation = 0.0
    var where = ""
    limited.hoofFrames.forEachIndexed { i, hooves ->
        hooves.forEachIndexed { k, h ->
            if (h.swinging) {
                val d = h.pos.distanceTo(unlimited.hoofFrames[i][k].pos)
                if (d > deviation) {
                    deviation = d
                    where = "leg $k frame $i"
                }
            }
        }
    }
    return LimiterEffect(correction, planted, deviation, where, limited.limiter.size)
}

class FullAnimationContinuityTest {
    @Test
    fun `bones and body and hooves move smoothly and planted hooves stay put in every sequence`() {
        for (name in Sequences.all.keys) {
            val t = trackHorse(name)
            assertTrue(t.body.v <= ROT_LIMIT_BODY, "$name: body bone ${t.body.w} ${t.body.v}")
            assertTrue(t.legs.step.v <= ROT_LIMIT_LEG, "$name: leg joint ${t.legs.step.w} ${t.legs.step.v}")
            assertTrue(t.legs.accel.v <= LEG_ACCEL, "$name: leg joint ${t.legs.accel.w} ${t.legs.accel.v}")
            assertTrue(t.rootStep <= ROOT_STEP, "$name: root at ${t.rootWhere} ${t.rootStep}")
            assertTrue(t.hoofStep <= HOOF_STEP, "$name: hoof step ${t.hoofStep}")
            assertTrue(t.hoofSlide <= IK_SLIDE, "$name: hoof ${t.hoofWhere} ${t.hoofSlide}")
        }
    }

    @Test
    fun `the joint limiter only smooths small kinks of the IK in every sequence`() {
        for (name in Sequences.all.keys) {
            val e = limiterEffect(name)
            assertTrue(e.calls > 0, "$name: limiter calls")
            assertTrue(e.correction <= LIMITER_CORRECTION, "$name: largest correction of a leg joint ${e.correction}")
            assertTrue(e.planted <= PLANTED_CORRECTION, "$name: correction of a planted leg ${e.planted}")
            val limit = HOOF_DEVIATION_BY_SEQUENCE[name] ?: HOOF_DEVIATION
            assertTrue(e.deviation <= limit, "$name: swinging hoof vs unlimited pose ${e.deviation} at ${e.where}")
        }
    }

    // The IK of the left foreleg gets a pop of 1 rad at one frame of the jump sequence (the front legs
    // are solved first, twice per frame). The unlimited second run of limiterEffect is past that call
    // and stays clean. The limiter spreads the pop, so the limits of the leg joints do not see it.
    @Test
    fun `the guards catch a pop of the IK in the swing of a hoof`() {
        IkPop.at = 2 * 205
        IkPop.calls = 0
        try {
            val e = limiterEffect("jump")
            assertTrue(e.correction > LIMITER_CORRECTION, "correction ${e.correction}")
            assertTrue(e.deviation > HOOF_DEVIATION, "deviation ${e.deviation}")
        } finally {
            IkPop.at = -1
        }
    }
}

// --- jumps from every gait, with the real height of the horse and the rider on top ---------------

// The same limits as in the gaits: before the soft reach of the IK, the landing (the hoof target
// comes back into reach and the straight leg bends in one frame) turned the forearm by 0.8-0.9
// rad, and the quick tuck-in of the take-off by 0.5 rad in one frame and then not at all.
private const val JUMP_LEG_LIMIT = ROT_LIMIT_LEG
private const val JUMP_RIDER_LIMIT = 0.15 // the rider's joints move calmly through the whole jump
private val RIDER_BONES =
    Regex("^(pelvis|spine|chest|neck|head|(L|R)(upperArm|forearm|hand|thigh|shin|foot))$")

private class JumpFrames(
    val frames: List<Horse>,
    val from: Int,
)

private fun jumpFrames(
    gait: Gait,
    speed: Double,
    height: Double,
    total: Double = 1.0,
): JumpFrames {
    val lead = 120
    val n = round(total / FRAME).toInt()
    val frames = ArrayList<Horse>()
    repeat(lead) { frames.add(Horse(gait = gait, speed = speed)) }
    for (i in 0 until n) {
        val s = i.toDouble() / n
        var phase = JumpPhase.TAKEOFF
        var progress = s / 0.2
        if (s >= 0.75) {
            phase = JumpPhase.LANDING
            progress = (s - 0.75) / 0.25
        } else if (s >= 0.2) {
            phase = JumpPhase.FLIGHT
            progress = (s - 0.2) / 0.55
        }
        frames.add(
            Horse(
                gait = gait,
                speed = speed,
                y = height * sin(kotlin.math.PI * s),
                jump = JumpView(phase, min(1.0, progress)),
            ),
        )
    }
    repeat(60) { frames.add(Horse(gait = gait, speed = speed)) }
    return JumpFrames(frames, lead - 1)
}

/** Follows the bones of a horse with a rider through a jump: legs (steps) and rider joints (angles). */
private class JumpTracker(
    private val horse: HorseView,
    private val from: Int,
) {
    private val riderNodes = HashSet<Node>()
    private val bones = bonesOf(horse.group)
    private val prevQ = HashMap<Bone, Quat>()
    val rider = WorstValue()
    val legs = LegTracker()

    init {
        horse.rider!!.group.traverse { riderNodes.add(it) }
    }

    fun frame(
        i: Int,
        state: Horse,
    ) {
        horse.update(FRAME, state)
        if (i >= from) {
            for (b in bones) if (b !in riderNodes && LEG_BONES.matches(b.name)) legs.sample(b, "frame ${i - from}")
        }
        for (b in bones) {
            if (b in riderNodes || !LEG_BONES.matches(b.name)) sampleRider(b, i)
        }
    }

    private fun sampleRider(
        b: Bone,
        i: Int,
    ) {
        val q = prevQ[b]
        prevQ[b] = b.quaternion.clone()
        if (q == null || i < from) return
        if (b !in riderNodes || !RIDER_BONES.matches(b.name)) return
        val angle = quatAngle(q, b.quaternion)
        if (angle > rider.v) {
            rider.v = angle
            rider.w = "${b.name} frame ${i - from}"
        }
    }
}

class JumpContinuityTest {
    private class Case(
        val gait: Gait,
        val speed: Double,
        val height: Double,
    )

    private val cases =
        listOf(
            Case(Gait.WALK, 1.2, 1.2),
            Case(Gait.TROT, 3.5, 0.6),
            Case(Gait.TROT, 4.5, 1.2),
            Case(Gait.CANTER, 5.2, 0.6),
            Case(Gait.CANTER, 5.2, 1.2),
        )

    @Test
    fun `legs and rider stay smooth in jumps from every gait`() {
        for (c in cases) {
            val label = "${c.gait} at ${c.speed} m/s over ${c.height} m"
            val horse = createHorse(quality = GraphicsLevel.LOW, rng = createRng(3))
            val jump = jumpFrames(c.gait, c.speed, c.height)
            val tracker = JumpTracker(horse, jump.from)
            jump.frames.forEachIndexed { i, state -> tracker.frame(i, state) }
            horse.dispose()
            val legs = tracker.legs
            assertTrue(legs.step.v <= JUMP_LEG_LIMIT, "$label: leg joint ${legs.step.w} ${legs.step.v}")
            assertTrue(legs.accel.v <= LEG_ACCEL, "$label: leg joint ${legs.accel.w} ${legs.accel.v}")
            assertTrue(tracker.rider.v <= JUMP_RIDER_LIMIT, "$label: rider bone ${tracker.rider.w} ${tracker.rider.v}")
        }
    }
}
