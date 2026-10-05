package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Horse bone hierarchy in model coordinates (rest pose from Anatomy.kt). Bones have no rotation in
// the rest pose; animation = rotation relative to it.

// Head: axis from the poll (head bone) towards the muzzle, tilted ~55 degrees downwards.
private const val HEAD_ANGLE = 55 * PI / 180

/** The head frame: [origin] at the poll, [dir] towards the muzzle, [front] towards the forehead. */
class HeadFrame(
    val origin: Vec3,
    val dir: Vec3,
    val front: Vec3,
    val length: Double,
)

val HEAD =
    HeadFrame(
        origin = Vec3(REST.head[0], REST.head[1], REST.head[2]),
        dir = Vec3(0.0, -sin(HEAD_ANGLE), cos(HEAD_ANGLE)),
        front = Vec3(0.0, cos(HEAD_ANGLE), sin(HEAD_ANGLE)),
        length = 0.62,
    )

/** Point in head coordinates (s along the head, f towards the forehead, x lateral). */
fun headPoint(
    s: Double,
    f: Double,
    x: Double,
): Vec3 =
    HEAD.origin
        .clone()
        .addScaledVector(HEAD.dir, s)
        .addScaledVector(HEAD.front, f)
        .add(Vec3(x, 0.0, 0.0))

/** The ear: base point and direction per side, length. */
object Ear {
    fun base(side: Double): Vec3 = headPoint(0.035, 0.085, 0.058 * side)

    fun dir(side: Double): Vec3 = Vec3(0.28 * side, 1.0, 0.12).normalize()

    const val LENGTH = 0.16
}

/** Anchor of the forelock bone on the forehead (it swings about this point). */
private fun forelockAnchor(): Vec3 = headPoint(-0.02, 0.08, 0.0)

/**
 * Eye and eyelid: the eye is an ellipsoid turned about Y by [yaw]; the upper lid is a hemispherical
 * cap whose pole points at [openAngle] (from the gaze towards up and back, so the lid rests above
 * the eye) and is turned about its axis by -[closeAngle] to cover the eye.
 */
object Eye {
    val radii = doubleArrayOf(0.019, 0.018, 0.025)
    const val YAW = 0.35

    fun center(side: Double): Vec3 = headPoint(0.168, 0.03, 0.104 * side)

    const val OPEN_ANGLE = 135 * PI / 180
    const val CLOSE_ANGLE = 150 * PI / 180
    const val LID_SCALE = 1.15
}

private val UP = Vec3(0.0, 1.0, 0.0)

/** Gaze direction of the eye on [side] (head frame). */
fun eyeGaze(side: Double): Vec3 = Vec3(side, 0.0, 0.0).applyAxisAngle(UP, Eye.YAW * side)

/** Hinge axis of the eyelid (head frame); a positive rotation about it lifts the gaze upwards. */
fun lidAxis(side: Double): Vec3 = eyeGaze(side).cross(UP).normalize()

/** Direction of the lid pole at [angle] from the gaze towards up (head frame). */
fun lidPole(
    side: Double,
    angle: Double,
): Vec3 = eyeGaze(side).multiplyScalar(cos(angle)).addScaledVector(UP, sin(angle))

/** The rider's seat point. */
val SADDLE_SEAT = Vec3(0.0, 1.63, 0.08)

/** Between the ears. */
fun earAnchor(): Vec3 = headPoint(0.03, 0.14, 0.0)

/** The bones of the horse: [root], by name, in the order of the skeleton ([list]) and their [index]. */
class SkeletonBones(
    val root: Bone,
    val bones: Map<String, Bone>,
    val list: List<Bone>,
    val index: Map<String, Int>,
    /** Rest position of every bone in model space. */
    val rest: Map<String, Vec3>,
    /** Rest rotation of the bones that have one (the mane bones). */
    val restQuaternion: Map<String, Quat>,
    /** Hinge axis of the eyelid bones. */
    val lidAxes: Map<String, Vec3>,
) {
    operator fun get(name: String): Bone = bones.getValue(name)
}

private fun v(a: DoubleArray) = Vec3(a[0], a[1], a[2])

private fun mirror(
    a: DoubleArray,
    side: Double,
) = Vec3(a[0] * side, a[1], a[2])

private class BoneMaker {
    val bones = LinkedHashMap<String, Bone>()
    val list = ArrayList<Bone>()
    val rest = HashMap<String, Vec3>()
    val restQuaternion = HashMap<String, Quat>()
    val lidAxes = HashMap<String, Vec3>()

    fun make(
        name: String,
        restPos: Vec3,
        parentName: String?,
        quaternion: Quat? = null,
    ): Bone {
        val b = Bone()
        b.name = name
        rest[name] = restPos
        if (quaternion != null) {
            b.quaternion.copy(quaternion)
            restQuaternion[name] = quaternion.clone()
        }
        val parent = if (parentName != null) bones[parentName] else null
        if (parent != null && parentName != null) {
            b.position.copy(restPos).sub(rest.getValue(parentName))
            parent.add(b)
        } else {
            b.position.copy(restPos)
        }
        bones[name] = b
        list.add(b)
        return b
    }
}

private fun makeLegs(m: BoneMaker) {
    for ((i, side) in SIDES.withIndex()) {
        val p = if (i == 0) "L" else "R"
        val f = REST.front
        m.make("${p}scapula", mirror(f.scapula, side), "spineFront")
        m.make("${p}humerus", mirror(f.shoulder, side), "${p}scapula")
        m.make("${p}forearm", mirror(f.elbow, side), "${p}humerus")
        m.make("${p}fcannon", mirror(f.knee, side), "${p}forearm")
        m.make("${p}fpastern", mirror(f.fetlock, side), "${p}fcannon")
        val h = REST.hind
        m.make("${p}femur", mirror(h.hip, side), "spineRear")
        m.make("${p}tibia", mirror(h.stifle, side), "${p}femur")
        m.make("${p}hcannon", mirror(h.hock, side), "${p}tibia")
        m.make("${p}hpastern", mirror(h.fetlock, side), "${p}hcannon")
    }
}

/** Creates the bone hierarchy. */
fun createSkeletonBones(): SkeletonBones {
    val m = BoneMaker()
    val root = m.make("root", v(REST.root), null)
    m.make("spineFront", v(REST.spineFront), "root")
    m.make("spineRear", v(REST.spineRear), "root")
    m.make("belly", v(REST.belly), "root")
    makeLegs(m)
    m.make("neck1", v(REST.neck[0]), "spineFront")
    m.make("neck2", v(REST.neck[1]), "neck1")
    m.make("neck3", v(REST.neck[2]), "neck2")
    m.make("head", v(REST.head), "neck3")
    for ((i, side) in SIDES.withIndex()) {
        m.make("${if (i == 0) "L" else "R"}ear", Ear.base(side), "head")
    }
    REST.tail.forEachIndexed { i, t -> m.make("tail${i + 1}", v(t), if (i == 0) "spineRear" else "tail$i") }
    // mane (a bone per anchor on the crest) and forelock: they swing on their own (Life.kt)
    for (a in maneAnchors()) m.make(a.name, a.position, a.parent, a.quaternion)
    m.make("forelock", forelockAnchor(), "head")
    // eyelids
    for ((i, side) in SIDES.withIndex()) {
        val name = "${if (i == 0) "L" else "R"}lid"
        m.make(name, Eye.center(side), "head")
        m.lidAxes[name] = lidAxis(side)
    }
    val index = HashMap<String, Int>()
    m.list.forEachIndexed { i, b -> index[b.name] = i }
    return SkeletonBones(root, m.bones, m.list, index, m.rest, m.restQuaternion, m.lidAxes)
}
