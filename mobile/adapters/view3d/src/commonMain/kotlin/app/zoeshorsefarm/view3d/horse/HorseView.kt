package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Skeleton
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.EulerOrder
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.view3d.releaseNow
import app.zoeshorsefarm.view3d.rider.Rider
import app.zoeshorsefarm.view3d.rider.createRider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

// Procedural 3D horse (scene model). Pure computations live in Motion.kt (+ Legs.kt, Gaits.kt,
// Poses.kt), Ik.kt, Life.kt (hair springs, blink, breathing; + Spring.kt, Schedule.kt), Seat.kt and
// Coats.kt; this file turns them into bone rotations and coat uniforms.
//
//   val horse = createHorse(coat = Coat.BAY, marking = Marking.STAR, quality = GraphicsLevel.MEDIUM)
//   scene.add(horse.group)             // origin on the ground below the forelegs, faces +Z
//   horse.update(dt, sim.horse)        // gait, jump, hop and refusal animation
//   horse.footfalls                    // footfalls of the last update (for dust and sound), see below
//
// Footfalls: after update(), `horse.footfalls` lists what touched the ground in this frame (the list
// and its events are reused by the next update, so copy what you keep): kind STEP or LANDING, leg
// 0..3 (LF, RF, LH, RH), gait, strength 0..1, x, y, z with the contact point in the local frame of
// horse.group (y = the ground level there). LANDING events come in pairs per jump: forelegs
// (strength 1) and hindlegs (0.65). horse.footfallWorld(event, out) converts a point to world space
// with the CURRENT position and heading of horse.group, so call it after the group has been placed.
// `onFootfall(gait, leg)` is still called for STEP events (sound).
//
// Height: the integration puts sim.horse.y on group.position.y; the model does not apply y again
// but keeps stance hooves on the ground (ground = -y in model space).

/** Distance body centre -> simulation reference point (ground below the forelegs). */
private val ORIGIN_OFFSET_Z = REST.front.hoof[2]

private val LEG_PREFIX = listOf("L", "R", "L", "R")

// Dust strength of a footfall per gait (a landing after a jump is always stronger)
private const val STEP_STRENGTH_WALK = 0.12
private const val STEP_STRENGTH_TROT = 0.5
private const val STEP_STRENGTH_CANTER = 0.8
private const val STEP_STRENGTH_BACK = 0.1
private const val STEP_STRENGTH_OTHER = 0.3
private const val LANDING_REACH_FRONT = 0.12
private const val LANDING_REACH_HIND = 0.1

// the free hair on the right side of the neck cannot be pressed into the neck by more than this (rad)
private const val MANE_PRESS = 0.1

// ... and it cannot swing further along the neck than this (rad)
private const val MANE_SWING = 0.6

// Grazing (graze 0..1): neck (per neck bone) and head down to the grass, chewing now and then; the
// muzzle ends up about 0.15 m above the ground, 0.55 m in front of the forefeet
private val GRAZE_NECK = doubleArrayOf(1.0, 0.5, 0.15)
private const val GRAZE_HEAD = -0.85
private const val GRAZE_CHEW = 0.03
private const val GRAZE_CHEW_RATE = 1.7
private val GRAZE_NECK_SUM = GRAZE_NECK.sum()

// Soft zone of the leg reach (m), see softReach in Ik.kt. On the ground a small one: a straight
// leg bends by 0.3 rad for the first 3 cm that the hoof lifts, and the hoof path of a long stride
// ends at the full stretch, where a hard limit locks the leg and lets it snap on the next frame.
// In the air a bigger one: the ground target of a hoof is out of reach during a jump, and the leg
// must not lock straight and bend in a single frame when the target comes back. The zone widens
// when the body has risen by AIRBORNE_RAMP (m), so that there is no switch in one frame.
private const val GROUND_SOFT_REACH = 0.03
private const val AIRBORNE_SOFT_REACH = 0.16
private const val AIRBORNE_RAMP = 0.15
private const val AIRBORNE_RATE = 10.0 // 1/s, how fast the soft zone follows

// Limit of the leg joints (see JointLimit.kt): 0.33 rad per frame and 0.25 rad per frame and
// frame at 60 fps. It smooths the IK kinks of every canter stride (an error of up to about 0.15
// rad). A hoof on the ground gets twice the room: limiting it would make it slide over the ground.
private const val LEG_JOINT_SPEED = 20.0
private const val LEG_JOINT_ACCEL = 900.0
private const val STANCE_LIMIT_FACTOR = 2.0

private const val MAX_STEP = 0.1 // s, longest time step of the animation

/** Occasional ear flick (0 most of the time), k = ear index. */
private fun earFlick(
    t: Double,
    k: Int,
): Double {
    val s = sin(t * (0.7 + 0.13 * k) + k * 2.1)
    return if (s > 0.96) (s - 0.96) * 12 else 0.0
}

private fun rigPoint(
    a: DoubleArray,
    parent: DoubleArray,
) = RigPoint(a[1] - parent[1], a[2] - parent[2])

private fun stepStrength(gait: Gait): Double =
    when (gait) {
        Gait.WALK -> STEP_STRENGTH_WALK
        Gait.TROT -> STEP_STRENGTH_TROT
        Gait.CANTER -> STEP_STRENGTH_CANTER
        Gait.BACK -> STEP_STRENGTH_BACK
        Gait.HALT -> STEP_STRENGTH_OTHER
    }

/** The joint limiter as a primitive function type (a test hook; calling it does not box). */
internal fun interface LimiterFn {
    operator fun invoke(
        s: JointLimit,
        target: Double,
        dt: Double,
        maxSpeed: Double,
        maxAccel: Double,
    ): Double
}

/** What touched the ground: a [STEP] of one hoof or the [LANDING] of a jump. */
enum class FootfallKind { STEP, LANDING }

/** A footfall event (a reused object, see the header of this file). */
class Footfall {
    var kind = FootfallKind.STEP
    var leg = 0
    var gait = Gait.HALT
    var strength = 0.0
    var x = 0.0
    var y = 0.0
    var z = 0.0
}

/**
 * A horse in the scene: [group] is what the world adds to the scene, [update] animates it. Create it
 * with [createHorse].
 */
class HorseView internal constructor(
    startLevel: GraphicsLevel,
    startAppearance: Appearance,
    withRider: Boolean,
    private val withTack: Boolean,
    private val release: (GpuObject?) -> Unit,
    rng: () -> Double,
    private val castShadow: Boolean?,
) {
    private var level = startLevel
    private var appearance = startAppearance

    /** Root node: origin on the ground below the forelegs, faces +Z. */
    val group = Group()
    private val rig = Group()
    private val skel = createSkeletonBones()
    private val b: Map<String, Bone> = skel.bones
    private val bRoot: Bone = b.getValue("root")
    private val bSpineFront: Bone = b.getValue("spineFront")
    private val bSpineRear: Bone = b.getValue("spineRear")
    private val bBelly: Bone = b.getValue("belly")
    private val bNeck1: Bone = b.getValue("neck1")
    private val bNeck2: Bone = b.getValue("neck2")
    private val bNeck3: Bone = b.getValue("neck3")
    private val bHead: Bone = b.getValue("head")
    private val bLear: Bone = b.getValue("Lear")
    private val bRear: Bone = b.getValue("Rear")
    private val bForelock: Bone = b.getValue("forelock")
    private val skeleton: Skeleton
    private val uniforms = createCoatUniforms()
    private val body: SkinnedMesh
    private val tack: SkinnedMesh?
    private val bindMatrix: Mat4

    /** The rider on the saddle (null for a horse without rider). */
    val rider: Rider?

    /** Between the ears (the rider view looks from here). */
    val earAnchor = Group()
    private val reins: Reins?

    /** Called for every STEP footfall (for the sound). */
    var onFootfall: ((gait: Gait, leg: Int) -> Unit)? = null

    /** The footfalls of the last [update]. */
    val footfalls: List<Footfall> get() = footfallList
    private val footfallList = ArrayList<Footfall>()

    // Test hooks (internal, null in the app): what the joint limiter does and a pop of the IK
    internal var limiter: LimiterFn = LimiterFn(::limitJoint)
    internal var frontSolved: ((DoubleArray) -> Unit)? = null

    /** The motion state (read by the tests). */
    internal val motion = createMotion(rng)
    private val life = createLife(rng)

    private val frontRig: FrontRig
    private val hindRig: HindRig
    private val legBones: List<List<Bone>>
    private val legLimiters: List<List<JointLimit>>
    private val legX: DoubleArray
    private val legZ: DoubleArray
    private val bitLocal: List<Vec3>
    private val restLocal: List<Vec3>
    private val maneBones = (1..5).map { b.getValue("mane$it") }
    private val tailBones = (1..5).map { b.getValue("tail$it") }
    private val lidBones = listOf(b.getValue("Llid"), b.getValue("Rlid"))
    private val lidAxes = lidBones.map { skel.lidAxes.getValue(it.name) }
    private val maneRest = maneBones.map { skel.restQuaternion.getValue(it.name) }

    private val footfallPool = ArrayList<Footfall>() // the event objects, reused every frame
    private var airborne = 0.0 // 0..1, smoothed: how much the soft reach is used
    private val headGesture = HeadGesture()
    private val lifeInput = LifeInput()
    private val qTmp = Quat()
    private val eTmp = Euler()
    private val pose = DoubleArray(POSE_SIZE)
    private val tmpPose = DoubleArray(POSE_SIZE)
    private val mFront = Mat4()
    private val mRear = Mat4()
    private val inv = Mat4()
    private val v3 = Vec3()
    private val down = Vec3()
    private val rotOut = DoubleArray(5)
    private val riderCtx = RiderContext()
    private val qObj = Quat()
    private val qHead = Quat()
    private val qWant = Quat()
    private val eX = Euler()
    private val mInvRig = Mat4()
    private val tmpA = Vec3()
    private val tmpB = Vec3()
    private val objMatrix = Mat4()

    // values of the current frame that the parts of update() share
    private var fPitch = 0.0
    private var fLift = 0.0
    private var fRoll = 0.0
    private var fTurnBend = 0.0
    private var fNeck = 0.0
    private var fWeight = 0.0 // W: share of the poses (jump, hop, refusal)
    private var fPivotZ = 0.0
    private var fY = 0.0
    private var tailLift = 0.0

    // target of the leg that is solved
    private var legHz = 0.0
    private var legHy = 0.0
    private var legPast = 0.0
    private var legFlex = 0.0
    private var legAngle = 0.0

    init {
        group.name = "horse"
        rig.name = "horse-rig"
        rig.position.z = -ORIGIN_OFFSET_Z
        group.add(rig)
        rig.add(skel.root)
        group.updateMatrixWorld(true)
        skeleton = Skeleton(skel.list)

        applyAppearance(uniforms, appearance)
        body = SkinnedMesh(Geometry(), BasicMaterial())
        body.name = "horse-body"
        // bandages are part of the tack geometry, so a horse without tack has none
        tack = if (withTack) SkinnedMesh(Geometry(), BasicMaterial()).also { it.name = "horse-tack" } else null
        for (m in listOfNotNull(body, tack)) {
            m.frustumCulled = false
            rig.add(m)
        }

        // leg rigs in the local frames of spineFront / spineRear
        val f = REST.front
        val h = REST.hind
        val sf = REST.spineFront
        val sr = REST.spineRear
        frontRig =
            makeFrontRig(
                rigPoint(f.scapula, sf),
                rigPoint(f.shoulder, sf),
                rigPoint(f.elbow, sf),
                rigPoint(f.knee, sf),
                rigPoint(f.fetlock, sf),
                rigPoint(f.hoof, sf),
            )
        hindRig =
            makeHindRig(
                rigPoint(h.hip, sr),
                rigPoint(h.stifle, sr),
                rigPoint(h.hock, sr),
                rigPoint(h.fetlock, sr),
                rigPoint(h.hoof, sr),
            )
        legBones =
            (0..3).map { i ->
                val p = LEG_PREFIX[i]
                if (i < 2) {
                    listOf("scapula", "humerus", "forearm", "fcannon", "fpastern").map { b.getValue("$p$it") }
                } else {
                    listOf("femur", "tibia", "hcannon", "hpastern").map { b.getValue("$p$it") }
                }
            }
        legLimiters = legBones.map { bones -> bones.map { createJointLimiter() } }
        legX = doubleArrayOf(f.hoof[0], -f.hoof[0], h.hoof[0], -h.hoof[0])
        legZ = doubleArrayOf(f.hoof[2], f.hoof[2], h.hoof[2], h.hoof[2])

        // rider on the saddle
        rider =
            if (withRider) {
                createRider(level, release).also {
                    it.group.position.set(
                        SADDLE_SEAT.x - REST.root[0],
                        SADDLE_SEAT.y - REST.root[1],
                        SADDLE_SEAT.z - REST.root[2],
                    )
                    bRoot.add(it.group)
                }
            } else {
                null
            }

        // ear anchor (rider view), looking forward
        earAnchor.name = "horse-ear-anchor"
        earAnchor.position.copy(earAnchor().sub(Vec3(REST.head[0], REST.head[1], REST.head[2])))
        bHead.add(earAnchor)

        // reins (dynamic, bit -> hands or neck)
        reins = if (withTack) createReins() else null
        reins?.let { rig.add(it.mesh) }
        bitLocal = listOf(1.0, -1.0).map { bitRingPoint(it).sub(Vec3(REST.head[0], REST.head[1], REST.head[2])) }
        restLocal =
            listOf(1.0, -1.0).map { reinRestPoint(it).sub(Vec3(sf[0], sf[1], sf[2])) }

        // bind matrices in the rest pose (group not transformed yet)
        group.updateMatrixWorld(true)
        bindMatrix = body.matrixWorld.clone()
        applyQuality()
    }

    private fun applyQuality() {
        release(body.geometry)
        release(body.material)
        body.geometry = buildBodyGeometry(skel.index, level)
        body.material = createCoatMaterial(level, uniforms)
        // Always bind with the rest-pose matrix: the group may have been moved since (quality change)
        body.bind(skeleton, bindMatrix)
        if (tack != null) {
            release(tack.geometry)
            release(tack.material)
            tack.geometry = buildTackGeometry(skel.index, level)
            tack.material = createVertexColorMaterial(level, 0.55)
            reins?.setMaterial(tack.material)
            tack.bind(skeleton, bindMatrix)
        }
        val shadows = castShadow ?: (level != GraphicsLevel.LOW)
        for (m in listOfNotNull(body, tack, reins?.mesh)) m.castShadow = shadows
        rider?.setQuality(level)
    }

    /** Contact point of a footfall event in world space (uses the current group transform). */
    fun footfallWorld(
        ev: Footfall,
        out: Vec3 = Vec3(),
    ): Vec3 {
        objMatrix.compose(group.position, group.quaternion, group.scale)
        return out.set(ev.x, ev.y, ev.z).applyMatrix4(objMatrix)
    }

    /** Changes coat and/or marking (null keeps the current one); only uniforms change. */
    fun setAppearance(
        coat: Coat? = null,
        marking: Marking? = null,
    ) {
        appearance = applyAppearance(uniforms, Appearance(coat ?: appearance.coat, marking ?: appearance.marking))
    }

    fun setAppearance(a: Appearance) = setAppearance(a.coat, a.marking)

    /** Rebuilds body and tack for another detail level (the replaced buffers go through `release`). */
    fun setQuality(l: GraphicsLevel) {
        if (l == level) return
        level = l
        applyQuality()
    }

    fun dispose() {
        // through `release`, so that objects of a lost context are not freed with GL calls
        release(body.geometry)
        release(tack?.geometry)
        release(body.material)
        release(tack?.material)
        reins?.dispose(release)
        rider?.dispose()
        release(skeleton.boneTexture)
        group.removeFromParent()
    }

    private fun limitLeg(
        leg: Int,
        k: Int,
        target: Double,
        dt: Double,
        planted: Boolean,
    ): Double {
        val f = if (planted) STANCE_LIMIT_FACTOR else 1.0
        return limiter(legLimiters[leg][k], target, dt, LEG_JOINT_SPEED * f, LEG_JOINT_ACCEL * f)
    }

    private fun addPose(
        src: DoubleArray,
        w: Double,
    ): Double {
        if (w <= 1e-4) return 0.0
        for (i in 0 until POSE_SIZE) pose[i] += src[i] * w
        return w
    }

    /**
     * Advances the animation by [dtIn] for the simulation horse [state]; [graze] (0..1) is how much the
     * horse grazes (the neck and head go down to the grass). Returns the footfalls of this frame.
     */
    fun update(
        dtIn: Double,
        state: Horse,
        graze: Double = 0.0,
    ): List<Footfall> {
        val dt = clamp(if (dtIn.isNaN()) 0.0 else dtIn, 0.0, MAX_STEP)
        val falls = motion.step(dt, state, graze)
        fY = state.y
        blendPoses()
        moveBody()
        moveNeckHeadEarsTail(state, graze)
        lifeSigns(dt, state)
        moveLegs(dt)
        updateRider(dt, state)
        updateWorldAndReins(dt)
        collectFootfalls(falls, state.gait)
        return footfallList
    }

    /** Blend of the poses of a jump, a hop and a refusal into [pose]; sets the share [fWeight]. */
    private fun blendPoses() {
        val m = motion
        pose.fill(0.0)
        var w = 0.0
        if (m.jumpWeight > 1e-3) w += addPose(samplePoses(JUMP_KEYS, m.jumpJ, tmpPose), m.jumpWeight)
        if (m.hopWeight > 1e-3) {
            samplePoses(JUMP_KEYS, m.hopJ, tmpPose)
            val hw = m.hopWeight * (1 - m.jumpWeight)
            // hop = small jump: 40 % pose amplitude
            for (i in 0 until POSE_SIZE) if (i != POSE_PIVOT) tmpPose[i] *= 0.4
            w += addPose(tmpPose, hw)
        }
        if (m.stopWeight > 1e-3) w += addPose(STOP_POSE, m.stopWeight * (1 - min(1.0, w)))
        if (w > 1) {
            for (i in 0 until POSE_SIZE) pose[i] /= w
            w = 1.0
        }
        fWeight = w
        // pivot of the body pitch: weighted like the rest of the pose, so it never jumps with W
        fPivotZ = pose[POSE_PIVOT]
    }

    private fun moveBody() {
        val m = motion
        val g = 1 - fWeight // gait share
        val breath = life.breath // breathing cycle of the previous step (one frame behind: fine)
        fPitch = m.body.pitch * g + pose[POSE_PITCH]
        fLift = m.body.bob * g + pose[POSE_DY] + m.weights.halt * 0.004 * breath
        fRoll = m.lean + m.body.roll * g + m.runoutWeight * m.runoutDir * -0.1
        val root = bRoot
        val ry = REST.root[1] + fLift
        val dz0 = -fPivotZ
        // rotation about (y = 0, z = pivotZ)
        root.position.set(
            -sin(fRoll) * REST.root[1],
            ry * cos(fPitch) - dz0 * sin(fPitch),
            fPivotZ + ry * sin(fPitch) + dz0 * cos(fPitch),
        )
        root.rotation.set(fPitch, 0.0, fRoll, EulerOrder.XZY)
        val bend = pose[POSE_BEND]
        fTurnBend = m.bend + m.runoutWeight * m.runoutDir * 0.4
        bSpineFront.rotation.set(bend * 0.55, fTurnBend * 0.3, 0.0)
        bSpineRear.rotation.set(-bend * 0.45, -fTurnBend * 0.22, 0.0)
        bBelly.scale.set(1 + 0.014 * breath, 1 + 0.01 * breath, 1.0)
    }

    private fun moveNeckHeadEarsTail(
        state: Horse,
        grazeIn: Double,
    ) {
        val m = motion
        val t = m.time
        val g = 1 - fWeight
        gestureHead(m.gesture?.state, headGesture)
        val graze = clamp(grazeIn, 0.0, 1.0)
        val chew = graze * GRAZE_CHEW * sin(t * 2 * PI * GRAZE_CHEW_RATE) * (0.5 + 0.5 * sin(t * 0.35))
        val lazy = m.weights.halt * (0.05 * sin(t * 0.37) + 0.03 * sin(t * 0.91 + 1))
        val neckPose =
            (neckCarriage(m.weights) + m.body.neck + lazy) * g +
                pose[POSE_NECK] +
                -fPitch * 0.35 * g +
                m.runoutWeight * -0.1 +
                headGesture.neck
        fNeck = neckPose + graze * GRAZE_NECK_SUM
        val turn = fTurnBend
        bNeck1.rotation.set(
            neckPose * 0.3 + graze * GRAZE_NECK[0],
            turn * 0.33 + headGesture.yaw * 0.25,
            0.0,
        )
        bNeck2.rotation.set(
            neckPose * 0.35 + graze * GRAZE_NECK[1],
            turn * 0.33 + headGesture.yaw * 0.3,
            0.0,
        )
        bNeck3.rotation.set(
            neckPose * 0.35 + graze * GRAZE_NECK[2],
            turn * 0.3 + headGesture.yaw * 0.35,
            0.0,
        )
        bHead.rotation.set(
            pose[POSE_HEAD] + m.body.neck * 0.3 * g - lazy * 0.5 + headGesture.pitch + graze * GRAZE_HEAD + chew,
            turn * 0.2 + m.weights.halt * 0.08 * sin(t * 0.23) + headGesture.yaw * 0.6,
            0.0,
        )
        val earBack = m.stopWeight * 0.6
        bLear.rotation.set(
            -0.1 - earBack - earFlick(t, 1) * 0.5 * m.weights.halt,
            0.15 * sin(t * 0.3),
            0.0,
        )
        bRear.rotation.set(
            -0.1 - earBack - earFlick(t, 2) * 0.5 * m.weights.halt,
            -0.15 * sin(t * 0.27 + 1),
            0.0,
        )
        // jump and hop count as alerts for the blink
        lifeInput.alert = if (state.jump != null || state.hop != null) 1.0 else m.weights.canter * 0.6
        tailLift = m.weights.trot * 0.15 + m.weights.canter * 0.35 + pose[POSE_TAIL]
    }

    /** Life signs: spring-driven tail, mane and forelock, blink, nostrils. */
    private fun lifeSigns(
        dt: Double,
        state: Horse,
    ) {
        val m = motion
        lifeInput.speed = state.speed
        lifeInput.turnRate = state.turnRate
        lifeInput.bodyY = fY + fLift
        lifeInput.neckAngle = fNeck
        lifeInput.weights = m.weights
        stepLife(life, dt, lifeInput)
        for (k in 0 until 5) {
            val seg = life.tail.segments[k]
            tailBones[k].rotation.set(
                (if (k == 0) 0.15 + tailLift * 0.7 else tailLift * (0.25 - k * 0.04)) + seg.pitch.x,
                0.0,
                seg.sway.x + fTurnBend * 0.2 * (if (k == 0) 1.0 else 0.5),
            )
        }
        for (k in maneBones.indices) {
            val seg = life.mane.segments[k]
            val bone = maneBones[k]
            // + sway presses the hair into the neck: only a little room for that
            val sway = if (seg.sway.x > 0) MANE_PRESS * tanh(seg.sway.x / MANE_PRESS) else seg.sway.x
            val pitch = MANE_SWING * tanh(seg.pitch.x / MANE_SWING)
            qTmp.setFromEuler(eTmp.set(pitch, 0.0, sway))
            bone.quaternion.copy(maneRest[k]).multiply(qTmp)
        }
        val fl = life.forelock.segments[0]
        bForelock.rotation.set(fl.pitch.x, 0.0, fl.sway.x)
        uniforms.flare = life.flare
        uniforms.blink = life.blinkClosure
        for (i in lidBones.indices) {
            lidBones[i].quaternion.setFromAxisAngle(lidAxes[i], -life.blinkClosure * Eye.CLOSE_ANGLE)
        }
    }

    /** The legs by IK: gait target (or pose) of the hooves -> joint angles through the limiter. */
    private fun moveLegs(dt: Double) {
        val m = motion
        val root = bRoot
        root.updateMatrix()
        bSpineFront.updateMatrix()
        bSpineRear.updateMatrix()
        mFront.multiplyMatrices(root.matrix, bSpineFront.matrix)
        mRear.multiplyMatrices(root.matrix, bSpineRear.matrix)
        // Airborne (a jump): the ground target of a hoof is out of reach, and the IK must not lock the
        // leg straight and bend it in a single frame when the target comes back (see softReach)
        airborne += (smoothstep(0.0, AIRBORNE_RAMP, fY) - airborne) * (1 - exp(-AIRBORNE_RATE * dt))
        if (airborne < 1e-3) airborne = 0.0
        val soft = lerp(GROUND_SOFT_REACH, AIRBORNE_SOFT_REACH, airborne)
        for (leg in 0 until 4) {
            val front = leg < 2
            inv.copy(if (front) mFront else mRear).invert()
            val l = m.legs[leg]
            val planted = l.stance && !l.squaring && fWeight < 0.02 && fY <= 1e-3
            val hoof = if (front) frontRig.h else hindRig.h
            val t4 = if (front) frontRig.t4 else hindRig.t4
            legTarget(leg, hoof, t4)
            if (front) {
                val scap = scapulaSlide(legHz - frontRig.h.z)
                solveFront(frontRig, legHz, legHy, legPast, legFlex, scap, rotOut, soft)
                frontSolved?.invoke(rotOut)
                val bones = legBones[leg]
                for (k in 0 until 5) bones[k].rotation.x = limitLeg(leg, k, rotOut[k], dt, planted)
            } else {
                val sweep = hindSweep(hindRig, legHz, legHy, legPast)
                val cannon = hindRig.t3 + 0.85 * (sweep - legAngle) + legAngle - legFlex
                solveHind(hindRig, legHz, legHy, legPast, cannon, rotOut, soft)
                val bones = legBones[leg]
                for (k in 0 until 4) bones[k].rotation.x = limitLeg(leg, k, rotOut[k], dt, planted)
            }
        }
    }

    /** The target of [leg] in rig space (hoof point, pastern angle, flexion) into the `leg*` fields. */
    private fun legTarget(
        leg: Int,
        restHoof: RigPoint,
        restPastern: Double,
    ) {
        val m = motion
        val l = m.legs[leg]
        val w = fWeight
        val g = 1 - w
        // gait target in rig space (ground = -y because the integration lifts the group by y)
        v3.set(legX[leg], -fY + l.y, legZ[leg] + l.dz).applyMatrix4(inv)
        down.set(0.0, -1.0, 0.0).transformDirection(inv)
        legAngle = angD(down.z, down.y)
        val o = LEG_OFFSET + leg * LEG_VALUES
        legHz = lerp(v3.z, restHoof.z + pose[o] / max(w, 1e-4), w)
        legHy = lerp(v3.y, restHoof.y + pose[o + 1] / max(w, 1e-4), w)
        legFlex = l.flex * g + pose[o + 2]
        val pastFold = l.past * g + pose[o + 3]
        legPast = restPastern + legAngle * (1 - 0.7 * w) + l.sink * 6 * g - pastFold
    }

    private fun updateRider(
        dt: Double,
        state: Horse,
    ) {
        val r = rider ?: return
        val m = motion
        riderCtx.weights = m.weights
        riderCtx.phi = m.phi
        riderCtx.lead = m.leadBlend
        riderCtx.jumpWeight = m.jumpWeight
        riderCtx.jumpJ = m.jumpJ
        riderCtx.hopWeight = m.hopWeight
        riderCtx.stopWeight = m.stopWeight
        riderCtx.pitch = fPitch
        riderCtx.neck = fNeck
        riderCtx.speed = m.speed
        r.update(dt, state, riderCtx)
    }

    /** World matrices, ear anchor and reins. */
    private fun updateWorldAndReins(dt: Double) {
        group.updateMatrixWorld(true)
        val head = bHead
        rider?.lateUpdate(dt)
        group.matrixWorld.decompose(tmpA, qObj, tmpB)
        head.matrixWorld.decompose(tmpA, qHead, tmpB)
        eX.set(fPitch * 0.4, 0.0, -fRoll * 0.5)
        qWant.setFromEuler(eX)
        earAnchor.quaternion.copy(qHead.invert().multiply(qObj).multiply(qWant))
        earAnchor.updateMatrixWorld(true)

        val r = reins ?: return
        mInvRig.copy(rig.matrixWorld).invert()
        val hands = rider?.hands
        for (s in 0 until 2) {
            val bit = tmpA.copy(bitLocal[s]).applyMatrix4(head.matrixWorld).applyMatrix4(mInvRig)
            val hand =
                if (hands != null) {
                    tmpB.setFromMatrixPosition(hands[s].matrixWorld).applyMatrix4(mInvRig)
                } else {
                    tmpB.copy(restLocal[s]).applyMatrix4(bSpineFront.matrixWorld).applyMatrix4(mInvRig)
                }
            r.setRein(s, bit, hand, if (hands != null) 0.06 else 0.02)
        }
        r.commit()
    }

    private fun collectFootfalls(
        falls: FootfallList,
        gait: Gait,
    ) {
        val m = motion
        footfallList.clear()
        val groundY = 0.0 - fY // (-0 -> 0)
        val strength = stepStrength(gait)
        for (i in 0 until falls.size) {
            val leg = falls[i]
            pushFootfall(
                FootfallKind.STEP,
                leg,
                gait,
                strength,
                legX[leg],
                groundY,
                legZ[leg] + m.legs[leg].dz - ORIGIN_OFFSET_Z,
            )
        }
        pushLanding(0, m.landing.front, LANDING_REACH_FRONT, gait, groundY)
        pushLanding(2, m.landing.hind, LANDING_REACH_HIND, gait, groundY)
        val callback = onFootfall ?: return
        for (i in 0 until footfallList.size) {
            val e = footfallList[i]
            if (e.kind == FootfallKind.STEP) callback(gait, e.leg)
        }
    }

    /** Adds an event to `footfalls`; the event objects are reused from frame to frame. */
    private fun pushFootfall(
        kind: FootfallKind,
        leg: Int,
        gait: Gait,
        strength: Double,
        x: Double,
        y: Double,
        z: Double,
    ) {
        val n = footfallList.size
        if (n >= footfallPool.size) footfallPool.add(Footfall())
        val e = footfallPool[n]
        e.kind = kind
        e.leg = leg
        e.gait = gait
        e.strength = strength
        e.x = x
        e.y = y
        e.z = z
        footfallList.add(e)
    }

    /** Landing of the legs [first] and [first] + 1 (strength 0 = nothing). */
    private fun pushLanding(
        first: Int,
        power: Double,
        reach: Double,
        gait: Gait,
        groundY: Double,
    ) {
        if (power <= 0) return
        for (leg in first until first + 2) {
            pushFootfall(
                FootfallKind.LANDING,
                leg,
                gait,
                power,
                legX[leg],
                groundY,
                legZ[leg] + reach - ORIGIN_OFFSET_Z,
            )
        }
    }
}

/**
 * Creates a horse. [coat] and [marking] default to the default appearance; [rider] and [tack] (saddle,
 * bridle, reins) can be left out (a grazing horse has none); [release] frees GPU objects that are
 * replaced on a quality change (see the GPU epoch); [rng] gives the random numbers of the idle
 * behaviour (default: seeded with [seed]); [castShadow] defaults to medium and up.
 */
fun createHorse(
    coat: Coat? = null,
    marking: Marking? = null,
    quality: GraphicsLevel = GraphicsLevel.MEDIUM,
    rider: Boolean = true,
    tack: Boolean = true,
    release: (GpuObject?) -> Unit = ::releaseNow,
    rng: (() -> Double)? = null,
    seed: Int = 7,
    castShadow: Boolean? = null,
): HorseView =
    HorseView(
        quality,
        normalizeAppearance(coat, marking),
        rider,
        tack,
        release,
        rng ?: createRng(seed),
        castShadow,
    )
