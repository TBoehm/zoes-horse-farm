package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.scene.math.CatmullRomCurve3
import app.zoeshorsefarm.scene.math.CatmullRomType
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3

// Neck shape data and the anchors of the mane bones (shared by HorseGeometry.kt and Skeleton.kt).

private fun v(a: DoubleArray) = Vec3(a[0], a[1], a[2])

private val NECK_PTS =
    listOf(
        Vec3(0.0, 1.25, 0.46),
        Vec3(0.0, 1.42, 0.74),
        Vec3(0.0, 1.66, 1.0),
        Vec3(0.0, 1.9, 1.245),
        Vec3(0.0, 2.05, 1.41),
    )

/** Neck section by u: half width, crest, throat, top taper. */
val NECK =
    table(
        row(0.0, 0.22, 0.32, 0.36, 0.72),
        row(0.15, 0.2, 0.28, 0.35, 0.66),
        row(0.4, 0.155, 0.21, 0.23, 0.6),
        row(0.65, 0.12, 0.165, 0.15, 0.55),
        row(0.85, 0.104, 0.135, 0.14, 0.55),
        row(1.0, 0.098, 0.11, 0.13, 0.62),
    )

private val curve: CatmullRomCurve3 by lazy { CatmullRomCurve3(NECK_PTS, false, CatmullRomType.CENTRIPETAL) }

private val joints: DoubleArray by lazy {
    val points = REST.neck + listOf(REST.head)
    DoubleArray(points.size) { nearestU(neckCurve(), v(points[it])) }
}

/** Centre line of the neck (shared, built once). */
fun neckCurve(): CatmullRomCurve3 = curve

/** Curve parameters of the neck joints (neck1, neck2, neck3) and of the poll (head). */
fun neckJoints(): DoubleArray = joints

/** Positions of the mane bones along the neck (curve parameter). */
val MANE_U = doubleArrayOf(0.14, 0.32, 0.5, 0.68, 0.86)

/** Boundaries between the influence zones of neighbouring mane bones. */
val MANE_SPLIT = DoubleArray(MANE_U.size - 1) { (MANE_U[it + 1] + MANE_U[it]) / 2 }

/** Half width of the transition between two mane bones. */
val MANE_BLEND = (MANE_U[1] - MANE_U[0]) / 2

private val NECK_BONES = listOf("spineFront", "neck1", "neck2", "neck3", "head")

/** Bone that carries the neck at curve parameter u. */
private fun neckBoneAt(u: Double): String {
    val j = neckJoints()
    var k = 0
    while (k < j.size && u > j[k]) k++
    return NECK_BONES[k]
}

/** Anchor of a mane bone on the crest: its [name], [parent] bone, [position] (model space) and [quaternion]. */
class ManeAnchor(
    val name: String,
    val parent: String,
    val position: Vec3,
    val quaternion: Quat,
)

/**
 * Anchors of the mane bones on the crest. The bone frame has z along the neck, y towards the crest
 * and x sideways, so a rotation about z lifts the hair off the neck and a rotation about x swings
 * it along the neck.
 */
fun maneAnchors(): List<ManeAnchor> {
    val frames = curveFrames(neckCurve())
    return MANE_U.mapIndexed { i, u ->
        val f = frames(u)
        val crest = NECK(u)[1]
        val position = f.o.clone().addScaledVector(f.n, crest * 0.92)
        val m = Mat4().makeBasis(f.b, f.n, f.t)
        ManeAnchor("mane${i + 1}", neckBoneAt(u), position, Quat().setFromRotationMatrix(m))
    }
}
