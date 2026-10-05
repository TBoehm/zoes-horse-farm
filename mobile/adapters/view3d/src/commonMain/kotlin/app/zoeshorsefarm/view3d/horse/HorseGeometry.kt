package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.SphereGeometry
import app.zoeshorsefarm.scene.math.CatmullRomCurve3
import app.zoeshorsefarm.scene.math.CatmullRomType
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.ColorSpace
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

// Procedural horse geometry: torso, neck, head, legs, hooves, ears, eyes, mane, tail (one skinned
// geometry, "coat" material) and tack (second geometry, vertex colours).

private const val TAU = PI * 2

private fun v(a: DoubleArray) = Vec3(a[0], a[1], a[2])

private fun hash(i: Int): Double {
    val s = sin(i * 127.1 + 311.7) * 43758.5453
    return s - floor(s)
}

/** Segment counts per quality level: [rings along, segments around] of every part. */
class Detail(
    val torso: IntArray,
    val neck: IntArray,
    val head: IntArray,
    val leg: IntArray,
    val tail: IntArray,
    val mane: IntArray,
    val forelock: IntArray,
    val ear: IntArray,
    val eye: IntArray,
    val lid: IntArray,
    val wrap: IntArray,
    val hoof: IntArray,
    val pad: IntArray,
    val saddle: IntArray,
    val flap: IntArray,
    val band: IntArray,
    val cheek: IntArray,
    val ring: IntArray,
)

private fun n(
    rings: Int,
    around: Int,
) = intArrayOf(rings, around)

private val DETAIL_LOW =
    Detail(
        torso = n(20, 14),
        neck = n(9, 10),
        head = n(12, 10),
        leg = n(16, 7),
        tail = n(8, 6),
        mane = n(12, 2),
        forelock = n(3, 2),
        ear = n(4, 5),
        eye = n(6, 4),
        lid = n(0, 0),
        wrap = n(0, 0),
        hoof = n(2, 7),
        pad = n(6, 6),
        saddle = n(5, 6),
        flap = n(3, 3),
        band = n(1, 8),
        cheek = n(5, 1),
        ring = n(3, 6),
    )
private val DETAIL_MEDIUM =
    Detail(
        torso = n(36, 22),
        neck = n(16, 16),
        head = n(20, 16),
        leg = n(30, 11),
        tail = n(16, 9),
        mane = n(30, 3),
        forelock = n(6, 4),
        ear = n(6, 7),
        eye = n(10, 6),
        lid = n(8, 4),
        wrap = n(3, 12),
        hoof = n(3, 11),
        pad = n(10, 10),
        saddle = n(9, 10),
        flap = n(5, 4),
        band = n(1, 14),
        cheek = n(10, 1),
        ring = n(4, 10),
    )
private val DETAIL_HIGH =
    Detail(
        torso = n(56, 32),
        neck = n(26, 24),
        head = n(30, 24),
        leg = n(46, 15),
        tail = n(26, 14),
        mane = n(52, 4),
        forelock = n(9, 6),
        ear = n(8, 10),
        eye = n(14, 8),
        lid = n(12, 6),
        wrap = n(5, 18),
        hoof = n(4, 15),
        pad = n(16, 16),
        saddle = n(14, 14),
        flap = n(8, 6),
        band = n(2, 20),
        cheek = n(16, 2),
        ring = n(6, 14),
    )

/** The segment counts of a graphics level. */
fun detailOf(level: GraphicsLevel): Detail =
    when (level) {
        GraphicsLevel.LOW -> DETAIL_LOW
        GraphicsLevel.MEDIUM -> DETAIL_MEDIUM
        GraphicsLevel.HIGH -> DETAIL_HIGH
    }

private fun samples(
    n: Int,
    a: Double = 0.0,
    b: Double = 1.0,
): List<Double> = List(n + 1) { lerp(a, b, it.toDouble() / n) }

// ------------------------------------------------------------------------------------------
// Torso
// z, topline, underline, half width, height of the widest point (0 bottom .. 1 top),
// top taper, bottom taper, notch at the bottom between the legs
private val TORSO =
    table(
        row(-0.97, 1.43, 1.25, 0.08, 0.5, 0.75, 0.8, 0.0),
        row(-0.93, 1.52, 1.08, 0.165, 0.5, 0.78, 0.75, 0.08),
        row(-0.85, 1.585, 0.98, 0.235, 0.5, 0.8, 0.8, 0.16),
        row(-0.73, 1.635, 0.92, 0.27, 0.46, 0.78, 0.82, 0.19),
        row(-0.59, 1.665, 0.95, 0.28, 0.44, 0.74, 0.84, 0.12),
        row(-0.44, 1.66, 0.99, 0.275, 0.42, 0.68, 0.8, 0.04),
        row(-0.3, 1.63, 0.94, 0.285, 0.4, 0.66, 0.84, 0.0),
        row(-0.14, 1.595, 0.865, 0.298, 0.42, 0.62, 0.86, 0.0),
        row(0.02, 1.575, 0.84, 0.3, 0.42, 0.6, 0.86, 0.0),
        row(0.17, 1.578, 0.85, 0.29, 0.42, 0.55, 0.86, 0.0),
        row(0.31, 1.61, 0.885, 0.265, 0.42, 0.46, 0.86, 0.0),
        row(0.44, 1.655, 0.91, 0.24, 0.43, 0.36, 0.86, 0.0),
        row(0.57, 1.635, 0.94, 0.225, 0.44, 0.42, 0.85, 0.02),
        row(0.69, 1.56, 0.97, 0.225, 0.46, 0.5, 0.82, 0.04),
        row(0.79, 1.46, 1.0, 0.22, 0.48, 0.6, 0.8, 0.06),
        row(0.87, 1.41, 1.04, 0.2, 0.48, 0.7, 0.8, 0.06),
        row(0.93, 1.35, 1.08, 0.165, 0.46, 0.76, 0.8, 0.04),
        row(0.965, 1.3, 1.12, 0.1, 0.45, 0.8, 0.8, 0.0),
    )
private const val TZ0 = -0.97
private const val TZ1 = 0.965

/** Cross section of the torso at z: centre height, half width, height above/below, width factors, notch. */
private class TorsoSection(
    val cy: Double,
    val w: Double,
    val up: Double,
    val down: Double,
    val upW: Double,
    val downW: Double,
    val notch: Double,
)

private fun torsoSection(z: Double): TorsoSection {
    val r = TORSO(z)
    val top = r[0]
    val bottom = r[1]
    val m = r[3]
    val h = top - bottom
    return TorsoSection(bottom + m * h, r[2], (1 - m) * h, m * h, r[4], r[5], r[6])
}

private fun torsoWeights(p: Vec3): List<BoneWeight> {
    val x = p.x
    val y = p.y
    val z = p.z
    val wf = smoothstep(0.1, 0.42, z)
    val wr = 1 - smoothstep(-0.42, -0.1, z)
    val out = ArrayList<BoneWeight>(6)
    val sx = abs(x)
    val side = if (x >= 0) "L" else "R"
    val sideMask = smoothstep(0.03, 0.12, sx)
    // breathing (belly)
    val bel = (1 - smoothstep(0.98, 1.25, y)) * (1 - smoothstep(0.2, 0.42, abs(z + 0.05))) * 0.7
    // shoulder: scapula above, humerus below
    val es = ((y - 1.15) / 0.32).pow(2) + ((z - 0.66) / 0.27).pow(2)
    val fs = (1 - smoothstep(0.45, 1.0, es)) * sideMask
    val scap = 0.4 * fs * smoothstep(1.0, 1.28, y)
    val hum = 0.4 * fs * (1 - smoothstep(1.0, 1.22, y))
    // hindquarters: femur
    val eh = ((y - 1.12) / 0.3).pow(2) + ((z + 0.68) / 0.32).pow(2)
    val fh = (1 - smoothstep(0.4, 1.0, eh)) * sideMask
    val fem = fh * lerp(0.5, 0.0, smoothstep(0.95, 1.28, y))
    val infl = bel + scap + hum + fem
    val k = max(0.0, 1 - infl)
    out.add(BoneWeight("root", (1 - wf - wr) * k))
    out.add(BoneWeight("spineFront", wf * k))
    out.add(BoneWeight("spineRear", wr * k))
    if (bel > 0) out.add(BoneWeight("belly", bel))
    if (scap > 0) out.add(BoneWeight("${side}scapula", scap))
    if (hum > 0) out.add(BoneWeight("${side}humerus", hum))
    if (fem > 0) out.add(BoneWeight("${side}femur", fem))
    return out
}

private fun makeTorso(): Loft =
    Loft(
        LoftDef(
            frame = { u ->
                val z = lerp(TZ0, TZ1, u)
                val s = torsoSection(z)
                Frame(Vec3(0.0, s.cy, z), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
            },
            section = { u, a ->
                val s = torsoSection(lerp(TZ0, TZ1, u))
                val r = oval(a, OvalSection(s.w, s.up, s.down, nUp = 2.25, nDown = 2.1, upW = s.upW, downW = s.downW))
                if (r[1] < 0 && s.notch > 0) r[1] += s.notch * exp(-((r[0] / 0.08).pow(2))) * -sin(a)
                r
            },
            weights = { _, _, p -> torsoWeights(p) },
        ),
    )

// ------------------------------------------------------------------------------------------
// Neck and head
private val NECK_BONE_NAMES = listOf("spineFront", "neck1", "neck2", "neck3", "head")

private fun makeNeck(): Loft {
    val curve = neckCurve()
    val joints = neckJoints()
    val len = curve.getLength()
    val blend = doubleArrayOf(0.1 / len, 0.08 / len, 0.08 / len, 0.05 / len)
    return Loft(
        LoftDef(
            frame = curveFrames(curve),
            section = { u, a ->
                val r = NECK(u)
                oval(a, OvalSection(r[0], r[1], r[2], nUp = 2.2, nDown = 2.0, upW = r[3], downW = 0.82))
            },
            weights = { u, _, _ -> chainWeights(u, joints, NECK_BONE_NAMES, blend) },
        ),
    )
}

private const val HEAD_S0 = -0.04
private const val HEAD_S1 = 0.625

// s, half width, forehead side, jaw side
private val HEADT =
    table(
        row(-0.04, 0.085, 0.07, 0.11),
        row(0.0, 0.095, 0.085, 0.135),
        row(0.06, 0.113, 0.1, 0.17),
        row(0.14, 0.124, 0.108, 0.195),
        row(0.22, 0.118, 0.103, 0.198),
        row(0.3, 0.1, 0.09, 0.155),
        row(0.4, 0.084, 0.073, 0.106),
        row(0.49, 0.079, 0.066, 0.086),
        row(0.56, 0.086, 0.066, 0.084),
        row(0.6, 0.08, 0.056, 0.074),
        row(0.625, 0.068, 0.046, 0.064),
    )

private fun headS(u: Double) = lerp(HEAD_S0, HEAD_S1, u)

private fun headU(s: Double) = (s - HEAD_S0) / (HEAD_S1 - HEAD_S0)

private fun headSection(
    s: Double,
    a: Double,
): DoubleArray {
    val r = HEADT(s)
    return oval(a, OvalSection(r[0], r[1], r[2], nUp = 2.6, nDown = 1.8, upW = 0.86, downW = 0.62))
}

private fun makeHead(): Loft {
    val p0 = HEAD.origin.clone().addScaledVector(HEAD.dir, HEAD_S0)
    val p1 = HEAD.origin.clone().addScaledVector(HEAD.dir, HEAD_S1)
    val joint = doubleArrayOf(0.0)
    val bones = listOf("neck3", "head")
    return Loft(
        LoftDef(
            frame = lineFrames(p0, p1),
            section = { u, a -> headSection(headS(u), a) },
            weights = { u, _, _ -> chainWeights(headS(u), joint, bones, 0.045) },
            attrs = { u, a, _, x, _ -> mapOf("aFace" to doubleArrayOf(headS(u), x, sin(a))) },
        ),
    )
}

// ------------------------------------------------------------------------------------------
// Legs
// k (segment + fraction), half width, front, back
private val FRONT_SECT =
    table(
        row(0.0, 0.06, 0.05, 0.07),
        row(1.0, 0.075, 0.06, 0.1),
        row(1.5, 0.095, 0.09, 0.125),
        row(2.0, 0.095, 0.095, 0.12),
        row(2.13, 0.09, 0.095, 0.085),
        row(2.5, 0.07, 0.07, 0.06),
        row(2.88, 0.052, 0.048, 0.046),
        row(3.0, 0.053, 0.05, 0.05),
        row(3.1, 0.046, 0.042, 0.052),
        row(3.25, 0.037, 0.031, 0.046),
        row(3.6, 0.035, 0.03, 0.045),
        row(3.9, 0.039, 0.035, 0.047),
        row(4.0, 0.047, 0.044, 0.057),
        row(4.15, 0.045, 0.04, 0.05),
        row(4.5, 0.039, 0.035, 0.036),
        row(4.85, 0.046, 0.043, 0.041),
        row(5.0, 0.05, 0.047, 0.045),
    )
private val HIND_SECT =
    table(
        row(0.0, 0.06, 0.05, 0.07),
        row(1.0, 0.075, 0.06, 0.1),
        row(1.5, 0.1, 0.085, 0.17),
        row(2.0, 0.115, 0.09, 0.3),
        row(2.3, 0.105, 0.08, 0.23),
        row(2.6, 0.085, 0.064, 0.15),
        row(2.88, 0.058, 0.046, 0.08),
        row(3.0, 0.058, 0.05, 0.075),
        row(3.1, 0.05, 0.045, 0.06),
        row(3.3, 0.039, 0.033, 0.048),
        row(3.6, 0.036, 0.031, 0.046),
        row(3.9, 0.039, 0.035, 0.047),
        row(4.0, 0.047, 0.044, 0.057),
        row(4.15, 0.045, 0.04, 0.05),
        row(4.5, 0.039, 0.035, 0.036),
        row(4.85, 0.046, 0.043, 0.041),
        row(5.0, 0.05, 0.047, 0.045),
    )

private class LegChain(
    val chain: List<Vec3>,
    val hoof: Vec3,
)

private fun legChain(
    front: Boolean,
    side: Double,
): LegChain {
    val pts =
        if (front) {
            listOf(REST.front.shoulder, REST.front.elbow, REST.front.knee, REST.front.fetlock)
        } else {
            listOf(REST.hind.hip, REST.hind.stifle, REST.hind.hock, REST.hind.fetlock)
        }
    val hoofRest = if (front) REST.front.hoof else REST.hind.hoof
    val p = pts.map { Vec3(it[0] * side, it[1], it[2]) }
    val hoof = Vec3(hoofRest[0] * side, hoofRest[1], hoofRest[2])
    val top = p[0].clone().addScaledVector(p[0].clone().sub(p[1]), 0.15)
    val coronet = p[3].clone().lerp(hoof, 0.5)
    return LegChain(listOf(top) + p + listOf(coronet), hoof)
}

/** A leg loft with the curve parameters of its joints ([ju]) and the map from section position k to u. */
private class LegLoft(
    val loft: Loft,
    val kToU: (Double) -> Double,
)

private fun makeLeg(
    front: Boolean,
    side: Double,
): LegLoft {
    val chain = legChain(front, side).chain
    val curve = CatmullRomCurve3(chain, false, CatmullRomType.CENTRIPETAL)
    val ju = DoubleArray(chain.size) { nearestU(curve, chain[it]) }
    ju[0] = 0.0
    ju[ju.size - 1] = 1.0
    val len = curve.getLength()

    fun toK(u: Double): Double {
        var i = 0
        while (i < ju.size - 2 && u > ju[i + 1]) i++
        return i + clamp((u - ju[i]) / (ju[i + 1] - ju[i]), 0.0, 1.0)
    }

    // inverse of toK: section position k (segment + fraction) -> curve parameter
    fun kToU(k: Double): Double {
        val i = min(ju.size - 2, floor(k).toInt())
        return lerp(ju[i], ju[i + 1], clamp(k - i, 0.0, 1.0))
    }
    val p = if (side > 0) "L" else "R"
    val bones =
        if (front) {
            listOf("${p}scapula", "${p}humerus", "${p}forearm", "${p}fcannon", "${p}fpastern")
        } else {
            listOf("spineRear", "${p}femur", "${p}tibia", "${p}hcannon", "${p}hpastern")
        }
    val blendValues = if (front) doubleArrayOf(0.07, 0.05, 0.035, 0.03) else doubleArrayOf(0.08, 0.06, 0.04, 0.03)
    val blends = DoubleArray(4) { blendValues[it] / len }
    val sect = if (front) FRONT_SECT else HIND_SECT
    val joints = ju.copyOfRange(1, 5)
    val loft =
        Loft(
            LoftDef(
                frame = curveFrames(curve),
                section = { u, a ->
                    val r = sect(toK(u))
                    oval(a, OvalSection(r[0], r[1], r[2], nUp = 2.1, nDown = 2.1))
                },
                weights = { u, _, _ -> chainWeights(u, joints, bones, blends) },
            ),
        )
    return LegLoft(loft, ::kToU)
}

private val HOOF_ATTRS: VertexAttrs = mapOf("aMat" to doubleArrayOf(0.0, 1.0, 0.0, 0.0))

private fun addHoof(
    builder: MeshBuilder,
    front: Boolean,
    side: Double,
    nh: Int,
    radial: Int,
) {
    val hoof = legChain(front, side).hoof
    val p = if (side > 0) "L" else "R"
    val w = rigid(if (front) "${p}fpastern" else "${p}hpastern")
    val hh = 0.088

    fun ring(
        h: Double,
        scale: Double,
    ): IntArray {
        val t = h / hh
        val hw = lerp(0.063, 0.051, t) * (if (front) 1.0 else 0.94) * scale
        val zf = hoof.z + 0.066 - h / tan((if (front) 52 else 56) * (PI / 180))
        val zb = hoof.z - 0.056 - h * 0.12
        val cz = (zf + zb) / 2
        val hl = ((zf - zb) / 2) * scale
        return IntArray(radial) { j ->
            val a = (TAU * j) / radial
            val s = sin(a)
            // heels slightly drawn in
            val hlz = if (s >= 0) hl else hl * 0.92
            builder.vertex(Vec3(hoof.x + hw * cos(a), h, cz + hlz * s), w, HOOF_ATTRS)
        }
    }
    val rings = ArrayList<IntArray>()
    rings.add(ring(0.0, 0.88))
    rings.add(ring(0.004, 1.0))
    for (k in 1..nh) rings.add(ring((hh * k) / nh, 1.0))
    rings.add(ring(hh + 0.004, 0.8))
    val bottom = builder.vertex(Vec3(hoof.x, 0.0, hoof.z), w, HOOF_ATTRS)
    val top = builder.vertex(Vec3(hoof.x, hh + 0.01, hoof.z - 0.03), w, HOOF_ATTRS)
    for (i in 0 until rings.size - 1) {
        for (j in 0 until radial) {
            val j1 = (j + 1) % radial
            val r0 = rings[i]
            val r1 = rings[i + 1]
            builder.tri(r0[j], r1[j], r0[j1])
            builder.tri(r1[j], r1[j1], r0[j1])
        }
    }
    for (j in 0 until radial) {
        builder.tri(bottom, rings[0][j], rings[0][(j + 1) % radial])
        val rl = rings[rings.size - 1]
        builder.tri(top, rl[(j + 1) % radial], rl[j])
    }
}

// ------------------------------------------------------------------------------------------
// Eyelids (medium, high): an upper lid per eye, a skin-coloured hemispherical cap on its own bone.
// At rest the lid is open (pole up and back); closing turns it about the hinge axis (HorseView.kt).
private fun addEyelids(
    builder: MeshBuilder,
    d: Detail,
) {
    val r = 0.0265 // a little more than the longest radius of the eye (0.025)
    val cap = geometryData(SphereGeometry(r, d.lid[0], d.lid[1], 0.0, TAU, 0.0, PI / 2))
    val up = Vec3(0.0, 1.0, 0.0)
    for (side in SIDES) {
        val q = Quat().setFromUnitVectors(up, lidPole(side, Eye.OPEN_ANGLE))
        val m = Mat4().compose(Eye.center(side), q, Vec3(1.0, 1.0, 1.0))
        val lid = rigid(if (side > 0) "Llid" else "Rlid")
        builder.addIndexed(cap, m, { lid }, { LID_ATTRS })
    }
}

private val LID_ATTRS: VertexAttrs = mapOf("aMat" to doubleArrayOf(0.0, 0.0, 0.0, 0.0))

// ------------------------------------------------------------------------------------------
// Tail
private val TAIL_PTS =
    listOf(
        Vec3(0.0, 1.58, -0.8),
        Vec3(0.0, 1.5, -0.95),
        Vec3(0.0, 1.31, -1.045),
        Vec3(0.0, 1.04, -1.075),
        Vec3(0.0, 0.78, -1.065),
        Vec3(0.0, 0.55, -1.03),
    )
private val TAILT =
    table(
        row(0.0, 0.052),
        row(0.12, 0.056),
        row(0.26, 0.072),
        row(0.5, 0.098),
        row(0.75, 0.092),
        row(0.92, 0.066),
        row(1.0, 0.03),
    )
private val TAIL_BONES = listOf("spineRear", "tail1", "tail2", "tail3", "tail4", "tail5")
private val HAIR_ATTRS: VertexAttrs = mapOf("aMat" to doubleArrayOf(1.0, 0.0, 0.0, 0.0))

private fun makeTail(): Loft {
    val curve = CatmullRomCurve3(TAIL_PTS, false, CatmullRomType.CENTRIPETAL)
    val joints = DoubleArray(REST.tail.size) { nearestU(curve, v(REST.tail[it])) }
    val len = curve.getLength()
    return Loft(
        LoftDef(
            frame = curveFrames(curve),
            section = { u, a ->
                val r = TAILT(u)[0]
                val strands = 1 + smoothstep(0.15, 0.4, u) * (0.08 * sin(9 * a + 2 * u) + 0.05 * sin(17 * a + 5))
                doubleArrayOf(cos(a) * r * 0.86 * strands, sin(a) * r * strands)
            },
            weights = { u, _, _ -> chainWeights(u, joints, TAIL_BONES, 0.07 / len) },
            attrs = { _, _, _, _, _ -> HAIR_ATTRS },
        ),
    )
}

// ------------------------------------------------------------------------------------------

/** Builds the coat geometry (skinned, one draw call). */
fun buildBodyGeometry(
    boneIndex: Map<String, Int>,
    level: GraphicsLevel = GraphicsLevel.MEDIUM,
): Geometry {
    val d = detailOf(level)
    val b = MeshBuilder(boneIndex, mapOf("aMat" to 4, "aFace" to 3))
    b.setDefaults(mapOf("aMat" to doubleArrayOf(0.0, 0.0, 0.0, 0.0), "aFace" to doubleArrayOf(-1.0, 0.0, 0.0)))
    val neck = makeNeck()
    val head = makeHead()
    makeTorso().build(
        b,
        LoftBuild(samples(d.torso[0]), d.torso[1], Cap(0.035, 2), Cap(0.035, 2), PI / 2),
    )
    neck.build(b, LoftBuild(samples(d.neck[0]), d.neck[1], capStart = Cap(0.05, 1), aOffset = PI / 2))
    head.build(
        b,
        LoftBuild(samples(d.head[0]), d.head[1], Cap(0.02, 1), Cap(0.035, 2), PI / 2),
    )
    addLegs(b, d)
    addEars(b, d)
    addEyes(b, d)
    addMane(b, d, neck)
    addForelock(b, d, head)
    makeTail().build(
        b,
        LoftBuild(samples(d.tail[0]), d.tail[1], capStart = Cap(0.03, 1), capEnd = Cap(0.05, 1)),
    )
    val geo = b.build()
    geo.setAttribute("aRest", geo.position.clone())
    return geo
}

private fun addLegs(
    b: MeshBuilder,
    d: Detail,
) {
    for (front in listOf(true, false)) {
        for (side in SIDES) {
            makeLeg(front, side).loft.build(
                b,
                LoftBuild(samples(d.leg[0]), d.leg[1], capStart = Cap(0.04, 1), aOffset = PI / 2),
            )
            addHoof(b, front, side, d.hoof[0], d.hoof[1])
        }
    }
}

private fun addEars(
    b: MeshBuilder,
    d: Detail,
) {
    for (side in SIDES) {
        val base = Ear.base(side)
        val dir = Ear.dir(side)
        val tip = base.clone().addScaledVector(dir, Ear.LENGTH)
        val p0 = base.clone().addScaledVector(dir, -0.03)
        val bone = if (side > 0) "Lear" else "Rear"
        val bones = listOf("head", bone)
        val joint = doubleArrayOf(0.18)
        val ear =
            Loft(
                LoftDef(
                    frame = lineFrames(p0, tip),
                    section = { u, a ->
                        val prof = sin(PI * (0.18 + 0.82 * u)).pow(0.75) * (1 - 0.15 * u)
                        val back = 0.027 * prof
                        val front = 0.006 * prof
                        oval(a, OvalSection(0.043 * prof, back, front, nUp = 2.2, nDown = 1.6))
                    },
                    weights = { u, _, _ -> chainWeights(u, joint, bones, 0.08) },
                    attrs = { u, a, _, _, _ ->
                        val inner = if (a >= 0) 0.0 else smoothstep(0.2, 0.8, -sin(a)) * smoothstep(0.1, 0.3, u)
                        mapOf("aMat" to doubleArrayOf(0.0, 0.0, 0.0, inner))
                    },
                ),
            )
        ear.build(
            b,
            LoftBuild(
                samples(d.ear[0], 0.0, 0.97),
                d.ear[1] * 2,
                capStart = Cap(0.01, 1),
                capEnd = Cap(0.008, 1),
            ),
        )
    }
}

private val EYE_ATTRS: VertexAttrs = mapOf("aMat" to doubleArrayOf(0.0, 0.0, 1.0, 0.0))

/** Eyes (low: the lid is painted by the shader, see HorseMaterial.kt) and the eyelids. */
private fun addEyes(
    b: MeshBuilder,
    d: Detail,
) {
    val eye = ellipsoidData(Eye.radii[0], Eye.radii[1], Eye.radii[2], d.eye[0], d.eye[1])
    val head = rigid("head")
    for (side in SIDES) {
        val m = Mat4().makeRotationY(Eye.YAW * side).setPosition(headPoint(0.168, 0.03, 0.104 * side))
        b.addIndexed(eye, m, { head }, { EYE_ATTRS })
    }
    if (d.lid[0] > 0) addEyelids(b, d)
}

/**
 * Mane (lying to the right): the free part of the hair is skinned to its own bones, which swing on
 * springs (Life.kt); the root stays with the neck.
 */
private fun addMane(
    b: MeshBuilder,
    d: Detail,
    neck: Loft,
) {
    val mn = d.mane[0]
    val mv = d.mane[1]
    val maneBones = MANE_U.indices.map { "mane${it + 1}" }
    buildShell(
        b,
        neck,
        ShellOptions(
            nu = mn,
            nv = mv,
            map = { su, sv ->
                val row = floor(su * mn + 0.5).toInt()
                val u = lerp(0.07, 0.985, su)
                val jag = 0.12 * (hash(row) - 0.5) + 0.06 * sin(row * 1.7)
                val spread = (0.78 + jag) * lerp(0.55, 1.0, smoothstep(0.0, 0.12, su))
                doubleArrayOf(u, PI / 2 - 0.14 + sv * (spread + 0.14))
            },
            thickness = { su, sv ->
                (0.006 + 0.024 * (1 - sv) * (1 - 0.35 * sv)) * lerp(0.4, 1.0, smoothstep(0.0, 0.1, su))
            },
            inset = -0.004,
            attrs = { _, _, _ -> HAIR_ATTRS },
            weights = { u, a, p, _, sv ->
                val k = smoothstep(0.02, 0.75, sv)
                val rigid = neck.def.weights(u, a, p).map { BoneWeight(it.bone, it.weight * (1 - k)) }
                val swing =
                    chainWeights(
                        u,
                        MANE_SPLIT,
                        maneBones,
                        MANE_BLEND,
                    ).map { BoneWeight(it.bone, it.weight * k) }
                rigid + swing
            },
        ),
    )
}

/** Forelock: skinned to its own bone towards the free end. */
private fun addForelock(
    b: MeshBuilder,
    d: Detail,
    head: Loft,
) {
    val fn = d.forelock[0]
    val fv = d.forelock[1]
    buildShell(
        b,
        head,
        ShellOptions(
            nu = fn,
            nv = fv * 2,
            map = { su, sv ->
                val col = floor(sv * fv * 2 + 0.5).toInt()
                val sEnd = 0.1 + 0.025 * (hash(col + 7) - 0.5) - 0.03 * abs(sv - 0.5)
                doubleArrayOf(headU(lerp(-0.035, sEnd, su)), PI / 2 + (sv - 0.5) * 0.7)
            },
            thickness = { su, _ -> 0.003 + 0.009 * (1 - su * 0.7) },
            inset = -0.003,
            attrs = { _, _, _ -> HAIR_ATTRS },
            weights = { u, a, p, su, _ ->
                val k = smoothstep(0.0, 0.9, su)
                val rigid = head.def.weights(u, a, p).map { BoneWeight(it.bone, it.weight * (1 - k)) }
                rigid + BoneWeight("forelock", k)
            },
        ),
    )
}

// ------------------------------------------------------------------------------------------
// Tack

private class TackColors {
    val pad = lin(0.93, 0.93, 0.92)
    val trim = lin(0.08, 0.12, 0.3)
    val leather = lin(0.2, 0.11, 0.06)
    val leatherDark = lin(0.12, 0.07, 0.04)
    val girth = lin(0.1, 0.07, 0.05)
    val steel = lin(0.72, 0.73, 0.75)
    val brow = lin(0.85, 0.85, 0.88)
    val wrap = lin(0.9, 0.9, 0.87)
}

// Leg wraps (fleece bandages) on the cannon bones: section positions (see FRONT_SECT) and thickness
private const val WRAP_K0 = 3.12
private const val WRAP_K1 = 3.92
private const val WRAP_THICKNESS = 0.008

private fun lin(
    r: Double,
    g: Double,
    b: Double,
): DoubleArray {
    val col = Color().setRGB(r, g, b, ColorSpace.SRGB)
    return doubleArrayOf(col.r, col.g, col.b)
}

private fun tuOf(z: Double) = (z - TZ0) / (TZ1 - TZ0)

private fun colorAttrs(c: DoubleArray): VertexAttrs = mapOf("color" to c)

/** Builds the tack geometry: saddle pad, saddle, girth, bridle, leg wraps, bit rings (vertex colours). */
fun buildTackGeometry(
    boneIndex: Map<String, Int>,
    level: GraphicsLevel = GraphicsLevel.MEDIUM,
): Geometry {
    val d = detailOf(level)
    val b = MeshBuilder(boneIndex, mapOf("color" to 3))
    val c = TackColors()
    val torso = makeTorso()
    val head = makeHead()
    addSaddle(b, d, torso, c)
    addBridle(b, d, head, c)
    addLegWraps(b, d, c)
    // bit rings
    val ring = torusData(0.026, 0.0045, d.ring[0], d.ring[1])
    val headBone = rigid("head")
    for (side in SIDES) {
        val m = Mat4().makeRotationY(PI / 2).setPosition(bitRingPoint(side))
        b.addIndexed(ring, m, { headBone }, { colorAttrs(c.steel) })
    }
    return b.build()
}

private fun addSaddle(
    b: MeshBuilder,
    d: Detail,
    torso: Loft,
    c: TackColors,
) {
    // saddle pad
    val pn = d.pad[0]
    val pv = d.pad[1]
    buildShell(
        b,
        torso,
        ShellOptions(
            nu = pn,
            nv = pv,
            map = { su, sv ->
                val z = lerp(-0.25, 0.5, su)
                val e = max(0.0, abs(2 * su - 1) - 0.75) / 0.25
                val a = 1.42 * (1 - 0.12 * e * e) - 0.1 * su
                doubleArrayOf(tuOf(z), PI / 2 + (2 * sv - 1) * a)
            },
            thickness = { _, sv -> 0.014 + 0.005 * (1 - abs(2 * sv - 1)) },
            inset = 0.002,
            attrs = { su, sv, _ ->
                val edge = min(min(su, 1 - su), min(sv, 1 - sv))
                colorAttrs(if (edge < 0.035) c.trim else c.pad)
            },
        ),
    )

    // saddle: seat with pommel and cantle
    buildShell(
        b,
        torso,
        ShellOptions(
            nu = d.saddle[0],
            nv = d.saddle[1],
            map = { su, sv -> doubleArrayOf(tuOf(lerp(-0.13, 0.4, su)), PI / 2 + (2 * sv - 1) * 0.72) },
            thickness = { su, sv ->
                val cen = exp(-(((sv - 0.5) / 0.26).pow(2)))
                val cantle = 0.075 * (1 - smoothstep(0.0, 0.26, su)) * cen
                val pommel = 0.05 * smoothstep(0.72, 1.0, su) * cen
                0.02 + 0.028 * (1 - abs(2 * sv - 1).pow(2)) + cantle + pommel
            },
            inset = 0.016,
            attrs = { su, sv, _ ->
                colorAttrs(if (min(min(su, 1 - su), min(sv, 1 - sv)) < 0.06) c.leatherDark else c.leather)
            },
        ),
    )

    // saddle flaps (forward cut) left and right
    val fn = d.flap[0]
    val fv = d.flap[1]
    for (side in SIDES) {
        buildShell(
            b,
            torso,
            ShellOptions(
                nu = fn * 2,
                nv = fv * 2,
                map = { su, sv ->
                    val z0 = -0.05 + 0.12 * sv
                    val z1 = 0.33 + 0.15 * sv
                    val a = 0.55 + sv * 0.85
                    doubleArrayOf(tuOf(lerp(z0, z1, su)), if (side > 0) PI / 2 - a else PI / 2 + a)
                },
                thickness = { su, _ -> 0.03 + 0.016 * smoothstep(0.78, 1.0, su) },
                inset = 0.02,
                attrs = { su, sv, _ ->
                    colorAttrs(if (min(min(su, 1 - su), 1 - sv) < 0.05) c.leatherDark else c.leather)
                },
            ),
        )
    }

    // girth
    buildShell(
        b,
        torso,
        ShellOptions(
            nu = 1,
            nv = d.band[1] * 2,
            map = { su, sv -> doubleArrayOf(tuOf(lerp(0.3, 0.37, su)), PI / 2 + 1.3 + sv * (TAU - 2.6)) },
            thickness = { _, _ -> 0.014 },
            inset = 0.004,
            attrs = { _, _, _ -> colorAttrs(c.girth) },
        ),
    )
}

/** Bridle: noseband, crownpiece, browband, cheekpieces. */
private fun addBridle(
    b: MeshBuilder,
    d: Detail,
    head: Loft,
    c: TackColors,
) {
    fun ringBand(
        s0: Double,
        s1: Double,
        color: DoubleArray,
        th: Double = 0.009,
    ) = buildShell(
        b,
        head,
        ShellOptions(
            nu = d.band[0],
            nv = d.band[1],
            closedV = true,
            map = { su, sv -> doubleArrayOf(headU(lerp(s0, s1, su)), sv * TAU) },
            thickness = { _, _ -> th },
            inset = 0.002,
            attrs = { _, _, _ -> colorAttrs(color) },
        ),
    )
    ringBand(0.395, 0.432, c.leather, 0.011)
    ringBand(-0.012, 0.012, c.leather)
    buildShell(
        b,
        head,
        ShellOptions(
            nu = 1,
            nv = d.band[1],
            map = { su, sv -> doubleArrayOf(headU(lerp(0.04, 0.062, su)), 0.15 + sv * (PI - 0.3)) },
            thickness = { _, _ -> 0.009 },
            inset = 0.002,
            attrs = { _, _, _ -> colorAttrs(c.brow) },
        ),
    )
    val cn = d.cheek[0]
    val cv = d.cheek[1]
    for (side in SIDES) {
        buildShell(
            b,
            head,
            ShellOptions(
                nu = cn,
                nv = cv,
                map = { su, sv ->
                    val s = lerp(0.0, 0.575, su)
                    val ac = lerp(0.12, -0.42, su) + (sv - 0.5) * 0.2
                    doubleArrayOf(headU(s), if (side > 0) ac else PI - ac)
                },
                thickness = { _, _ -> 0.008 },
                inset = 0.002,
                attrs = { _, _, _ -> colorAttrs(c.leather) },
            ),
        )
    }
}

/** Leg wraps on all four cannon bones (medium, high; low paints them in the shader). */
private fun addLegWraps(
    b: MeshBuilder,
    d: Detail,
    c: TackColors,
) {
    val wn = d.wrap[0]
    val wv = d.wrap[1]
    if (wn <= 0) return
    for (front in listOf(true, false)) {
        for (side in SIDES) {
            val leg = makeLeg(front, side)
            buildShell(
                b,
                leg.loft,
                ShellOptions(
                    nu = wn,
                    nv = wv,
                    closedV = true,
                    map = { su, sv -> doubleArrayOf(leg.kToU(lerp(WRAP_K0, WRAP_K1, su)), sv * TAU) },
                    thickness = { su, _ -> WRAP_THICKNESS * (1 + 0.25 * abs(2 * su - 1).pow(4)) },
                    inset = 0.002,
                    attrs = { su, _, _ -> colorAttrs(if (su < 0.08 || su > 0.92) c.trim else c.wrap) },
                ),
            )
        }
    }
}

/** Bit ring (rein attachment), rest-pose model space. */
fun bitRingPoint(side: Double): Vec3 {
    val s = 0.585
    val w = HEADT(s)[0]
    return headPoint(s, -0.05, (w - 0.004) * side)
}

/** Rein resting point on the neck (no rider), rest-pose model space. */
fun reinRestPoint(side: Double): Vec3 = Vec3(0.11 * side, 1.66, 0.62)
