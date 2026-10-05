package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.scene.math.CatmullRomCurve3
import app.zoeshorsefarm.scene.math.CatmullRomType
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.EulerOrder
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.view3d.horse.Cap
import app.zoeshorsefarm.view3d.horse.Loft
import app.zoeshorsefarm.view3d.horse.LoftBuild
import app.zoeshorsefarm.view3d.horse.LoftDef
import app.zoeshorsefarm.view3d.horse.MeshBuilder
import app.zoeshorsefarm.view3d.horse.MeshData
import app.zoeshorsefarm.view3d.horse.OvalSection
import app.zoeshorsefarm.view3d.horse.chainWeights
import app.zoeshorsefarm.view3d.horse.curveFrames
import app.zoeshorsefarm.view3d.horse.ellipsoidData
import app.zoeshorsefarm.view3d.horse.oval
import app.zoeshorsefarm.view3d.horse.rigid
import app.zoeshorsefarm.view3d.horse.row
import app.zoeshorsefarm.view3d.horse.table
import app.zoeshorsefarm.view3d.horse.torusData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Detail geometry of the rider, merged into the one skinned rider mesh (no extra draw call):
// face (eyes, brows, nose, smile, ears, cheeks), helmet chin strap, ponytail with a hair bow, and
// jacket details (buttons, stock tie, collar tips, piping). All parts are weighted to existing
// bones; `level` (low / medium / high) only decides how much is added and how finely.

/** Head ellipsoid the face is mapped onto (matches the head part in Rider.kt). */
object RiderHead {
    const val CX = 0.0
    const val CY = 0.815
    const val CZ = 0.0
    const val RX = 0.083
    const val RY = 0.112
    const val RZ = 0.1
}

/** Ponytail bone chain (rider space, children of the head bone). */
object PonyJoints {
    val pony1 = doubleArrayOf(0.0, 0.795, -0.098)
    val pony2 = doubleArrayOf(0.0, 0.735, -0.138)
    val pony3 = doubleArrayOf(0.0, 0.665, -0.153)
}

private val PONY_END = doubleArrayOf(0.0, 0.6, -0.15)
private val PONY_STUB = doubleArrayOf(0.0, 0.815, -0.085)

/** Colours of the rider (linear rgb), see Rider.kt. */
class RiderColors(
    val jacket: DoubleArray,
    val breeches: DoubleArray,
    val boot: DoubleArray,
    val glove: DoubleArray,
    val helmet: DoubleArray,
    val leather: DoubleArray,
    val pupil: DoubleArray,
    val eyeWhite: DoubleArray,
    val iris: DoubleArray,
    val brow: DoubleArray,
    val skin: DoubleArray,
    val blush: DoubleArray,
    val nose: DoubleArray,
    val lip: DoubleArray,
    val strap: DoubleArray,
    val steel: DoubleArray,
    val hair: DoubleArray,
    val hairTip: DoubleArray,
    val bow: DoubleArray,
    val bowKnot: DoubleArray,
    val button: DoubleArray,
    val collar: DoubleArray,
    val piping: DoubleArray,
)

/** Segment counts per level: ellipsoid width/height, tube samples/radial, strap and tail. */
private class FaceDetail(
    val eye: IntArray,
    val small: IntArray,
    val tube: IntArray,
    val strap: IntArray,
    val tail: IntArray,
)

private fun pair(
    a: Int,
    b: Int,
) = intArrayOf(a, b)

private val FACE_LOW = FaceDetail(pair(4, 3), pair(4, 3), pair(4, 3), pair(6, 3), pair(4, 4))
private val FACE_MEDIUM = FaceDetail(pair(8, 6), pair(7, 5), pair(8, 4), pair(14, 4), pair(6, 7))
private val FACE_HIGH = FaceDetail(pair(10, 8), pair(9, 6), pair(12, 5), pair(20, 5), pair(8, 8))

private fun detailOf(level: GraphicsLevel): FaceDetail =
    when (level) {
        GraphicsLevel.LOW -> FACE_LOW
        GraphicsLevel.MEDIUM -> FACE_MEDIUM
        GraphicsLevel.HIGH -> FACE_HIGH
    }

private fun v3(a: DoubleArray) = Vec3(a[0], a[1], a[2])

/**
 * Point on (or, with off > 0, outside of) the head ellipsoid: lat = elevation (0 = equator, + up),
 * lon = azimuth (0 = straight ahead, + towards +X = the rider's left side).
 */
fun riderHeadPoint(
    lat: Double,
    lon: Double,
    off: Double = 0.0,
    out: Vec3 = Vec3(),
): Vec3 {
    val k = 1 + off
    return out.set(
        RiderHead.CX + RiderHead.RX * cos(lat) * sin(lon) * k,
        RiderHead.CY + RiderHead.RY * sin(lat) * k,
        RiderHead.CZ + RiderHead.RZ * cos(lat) * cos(lon) * k,
    )
}

private val tmpEuler = Euler()
private val tmpQuat = Quat()
private val tmpScale = Vec3(1.0, 1.0, 1.0)

private fun colorOf(c: DoubleArray): Map<String, DoubleArray> = mapOf("color" to c)

/** Ellipsoid (radii rx, ry, rz; z = outward) sitting on the head surface at (lat, lon). */
private fun surfaceBlob(
    b: MeshBuilder,
    bone: String,
    color: DoubleArray,
    radii: DoubleArray,
    lat: Double,
    lon: Double,
    seg: IntArray,
    off: Double = 0.0,
    push: Double = 0.0,
    roll: Double = 0.0,
) {
    val pos = riderHeadPoint(lat, lon, off)
    // local frame: z along the surface normal (approximated by lon / lat), roll about the normal
    tmpEuler.set(-lat * 0.85, lon, roll, EulerOrder.YXZ)
    tmpQuat.setFromEuler(tmpEuler)
    val m = Mat4().compose(pos, tmpQuat, tmpScale)
    if (push != 0.0) m.multiply(Mat4().makeTranslation(0.0, 0.0, push))
    val d = ellipsoidData(radii[0], radii[1], radii[2], seg[0], seg[1])
    val w = rigid(bone)
    val attrs = colorOf(color)
    b.addIndexed(d, m, { w }, { attrs })
}

/** Round tube along [points] with a radius table, all weighted to one bone. */
private fun simpleTube(
    b: MeshBuilder,
    bone: String,
    color: DoubleArray,
    points: List<Vec3>,
    radii: List<DoubleArray>,
    samples: Int,
    radial: Int,
    xAxis: Vec3,
) {
    val curve = CatmullRomCurve3(points, false, CatmullRomType.CENTRIPETAL)
    val rt = table(*radii.toTypedArray())
    val w = rigid(bone)
    val attrs = colorOf(color)
    Loft(
        LoftDef(
            frame = curveFrames(curve, xAxis),
            section = { u, a ->
                val r = rt(u)[0]
                oval(a, OvalSection(r, r, r))
            },
            weights = { _, _, _ -> w },
            attrs = { _, _, _, _, _ -> attrs },
        ),
    ).build(
        b,
        LoftBuild(
            List(samples + 1) { it.toDouble() / samples },
            radial,
            capStart = Cap(radii[0][1], 1),
            capEnd = Cap(radii[radii.size - 1][1], 1),
        ),
    )
}

/** Eyes, brows, nose, smile, ears (and blush on high). */
fun addFace(
    b: MeshBuilder,
    level: GraphicsLevel,
    c: RiderColors,
) {
    val d = detailOf(level)
    val low = level == GraphicsLevel.LOW
    for (s in listOf(1.0, -1.0)) {
        val lon = 0.36 * s
        if (low) {
            // one dark ellipsoid per eye
            surfaceBlob(b, "head", c.pupil, doubleArrayOf(0.0105, 0.0135, 0.006), -0.134, lon, d.eye, off = 0.02)
        } else {
            addEye(b, level, c, d, lon)
            surfaceBlob(
                b,
                "head",
                c.brow,
                doubleArrayOf(0.0165, 0.0032, 0.004),
                0.115,
                lon * 1.02,
                d.small,
                off = 0.012,
                roll = -0.12 * s,
            )
        }
        // ears (below the helmet rim; not on low, where they would cost more than they show)
        if (!low) {
            surfaceBlob(b, "head", c.skin, doubleArrayOf(0.0065, 0.022, 0.015), -0.12, 1.5 * s, d.small, off = 0.015)
        }
        if (level == GraphicsLevel.HIGH) {
            surfaceBlob(b, "head", c.blush, doubleArrayOf(0.014, 0.0095, 0.004), -0.52, 0.62 * s, d.small, off = 0.004)
        }
    }
    // nose
    surfaceBlob(b, "head", c.nose, doubleArrayOf(0.011, 0.0092, 0.011), -0.43, 0.0, d.small, off = 0.04)
    // smile: corners up, centre low
    val pts =
        listOf(-0.38, -0.19, 0.0, 0.19, 0.38).map { lon ->
            riderHeadPoint(-0.84 + 0.1 * (lon / 0.38).pow(2), lon, 0.014)
        }
    simpleTube(
        b,
        "head",
        c.lip,
        pts,
        listOf(row(0.0, 0.0022), row(0.5, 0.0034), row(1.0, 0.0022)),
        d.tube[0],
        d.tube[1],
        Vec3(0.0, 1.0, 0.0),
    )
}

/** White of the eye, iris, pupil (and a catch light on high) of the eye at [lon]. */
private fun addEye(
    b: MeshBuilder,
    level: GraphicsLevel,
    c: RiderColors,
    d: FaceDetail,
    lon: Double,
) {
    surfaceBlob(b, "head", c.eyeWhite, doubleArrayOf(0.0135, 0.0165, 0.0055), -0.134, lon, d.eye)
    surfaceBlob(b, "head", c.iris, doubleArrayOf(0.0092, 0.0118, 0.0045), -0.134, lon, d.eye, push = 0.0025)
    surfaceBlob(b, "head", c.pupil, doubleArrayOf(0.0055, 0.0075, 0.004), -0.134, lon, d.small, push = 0.0046)
    if (level == GraphicsLevel.HIGH) {
        // a small catch light makes the eyes look alive
        surfaceBlob(
            b,
            "head",
            c.eyeWhite,
            doubleArrayOf(0.0028, 0.0028, 0.0022),
            -0.134,
            lon,
            pair(5, 4),
            push = 0.0068,
        )
    }
}

/** Helmet harness: a strap from both temples under the chin (buckle on high). */
fun addChinStrap(
    b: MeshBuilder,
    level: GraphicsLevel,
    c: RiderColors,
) {
    val d = detailOf(level)

    fun side(s: Double) =
        listOf(
            riderHeadPoint(0.1, 1.25 * s, 0.04),
            riderHeadPoint(-0.25, 1.15 * s, 0.04),
            riderHeadPoint(-0.75, 0.85 * s, 0.04),
            riderHeadPoint(-1.15, 0.45 * s, 0.04),
        )
    val left = side(1.0)
    val right = side(-1.0).reversed()
    val pts = left + listOf(riderHeadPoint(-1.3, 0.0, 0.04)) + right
    simpleTube(
        b,
        "head",
        c.strap,
        pts,
        listOf(row(0.0, 0.0042), row(1.0, 0.0042)),
        d.strap[0],
        d.strap[1],
        Vec3(0.0, 0.0, 1.0),
    )
    if (level == GraphicsLevel.HIGH) {
        // small steel buckle on the left cheek
        val p = riderHeadPoint(-0.4, 1.0, 0.075)
        val data = ellipsoidData(0.0075, 0.0085, 0.004, 6, 4)
        val m = Mat4().compose(p, tmpQuat.setFromEuler(tmpEuler.set(0.0, 1.0, 0.0, EulerOrder.YXZ)), tmpScale)
        val w = rigid("head")
        val attrs = colorOf(c.steel)
        b.addIndexed(data, m, { w }, { attrs })
    }
}

/** Ponytail on the three pony bones, with a scrunchie (low) or a bow (medium, high). */
fun addPonytail(
    b: MeshBuilder,
    level: GraphicsLevel,
    c: RiderColors,
) {
    val d = detailOf(level)
    val pts =
        listOf(PONY_STUB, PonyJoints.pony1, PonyJoints.pony2, PonyJoints.pony3, PONY_END).map { v3(it) }
    val curve = CatmullRomCurve3(pts, false, CatmullRomType.CENTRIPETAL)
    val len = curve.getLength()
    val joints = ArrayList<Double>()
    var acc = 0.0
    for (i in 1 until pts.size - 1) {
        acc += pts[i].distanceTo(pts[i - 1])
        if (i >= 2) joints.add(acc / len)
    }
    val jointArray = joints.toDoubleArray()
    val rt = table(row(0.0, 0.026), row(0.25, 0.03), row(0.6, 0.024), row(1.0, 0.007))
    val bones = listOf("pony1", "pony2", "pony3")
    Loft(
        LoftDef(
            frame = curveFrames(curve),
            section = { u, a ->
                val r = rt(u)[0]
                oval(a, OvalSection(r, r * 0.85, r * 0.85))
            },
            weights = { u, _, _ -> chainWeights(u, jointArray, bones, 0.07) },
            attrs = { u, _, _, _, _ ->
                val t = maxOf(0.0, u - 0.3)
                colorOf(DoubleArray(3) { c.hair[it] + (c.hairTip[it] - c.hair[it]) * t * 1.4 })
            },
        ),
    ).build(
        b,
        LoftBuild(
            List(d.tail[0] + 1) { it.toDouble() / d.tail[0] },
            d.tail[1],
            capStart = Cap(0.02, 1),
            capEnd = Cap(0.02, 1),
        ),
    )
    addBow(b, level, c, d)
}

/** Scrunchie / bow at the root of the ponytail, facing backwards. */
private fun addBow(
    b: MeshBuilder,
    level: GraphicsLevel,
    c: RiderColors,
    d: FaceDetail,
) {
    val root = v3(PonyJoints.pony1).add(Vec3(0.0, -0.006, -0.012))
    val dir = v3(PonyJoints.pony2).sub(v3(PonyJoints.pony1)).normalize()
    val q = Quat().setFromUnitVectors(Vec3(0.0, 0.0, 1.0), dir)
    val low = level == GraphicsLevel.LOW
    val ring = torusData(0.03, 0.0115, if (low) 3 else if (d.small[1] > 3) 5 else 4, if (low) 5 else 9)
    val w = rigid("pony1")
    b.addIndexed(ring, Mat4().compose(root, q, tmpScale), { w }, { colorOf(c.bow) })
    if (low) return
    // bow: two loops and a knot, tilted away from the ponytail
    for (s in listOf(1.0, -1.0)) {
        val loop = ellipsoidData(0.03, 0.017, 0.011, d.small[0], d.small[1])
        val pos = root.clone().add(Vec3(0.03 * s, 0.006, -0.012))
        val rot = Quat().setFromEuler(Euler(0.2, 0.0, -0.45 * s))
        b.addIndexed(loop, Mat4().compose(pos, rot, tmpScale), { w }, { colorOf(c.bow) })
    }
    val knot = ellipsoidData(0.012, 0.012, 0.011, d.small[0], d.small[1])
    b.addIndexed(
        knot,
        Mat4().makeTranslation(root.x, root.y + 0.006, root.z - 0.016),
        { w },
        { colorOf(c.bowKnot) },
    )
}

/** Buttons, stock tie, collar tips (medium) and lapel piping (high) on the jacket front. */
fun addJacketDetails(
    b: MeshBuilder,
    torso: Loft,
    level: GraphicsLevel,
    c: RiderColors,
) {
    if (level == GraphicsLevel.LOW) return
    val front = -PI / 2 // 'down' side of the torso loft = front (+Z)

    fun part(
        data: MeshData,
        u: Double,
        a: Double,
        color: DoubleArray,
        quat: Quat? = null,
        lift: Double = 0.0025,
    ) {
        val pos = torso.point(u, a, lift)
        val w = torso.def.weights(u, a, pos)
        val attrs = colorOf(color)
        b.addIndexed(data, Mat4().compose(pos, quat ?: Quat(), tmpScale), { w }, { attrs })
    }
    val buttons = if (level == GraphicsLevel.HIGH) listOf(0.3, 0.4, 0.5, 0.6) else listOf(0.34, 0.47, 0.6)
    val seg = if (level == GraphicsLevel.HIGH) pair(8, 6) else pair(6, 4)
    for (u in buttons) part(ellipsoidData(0.0085, 0.0085, 0.0045, seg[0], seg[1]), u, front, c.button)
    // stock tie / shirt front at the neck
    part(ellipsoidData(0.011, 0.028, 0.007, seg[0], seg[1]), 0.9, front, c.collar)
    // collar tips
    for (s in listOf(1.0, -1.0)) {
        val tip = ellipsoidData(0.014, 0.0035, 0.01, seg[0], seg[1])
        val q = Quat().setFromEuler(Euler(0.35, 0.0, -0.45 * s))
        part(tip, 0.955, front + 0.55 * s, c.collar, q, 0.004)
    }
    if (level == GraphicsLevel.HIGH) addPiping(b, torso, c, front)
}

/** Contrasting piping along the lapel edges. */
private fun addPiping(
    b: MeshBuilder,
    torso: Loft,
    c: RiderColors,
    front: Double,
) {
    val attrs = colorOf(c.piping)
    for (s in listOf(1.0, -1.0)) {
        val pts = listOf(0.34, 0.5, 0.66, 0.82, 0.93).map { u -> torso.point(u, front + 0.3 * s, 0.002) }
        val curve = CatmullRomCurve3(pts, false, CatmullRomType.CENTRIPETAL)
        Loft(
            LoftDef(
                frame = curveFrames(curve, Vec3(0.0, 0.0, 1.0)),
                section = { _, a -> oval(a, OvalSection(0.0032, 0.0032, 0.0032)) },
                weights = { u, _, p -> torso.def.weights(0.34 + u * 0.59, front, p) },
                attrs = { _, _, _, _, _ -> attrs },
            ),
        ).build(
            b,
            LoftBuild(
                listOf(0.0, 0.25, 0.5, 0.75, 1.0),
                4,
                capStart = Cap(0.003, 1),
                capEnd = Cap(0.003, 1),
            ),
        )
    }
}
