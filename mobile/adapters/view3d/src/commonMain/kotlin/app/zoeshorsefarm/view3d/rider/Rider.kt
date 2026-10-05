package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.SphereGeometry
import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Skeleton
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.math.CatmullRomCurve3
import app.zoeshorsefarm.scene.math.CatmullRomType
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.ColorSpace
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.view3d.horse.BoneWeight
import app.zoeshorsefarm.view3d.horse.Cap
import app.zoeshorsefarm.view3d.horse.Loft
import app.zoeshorsefarm.view3d.horse.LoftBuild
import app.zoeshorsefarm.view3d.horse.LoftDef
import app.zoeshorsefarm.view3d.horse.MeshBuilder
import app.zoeshorsefarm.view3d.horse.OvalSection
import app.zoeshorsefarm.view3d.horse.RiderContext
import app.zoeshorsefarm.view3d.horse.SeatPose
import app.zoeshorsefarm.view3d.horse.chainWeights
import app.zoeshorsefarm.view3d.horse.createSeatFilter
import app.zoeshorsefarm.view3d.horse.createVertexColorMaterial
import app.zoeshorsefarm.view3d.horse.curveFrames
import app.zoeshorsefarm.view3d.horse.ellipsoidData
import app.zoeshorsefarm.view3d.horse.geometryData
import app.zoeshorsefarm.view3d.horse.lineFrames
import app.zoeshorsefarm.view3d.horse.oval
import app.zoeshorsefarm.view3d.horse.rigid
import app.zoeshorsefarm.view3d.horse.row
import app.zoeshorsefarm.view3d.horse.smoothstep
import app.zoeshorsefarm.view3d.horse.table
import app.zoeshorsefarm.view3d.horse.torusData
import app.zoeshorsefarm.view3d.releaseNow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// Procedural rider (scene model): helmet, jacket, breeches, boots, gloves; one skinned mesh.
// Seat per gait: walk/halt sitting, trot rising (posting) in rhythm, canter light seat,
// jump two-point seat with crest release. Feet stay in the stirrups and hands on the reins via
// two-bone IK. Local frame: origin = seat point on the saddle, +Z forward, Y up.

private val SIDES = doubleArrayOf(1.0, -1.0)
private val PONY_NAMES = listOf("pony1", "pony2", "pony3")

// the head takes most of the look, the neck the rest
private const val LOOK_SHARE_NECK = 0.4
private const val LOOK_SHARE_HEAD = 0.6

// right hand on the horse's neck for the pat after a jump (offsets to the rein position)
private const val PAT_X = 0.04
private const val PAT_Y = -0.1
private const val PAT_Z = 0.24
private const val PAT_TAP = 0.03
private const val PAT_LEAN = 0.5

// the ponytail ignores head jumps above this distance per frame (restart, teleport) in metres
private const val TELEPORT_DISTANCE = 3.0
private const val ACCEL_FILTER = 22.0 // 1/s, low-pass on the head acceleration

private class LimbNames(
    val thigh: String,
    val shin: String,
    val foot: String,
    val upperArm: String,
    val forearm: String,
    val hand: String,
)

private fun boneNames(p: String) =
    LimbNames("${p}thigh", "${p}shin", "${p}foot", "${p}upperArm", "${p}forearm", "${p}hand")

/** The bones of one side of the rider, resolved by [LimbNames]. */
private class LimbBones(
    names: LimbNames,
    bones: Map<String, Bone>,
) {
    val thigh = bones.getValue(names.thigh)
    val shin = bones.getValue(names.shin)
    val foot = bones.getValue(names.foot)
    val upperArm = bones.getValue(names.upperArm)
    val forearm = bones.getValue(names.forearm)
    val hand = bones.getValue(names.hand)
}

// precomputed: update() runs every frame
private val LEFT_NAMES = boneNames("L")
private val RIGHT_NAMES = boneNames("R")

private fun joint(
    x: Double,
    y: Double,
    z: Double,
) = doubleArrayOf(x, y, z)

// Bind pose (sitting) joint positions in rider space
private object J {
    val base = joint(0.0, 0.0, 0.0)
    val pelvis = joint(0.0, 0.1, -0.04)
    val spine = joint(0.0, 0.28, -0.05)
    val chest = joint(0.0, 0.46, -0.04)
    val neck = joint(0.0, 0.63, -0.03)
    val head = joint(0.0, 0.72, -0.02)
    val shoulder = joint(0.17, 0.59, -0.03)
    val elbow = joint(0.19, 0.36, 0.07)
    val wrist = joint(0.09, 0.22, 0.33)
    val hip = joint(0.1, 0.08, -0.02)
    val knee = joint(0.27, -0.17, 0.28)
    val ankle = joint(0.36, -0.56, 0.15)
    val toe = joint(0.365, -0.59, 0.31)
    val pony1 = PonyJoints.pony1
    val pony2 = PonyJoints.pony2
    val pony3 = PonyJoints.pony3
}

private fun mir(
    a: DoubleArray,
    s: Double,
) = doubleArrayOf(a[0] * s, a[1], a[2])

private fun v(a: DoubleArray) = Vec3(a[0], a[1], a[2])

private val COLORS =
    mapOf(
        "jacket" to doubleArrayOf(0.07, 0.09, 0.2),
        "breeches" to doubleArrayOf(0.92, 0.9, 0.84),
        "boot" to doubleArrayOf(0.03, 0.03, 0.03),
        "skin" to doubleArrayOf(0.88, 0.68, 0.56),
        "hair" to doubleArrayOf(0.38, 0.24, 0.12),
        "helmet" to doubleArrayOf(0.04, 0.04, 0.05),
        "glove" to doubleArrayOf(0.06, 0.06, 0.06),
        "collar" to doubleArrayOf(0.95, 0.95, 0.95),
        "steel" to doubleArrayOf(0.72, 0.73, 0.75),
        "leather" to doubleArrayOf(0.16, 0.09, 0.05),
        "hairTip" to doubleArrayOf(0.5, 0.33, 0.17),
        "eyeWhite" to doubleArrayOf(0.97, 0.97, 0.95),
        "iris" to doubleArrayOf(0.22, 0.4, 0.62),
        "pupil" to doubleArrayOf(0.02, 0.02, 0.03),
        "brow" to doubleArrayOf(0.24, 0.15, 0.08),
        "nose" to doubleArrayOf(0.86, 0.6, 0.5),
        "lip" to doubleArrayOf(0.66, 0.24, 0.24),
        "blush" to doubleArrayOf(0.96, 0.58, 0.52),
        "strap" to doubleArrayOf(0.05, 0.045, 0.045),
        "bow" to doubleArrayOf(0.93, 0.3, 0.5),
        "bowKnot" to doubleArrayOf(0.8, 0.2, 0.4),
        "button" to doubleArrayOf(0.86, 0.7, 0.28),
        "piping" to doubleArrayOf(0.82, 0.68, 0.3),
    )

private fun lin(c: DoubleArray): DoubleArray {
    val col = Color().setRGB(c[0], c[1], c[2], ColorSpace.SRGB)
    return doubleArrayOf(col.r, col.g, col.b)
}

private val LINEAR: Map<String, DoubleArray> = COLORS.mapValues { lin(it.value) }

private fun colorOf(name: String): DoubleArray = LINEAR.getValue(name)

private val C =
    RiderColors(
        jacket = colorOf("jacket"),
        breeches = colorOf("breeches"),
        boot = colorOf("boot"),
        glove = colorOf("glove"),
        helmet = colorOf("helmet"),
        leather = colorOf("leather"),
        pupil = colorOf("pupil"),
        eyeWhite = colorOf("eyeWhite"),
        iris = colorOf("iris"),
        brow = colorOf("brow"),
        skin = colorOf("skin"),
        blush = colorOf("blush"),
        nose = colorOf("nose"),
        lip = colorOf("lip"),
        strap = colorOf("strap"),
        steel = colorOf("steel"),
        hair = colorOf("hair"),
        hairTip = colorOf("hairTip"),
        bow = colorOf("bow"),
        bowKnot = colorOf("bowKnot"),
        button = colorOf("button"),
        collar = colorOf("collar"),
        piping = colorOf("piping"),
    )

/** Rings and segments per level. */
private class RiderDetail(
    val torso: IntArray,
    val limb: IntArray,
    val head: IntArray,
    val peak: IntArray,
    val hand: IntArray,
    val iron: IntArray,
    val foot: Int,
    val leather: Int,
)

private fun pair(
    a: Int,
    b: Int,
) = intArrayOf(a, b)

// `low` must not get more triangles than the rider had before the face and the ponytail were
// added (1336): the face is paid for with coarser hands, boots and stirrups, which are small in
// the picture.
private val DETAIL_LOW = RiderDetail(pair(8, 8), pair(4, 6), pair(8, 6), pair(5, 3), pair(5, 4), pair(3, 5), 3, 2)
private val DETAIL_MEDIUM =
    RiderDetail(pair(14, 12), pair(7, 9), pair(12, 9), pair(10, 4), pair(10, 6), pair(4, 10), 4, 4)
private val DETAIL_HIGH =
    RiderDetail(pair(20, 16), pair(10, 12), pair(16, 12), pair(14, 4), pair(14, 6), pair(4, 14), 4, 4)

private fun detailOf(level: GraphicsLevel): RiderDetail =
    when (level) {
        GraphicsLevel.LOW -> DETAIL_LOW
        GraphicsLevel.MEDIUM -> DETAIL_MEDIUM
        GraphicsLevel.HIGH -> DETAIL_HIGH
    }

private fun samples(n: Int): List<Double> = List(n + 1) { it.toDouble() / n }

private class RiderBones(
    val bones: Map<String, Bone>,
    val list: List<Bone>,
    val index: Map<String, Int>,
)

private fun createBones(): RiderBones {
    val bones = LinkedHashMap<String, Bone>()
    val list = ArrayList<Bone>()
    val rest = HashMap<String, Vec3>()

    fun make(
        name: String,
        pos: DoubleArray,
        parent: String?,
    ) {
        val b = Bone()
        b.name = name
        val restPos = v(pos)
        rest[name] = restPos
        if (parent != null) {
            b.position.copy(restPos).sub(rest.getValue(parent))
            bones.getValue(parent).add(b)
        } else {
            b.position.copy(restPos)
        }
        bones[name] = b
        list.add(b)
    }
    make("base", J.base, null)
    make("pelvis", J.pelvis, "base")
    make("spine", J.spine, "pelvis")
    make("chest", J.chest, "spine")
    make("neck", J.neck, "chest")
    make("head", J.head, "neck")
    make("pony1", J.pony1, "head")
    make("pony2", J.pony2, "pony1")
    make("pony3", J.pony3, "pony2")
    for (s in SIDES) {
        val p = if (s > 0) "L" else "R"
        make("${p}upperArm", mir(J.shoulder, s), "chest")
        make("${p}forearm", mir(J.elbow, s), "${p}upperArm")
        make("${p}hand", mir(J.wrist, s), "${p}forearm")
        make("${p}thigh", mir(J.hip, s), "pelvis")
        make("${p}shin", mir(J.knee, s), "${p}thigh")
        make("${p}foot", mir(J.ankle, s), "${p}shin")
    }
    val index = HashMap<String, Int>()
    list.forEachIndexed { i, b -> index[b.name] = i }
    return RiderBones(bones, list, index)
}

private fun colorAttrs(c: DoubleArray): Map<String, DoubleArray> = mapOf("color" to c)

/** Tube along a polyline of joints with radius table r(u) and bone chain weights. */
private fun limb(
    b: MeshBuilder,
    points: List<DoubleArray>,
    radii: Array<DoubleArray>,
    bones: List<String>,
    color: (Double) -> DoubleArray,
    d: RiderDetail,
    n: Int? = null,
    capStart: Double = 0.02,
    capEnd: Double = 0.02,
) {
    val pts = points.map { v(it) }
    val curve = CatmullRomCurve3(pts, false, CatmullRomType.CENTRIPETAL)
    val len = curve.getLength()
    val joints = ArrayList<Double>()
    var acc = 0.0
    for (i in 1 until points.size - 1) {
        acc += pts[i].distanceTo(pts[i - 1])
        joints.add(acc / len)
    }
    val jointArray = joints.toDoubleArray()
    val rt = table(*radii)
    Loft(
        LoftDef(
            frame = curveFrames(curve),
            section = { u, a ->
                val rf = rt(u)
                val r = rf[0]
                val flat = if (rf.size > 1) rf[1] else 1.0
                oval(a, OvalSection(w = r, up = r * flat, down = r * flat))
            },
            weights = { u, _, _ -> chainWeights(u, jointArray, bones, 0.04 / len) },
            attrs = { u, _, _, _, _ -> colorAttrs(color(u)) },
        ),
    ).build(
        b,
        LoftBuild(
            uSamples = samples(n ?: d.limb[0]),
            radial = d.limb[1],
            capStart = Cap(capStart, 1),
            capEnd = Cap(capEnd, 1),
        ),
    )
}

private fun buildRiderGeometry(
    index: Map<String, Int>,
    level: GraphicsLevel,
): Geometry {
    val d = detailOf(level)
    val b = MeshBuilder(index, mapOf("color" to 3))
    addTorso(b, d, level)
    addHeadAndHelmet(b, d, level)
    for (s in SIDES) {
        addArmAndHand(b, d, s)
        addLegAndStirrup(b, d, s)
    }
    return b.build()
}

/** Torso (n points backward for an upward tangent: "up" = back, "down" = front) with jacket details. */
private fun addTorso(
    b: MeshBuilder,
    d: RiderDetail,
    level: GraphicsLevel,
) {
    val torsoPts =
        listOf(
            Vec3(0.0, -0.015, -0.06),
            Vec3(0.0, 0.2, -0.06),
            Vec3(0.0, 0.42, -0.05),
            Vec3(0.0, 0.63, -0.03),
        )
    val curve = CatmullRomCurve3(torsoPts, false, CatmullRomType.CENTRIPETAL)
    val t =
        table(
            row(0.0, 0.155, 0.13, 0.09),
            row(0.15, 0.17, 0.125, 0.11),
            row(0.36, 0.14, 0.09, 0.1),
            row(0.6, 0.165, 0.1, 0.11),
            row(0.84, 0.19, 0.09, 0.08),
            row(1.0, 0.07, 0.05, 0.05),
        )
    val ju = doubleArrayOf(0.27, 0.62, 0.95)
    val torsoBones = listOf("pelvis", "spine", "chest", "neck")
    val torso =
        Loft(
            LoftDef(
                frame = curveFrames(curve),
                section = { u, a ->
                    val s = t(u)
                    oval(a, OvalSection(w = s[0], up = s[1], down = s[2], nUp = 2.2, nDown = 2.2))
                },
                weights = { u, _, _ -> chainWeights(u, ju, torsoBones, 0.06) },
                attrs = { u, _, _, _, _ ->
                    colorAttrs(
                        if (u < 0.3) {
                            C.breeches
                        } else if (u > 0.95) {
                            C.collar
                        } else {
                            C.jacket
                        },
                    )
                },
            ),
        )
    torso.build(
        b,
        LoftBuild(samples(d.torso[0]), d.torso[1], capStart = Cap(0.05, 2), capEnd = Cap(0.02, 1)),
    )
    addJacketDetails(b, torso, level, C)
}

/** Neck, head, ponytail, helmet with peak, face and chin strap. */
private fun addHeadAndHelmet(
    b: MeshBuilder,
    d: RiderDetail,
    level: GraphicsLevel,
) {
    limb(
        b,
        listOf(joint(0.0, 0.6, -0.035), joint(0.0, 0.69, -0.025), joint(0.0, 0.78, -0.01)),
        arrayOf(row(0.0, 0.048), row(1.0, 0.045)),
        listOf("neck", "head"),
        { C.skin },
        d,
        n = 3,
    )
    val hd = ellipsoidData(0.083, 0.112, 0.1, d.head[0], d.head[1])
    val headW = rigid("head")
    b.addIndexed(
        hd,
        Mat4().makeTranslation(0.0, 0.815, 0.0),
        { headW },
        { p -> colorAttrs(if (p.z < -0.02 && p.y > 0.755) C.hair else C.skin) },
    )
    addPonytail(b, level, C)
    // Helmet (shell) with peak
    val helmet = SphereGeometry(1.0, d.head[0], d.head[1], 0.0, PI * 2, 0.0, PI * 0.56)
    helmet.scale(0.096, 0.1, 0.116)
    val helmetColor = colorAttrs(C.helmet)
    b.addIndexed(
        geometryData(helmet),
        Mat4().makeRotationX(-0.12).setPosition(0.0, 0.845, -0.005),
        { headW },
        { helmetColor },
    )
    helmet.dispose()
    val peak = ellipsoidData(0.072, 0.008, 0.05, d.peak[0], d.peak[1])
    b.addIndexed(peak, Mat4().makeRotationX(0.25).setPosition(0.0, 0.86, 0.095), { headW }, { helmetColor })
    addFace(b, level, C)
    addChinStrap(b, level, C)
}

private fun addArmAndHand(
    b: MeshBuilder,
    d: RiderDetail,
    s: Double,
) {
    val p = if (s > 0) "L" else "R"
    limb(
        b,
        listOf(mir(J.shoulder, s), mir(J.elbow, s), mir(J.wrist, s)),
        arrayOf(row(0.0, 0.05), row(0.45, 0.043), row(0.55, 0.04), row(0.95, 0.031), row(1.0, 0.032)),
        listOf("${p}upperArm", "${p}forearm"),
        { C.jacket },
        d,
    )
    val hand = ellipsoidData(0.028, 0.042, 0.05, d.hand[0], d.hand[1])
    val hp = Vec3(J.wrist[0] * s, J.wrist[1], J.wrist[2]).add(Vec3(-0.01 * s, -0.01, 0.04))
    val handW = rigid("${p}hand")
    val glove = colorAttrs(C.glove)
    b.addIndexed(hand, Mat4().makeRotationX(0.5).setPosition(hp), { handW }, { glove })
}

/** Leg: thigh (breeches), shin and foot (boot), stirrup iron and leather. */
private fun addLegAndStirrup(
    b: MeshBuilder,
    d: RiderDetail,
    s: Double,
) {
    val p = if (s > 0) "L" else "R"
    limb(
        b,
        listOf(mir(J.hip, s), mir(J.knee, s), mir(J.ankle, s)),
        arrayOf(
            row(0.0, 0.085),
            row(0.25, 0.08),
            row(0.47, 0.058),
            row(0.5, 0.057),
            row(0.54, 0.06),
            row(0.72, 0.055),
            row(1.0, 0.04),
        ),
        listOf("${p}thigh", "${p}shin"),
        { u -> if (u < 0.52) C.breeches else C.boot },
        d,
        capStart = 0.05,
    )
    limb(
        b,
        listOf(
            mir(joint(J.ankle[0], J.ankle[1] - 0.01, J.ankle[2] - 0.05), s),
            mir(J.ankle, s),
            mir(J.toe, s),
        ),
        arrayOf(row(0.0, 0.04, 1.1), row(0.5, 0.042, 1.0), row(1.0, 0.033, 0.8)),
        listOf("${p}foot", "${p}foot"),
        { C.boot },
        d,
        n = d.foot,
    )
    // Stirrup iron under the ball of the foot, leather up to the saddle
    val iron = torusData(0.055, 0.007, d.iron[0], d.iron[1])
    val ip = Vec3(J.toe[0] * s, J.toe[1] + 0.055 - 0.035, J.toe[2] - 0.07)
    val footW = rigid("${p}foot")
    val steel = colorAttrs(C.steel)
    b.addIndexed(iron, Mat4().makeScale(0.9, 1.0, 1.0).setPosition(ip), { footW }, { steel })
    val top = Vec3(0.2 * s, -0.04, 0.1)
    val bottom = ip.clone().add(Vec3(0.0, 0.05, 0.0))
    val leather = colorAttrs(C.leather)
    Loft(
        LoftDef(
            frame = lineFrames(top, bottom, Vec3(0.0, 0.0, 1.0)),
            section = { _, a -> doubleArrayOf(cos(a) * 0.003, sin(a) * 0.016) },
            weights = { u, _, _ ->
                listOf(
                    BoneWeight("base", 1 - smoothstep(0.0, 1.0, u)),
                    BoneWeight("${p}foot", smoothstep(0.0, 1.0, u)),
                )
            },
            attrs = { _, _, _, _, _ -> leather },
        ),
    ).build(b, LoftBuild(samples(d.leather), 4))
}

/** Rider from bind pose; [update] applies the seat and IK. */
class Rider(
    quality: GraphicsLevel = GraphicsLevel.MEDIUM,
    private val release: (GpuObject?) -> Unit = ::releaseNow,
) {
    private var level = quality

    /** Root node of the rider (origin = seat point on the saddle). */
    val group = Group()
    private val riderBones = createBones()

    /** The bones by name (`base`, `pelvis`, ..., `Lhand`, `Rhand`, `pony1` ... `pony3`). */
    val bones: Map<String, Bone> = riderBones.bones
    private val list = riderBones.list
    private val skeleton: Skeleton

    /** The one skinned mesh of the rider. */
    val mesh: SkinnedMesh

    /** The hands (left, right), for the reins. */
    val hands: List<Bone>

    init {
        group.name = "rider"
        group.add(bones.getValue("base"))
        group.updateMatrixWorld(true)
        skeleton = Skeleton(list)
        mesh = SkinnedMesh(buildRiderGeometry(riderBones.index, level), createVertexColorMaterial(level, 0.7))
        mesh.name = "rider-body"
        mesh.frustumCulled = false
        group.add(mesh)
        mesh.bind(skeleton, mesh.matrixWorld)
        mesh.castShadow = level != GraphicsLevel.LOW
        hands = listOf(bones.getValue("Lhand"), bones.getValue("Rhand"))
    }

    private val restDir = HashMap<Bone, Vec3>()

    init {
        for (b in list) {
            val child = b.children.firstOrNull { it is Bone }
            if (child != null) restDir[b] = child.position.clone().normalize()
        }
        val handDir = Vec3(0.0, -0.2, 1.0).normalize()
        restDir[bones.getValue("Lhand")] = handDir
        restDir[bones.getValue("Rhand")] = handDir.clone()
    }

    // the bones that update() moves, looked up once
    private val bPelvis = bones.getValue("pelvis")
    private val bSpine = bones.getValue("spine")
    private val bChest = bones.getValue("chest")
    private val bNeck = bones.getValue("neck")
    private val bHead = bones.getValue("head")
    private val bBase = bones.getValue("base")
    private val leftLimbs = LimbBones(LEFT_NAMES, bones)
    private val rightLimbs = LimbBones(RIGHT_NAMES, bones)

    private val invBase = Mat4()
    private val m4 = Mat4()
    private val pRoot = Vec3()
    private val pMid = Vec3()
    private val pTarget = Vec3()
    private val pPole = Vec3()
    private val qParent = Quat()
    private val qTmp = Quat()
    private val vA = Vec3()
    private val vB = Vec3()
    private val scl = Vec3()
    private val vTmp = Vec3()
    private val vDir = Vec3()

    private fun frameOf(
        bone: Node,
        posOut: Vec3,
        quatOut: Quat,
    ) {
        m4.multiplyMatrices(invBase, bone.matrixWorld)
        m4.decompose(posOut, quatOut, scl)
    }

    /** Local quaternion so that the rest direction of [bone] points along [dirBase] (base frame). */
    private fun aim(
        bone: Bone,
        dirBase: Vec3,
    ) {
        frameOf(bone.parent!!, vTmp, qParent) // (a bone of the chain always has a parent)
        vB.copy(dirBase).normalize().applyQuaternion(qTmp.copy(qParent).invert())
        bone.quaternion.setFromUnitVectors(restDir.getValue(bone), vB)
        bone.updateMatrixWorld(true)
    }

    private fun twoBone(
        a: Bone,
        mid: Bone,
        end: Bone,
        target: Vec3,
        pole: Vec3,
    ) {
        frameOf(a, pRoot, qParent)
        val l1 = mid.position.length()
        val l2 = end.position.length()
        val dv = vA.subVectors(target, pRoot)
        val dist = limbReach(dv.length(), l1, l2)
        dv.normalize()
        val x = (l1 * l1 + dist * dist - l2 * l2) / (2 * dist)
        val h = sqrt(maxOf(0.0, l1 * l1 - x * x))
        vB.subVectors(pole, pRoot)
        vB.addScaledVector(dv, -vB.dot(dv)).normalize()
        pMid.copy(pRoot).addScaledVector(dv, x).addScaledVector(vB, h)
        aim(a, vDir.subVectors(pMid, pRoot))
        aim(mid, vDir.subVectors(target, pMid))
    }

    private val seatFilter = createSeatFilter()
    private val seat = SeatPose()
    private val look = createHeadLook()
    private val pat = createPat()
    private val breath = Breath()
    private val pony = createPonytail()
    private val ponyBones = PONY_NAMES.map { bones.getValue(it) }
    private val ponyInput = PonytailInput()

    // head motion for the ponytail
    private var hasMotion = false
    private val mPos = Vec3()
    private val mPrev = Vec3()
    private val mVel = Vec3()
    private val mPrevVel = Vec3()
    private val mAcc = Vec3()
    private val mQuat = Quat()
    private val vL = Vec3()
    private val aL = Vec3()
    private val gL = Vec3()

    /**
     * Applies the seat and the two-bone IK for the horse [state]; [ctx] is the horse's motion as the
     * rider sees it (without it the gait of [state] decides about halt).
     */
    fun update(
        dt: Double,
        state: Horse,
        ctx: RiderContext? = null,
    ) {
        seatFilter.step(dt, state, ctx, seat)
        val halt =
            if (ctx != null) {
                ctx.weights.halt
            } else if (state.gait == Gait.HALT) {
                1.0
            } else {
                0.0
            }
        stepHeadLook(look, dt, state, halt)
        stepPat(pat, dt, jumping = state.jump != null, halted = halt > 0.95)
        breathing(look.time, halt, breath)
        val pelvis = bPelvis
        val spine = bSpine
        val chest = bChest
        val neck = bNeck
        val head = bHead
        val base = bBase
        pelvis.position.set(J.pelvis[0], J.pelvis[1] + seat.rise, J.pelvis[2] + seat.forward)
        val lean = seat.lean + PAT_LEAN * pat.reach
        pelvis.rotation.set(lean * 0.4 + seat.sway, 0.0, seat.roll)
        spine.rotation.set(lean * 0.33, 0.0, 0.0)
        chest.rotation.set(lean * 0.27 + breath.chest, 0.0, breath.roll)
        val yaw = look.yaw.x
        neck.rotation.set(-lean * 0.45, yaw * LOOK_SHARE_NECK, 0.0)
        head.rotation.set(-lean * 0.4 - seat.horsePitch * 0.6 + look.pitch.x, yaw * LOOK_SHARE_HEAD, 0.0)
        base.updateMatrixWorld(true)
        invBase.copy(base.matrixWorld).invert()
        for (s in SIDES) {
            val limbs = if (s > 0) leftLimbs else rightLimbs
            val shin = limbs.shin
            val foot = limbs.foot
            val forearm = limbs.forearm
            val hand = limbs.hand
            // legs: ankle in the stirrup (fixed on the saddle), knee forward/outward
            pTarget.set(J.ankle[0] * s, J.ankle[1], J.ankle[2] + seat.footForward)
            pPole.set(0.6 * s, -0.1, 1.2)
            twoBone(limbs.thigh, shin, foot, pTarget, pPole)
            frameOf(shin, vA, qParent)
            foot.quaternion.copy(qParent).invert()
            foot.rotateX(-0.1)
            // arms: hands on the reins above the withers, elbows down/back/outward; the right hand
            // goes to the horse's neck for a pat
            val patS = if (s < 0) pat.reach else 0.0
            pTarget.set(
                (seat.handX + PAT_X * patS) * s,
                seat.handY + PAT_Y * patS + PAT_TAP * pat.tap * patS,
                seat.handZ + PAT_Z * patS,
            )
            pPole.set(0.5 * s, -0.4, -0.6)
            twoBone(limbs.upperArm, forearm, hand, pTarget, pPole)
            frameOf(forearm, vA, qParent)
            hand.quaternion.copy(qParent).invert()
            hand.rotateZ(-0.5 * s)
        }
    }

    /**
     * Secondary motion that needs the final world matrices of the head (call after the horse has
     * updated them): the ponytail lags behind the head's bob, acceleration and turns.
     */
    fun lateUpdate(dt: Double) {
        if (!(dt > 0)) return
        bHead.matrixWorld.decompose(mPos, mQuat, scl)
        if (!hasMotion || mPos.distanceToSquared(mPrev) > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            // first frame or a jump in place (restart): no velocity yet
            hasMotion = true
            mPrev.copy(mPos)
            mVel.set(0.0, 0.0, 0.0)
            mPrevVel.set(0.0, 0.0, 0.0)
            mAcc.set(0.0, 0.0, 0.0)
            return
        }
        mVel.subVectors(mPos, mPrev).divideScalar(dt)
        mAcc.lerp(vA.subVectors(mVel, mPrevVel).divideScalar(dt), 1 - exp(-ACCEL_FILTER * dt))
        mPrev.copy(mPos)
        mPrevVel.copy(mVel)
        // into the head's own frame (+Z forward, +X left)
        qTmp.copy(mQuat).invert()
        vL.copy(mVel).applyQuaternion(qTmp)
        aL.copy(mAcc).applyQuaternion(qTmp)
        gL.set(0.0, -1.0, 0.0).applyQuaternion(qTmp)
        ponyInput.ax = aL.x
        ponyInput.ay = aL.y
        ponyInput.az = aL.z
        ponyInput.vx = vL.x
        ponyInput.vy = vL.y
        ponyInput.vz = vL.z
        ponyInput.gy = gL.y
        ponyInput.gz = gL.z
        stepPonytail(pony, ponyInput, dt)
        for (i in 0 until PONY_SEGMENTS) {
            ponyBones[i].rotation.set(pony.pitch[i].x, pony.yaw[i].x, 0.0)
        }
    }

    fun setQuality(l: GraphicsLevel) {
        if (l == level) return
        level = l
        release(mesh.geometry)
        release(mesh.material)
        mesh.geometry = buildRiderGeometry(riderBones.index, level)
        mesh.material = createVertexColorMaterial(level, 0.7)
        mesh.castShadow = level != GraphicsLevel.LOW
    }

    fun dispose() {
        // through `release`, so that objects of a lost context are not freed with GL calls
        release(mesh.geometry)
        release(mesh.material)
        release(skeleton.boneTexture)
        group.removeFromParent()
    }
}

/** Creates the rider; [release] frees replaced GPU objects (see the GPU epoch), right away by default. */
fun createRider(
    quality: GraphicsLevel = GraphicsLevel.MEDIUM,
    release: (GpuObject?) -> Unit = ::releaseNow,
): Rider = Rider(quality, release)
