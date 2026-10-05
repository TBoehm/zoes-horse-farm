package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.scene.geometry.DoubleBuf
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.FloatBuf
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.IntBuf
import app.zoeshorsefarm.scene.geometry.SphereGeometry
import app.zoeshorsefarm.scene.geometry.TorusGeometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.math.Curve3
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// Procedural geometry building blocks for horse and rider: lofts (tubes with a variable cross
// section along a path), shells (layers on a loft surface, e.g. mane, saddle, bridle) and a builder
// that merges all parts into ONE skinned Geometry (one draw call).

private const val TAU = PI * 2

/** The weight of a vertex on a bone (by name); a vertex has up to four of them. */
class BoneWeight(
    val bone: String,
    val weight: Double,
)

/** Shorthand: the weights of a vertex that belongs to one bone. */
fun rigid(bone: String): List<BoneWeight> = listOf(BoneWeight(bone, 1.0))

/** Extra vertex attributes (name -> values) of one vertex. */
typealias VertexAttrs = Map<String, DoubleArray>

/**
 * Oval cross section (superellipse) with separate upper/lower extent: half width [w], height
 * above ([up]) and below ([down]) the centre line, exponents [nUp]/[nDown] (2 = ellipse), width
 * factors at the top/bottom [upW]/[downW] and an offset [yOff] along the normal.
 */
class OvalSection(
    val w: Double,
    val up: Double,
    val down: Double,
    val nUp: Double = 2.0,
    val nDown: Double = 2.0,
    val upW: Double = 1.0,
    val downW: Double = 1.0,
    val yOff: Double = 0.0,
)

/**
 * Cross section point for angle [a] (0 = +b (lateral, left), pi/2 = +n (up/front)): returns
 * [x (along b), y (along n)] in [out].
 */
fun oval(
    a: Double,
    s: OvalSection,
    out: DoubleArray = DoubleArray(2),
): DoubleArray {
    val c = cos(a)
    val sn = sin(a)
    val upper = sn >= 0
    val ex = 2 / (if (upper) s.nUp else s.nDown)
    val cx = sign(c) * abs(c).pow(ex)
    val sy = sign(sn) * abs(sn).pow(ex)
    val s2 = sn * sn
    val wf = if (upper) lerp(1.0, s.upW, s2) else lerp(1.0, s.downW, s2)
    out[0] = s.w * cx * wf
    out[1] = (if (upper) s.up else s.down) * sy + s.yOff
    return out
}

/** A frame on a loft path: origin [o], tangent [t], normal [n] (up) and binormal [b] (lateral). */
class Frame(
    val o: Vec3,
    val t: Vec3,
    val n: Vec3,
    val b: Vec3,
)

private val X_AXIS = Vec3(1.0, 0.0, 0.0)

/** Frames along a curve in a sagittal plane: b = +X, n = t x b. */
fun curveFrames(
    curve: Curve3,
    xAxis: Vec3 = X_AXIS,
): (Double) -> Frame =
    fun(u: Double): Frame {
        val o = curve.getPointAt(clamp(u, 0.0, 1.0))
        val t = curve.getTangentAt(clamp(u, 0.0, 1.0)).normalize()
        val n = Vec3().crossVectors(t, xAxis).normalize()
        val b = Vec3().crossVectors(n, t).normalize()
        return Frame(o, t, n, b)
    }

/** Parameter u in [0, 1] of the point on a curve that is nearest to [point] (sampled). */
fun nearestU(
    curve: Curve3,
    point: Vec3,
    n: Int = 400,
): Double {
    var best = 0.0
    var bd = Double.POSITIVE_INFINITY
    val p = Vec3()
    for (i in 0..n) {
        curve.getPointAt(i.toDouble() / n, p)
        val d = p.distanceToSquared(point)
        if (d < bd) {
            bd = d
            best = i.toDouble() / n
        }
    }
    return best
}

/** Frames along a straight line from p0 to p1. */
fun lineFrames(
    p0: Vec3,
    p1: Vec3,
    xAxis: Vec3 = X_AXIS,
): (Double) -> Frame {
    val t = Vec3().subVectors(p1, p0).normalize()
    val n = Vec3().crossVectors(t, xAxis).normalize()
    val b = Vec3().crossVectors(n, t).normalize()
    return fun(u: Double): Frame = Frame(Vec3().lerpVectors(p0, p1, u), t, n, b)
}

/**
 * Definition of a loft: tube along `frame(u)`, u in [0, 1], cross section `section(u, a)` ->
 * [x, y]; `weights(u, a, p)` gives the bone weights of a vertex and `attrs(u, a, p, x, y)` extra
 * attributes per vertex.
 */
class LoftDef(
    val frame: (Double) -> Frame,
    val section: (Double, Double) -> DoubleArray,
    val weights: (Double, Double, Vec3) -> List<BoneWeight>,
    val attrs: ((Double, Double, Vec3, Double, Double) -> VertexAttrs?)? = null,
)

/** A rounded end of a loft: [len] long (m) with [rings] rings. */
class Cap(
    val len: Double,
    val rings: Int,
)

/** How a loft is turned into triangles: samples along the path, segments around, optional caps. */
class LoftBuild(
    val uSamples: List<Double>,
    val radial: Int,
    val capStart: Cap? = null,
    val capEnd: Cap? = null,
    val aOffset: Double = 0.0,
)

/** Loft: tube along a path with a variable cross section. */
class Loft(
    val def: LoftDef,
) {
    private val frameCache = HashMap<Long, Frame>()

    fun frame(u: Double): Frame {
        val key = floor(u * 1e6 + 0.5).toLong()
        return frameCache.getOrPut(key) { def.frame(u) }
    }

    fun point(
        u: Double,
        a: Double,
        offset: Double = 0.0,
        out: Vec3 = Vec3(),
    ): Vec3 {
        val f = frame(u)
        val xy = def.section(u, a)
        out.copy(f.o).addScaledVector(f.b, xy[0]).addScaledVector(f.n, xy[1])
        if (offset != 0.0) out.addScaledVector(normal(u, a), offset)
        return out
    }

    fun normal(
        u: Double,
        a: Double,
    ): Vec3 {
        val e = 1e-3
        val pu0 = point(clamp(u - e, 0.0, 1.0), a)
        val pu1 = point(clamp(u + e, 0.0, 1.0), a)
        val pa0 = point(u, a - e)
        val pa1 = point(u, a + e)
        val du = pu1.sub(pu0)
        val da = pa1.sub(pa0)
        val nrm = Vec3().crossVectors(da, du)
        if (nrm.lengthSq() < 1e-14) {
            val f = frame(u)
            val xy = def.section(u, a)
            nrm.copy(f.b).multiplyScalar(xy[0]).addScaledVector(f.n, xy[1])
        }
        nrm.normalize()
        // make it point outwards
        val f = frame(u)
        val p = point(u, a)
        if (nrm.dot(p.sub(f.o)) < 0) nrm.negate()
        return nrm
    }

    /** Emits the tube as one part into the [builder]. */
    fun build(
        builder: MeshBuilder,
        opts: LoftBuild,
    ) {
        val rings = ArrayList<IntArray>()
        val u0 = opts.uSamples[0]
        val uE = opts.uSamples[opts.uSamples.size - 1]
        var startPole = -1
        var endPole = -1
        if (opts.capStart != null) {
            startPole = pole(builder, u0, -opts.capStart.len)
            capRings(builder, rings, opts, opts.capStart, u0, -1.0, true)
        }
        for (u in opts.uSamples) rings.add(ring(builder, opts, u, 1.0, 0.0, 1.0))
        if (opts.capEnd != null) {
            capRings(builder, rings, opts, opts.capEnd, uE, 1.0, false)
            endPole = pole(builder, uE, opts.capEnd.len)
        }
        val radial = opts.radial
        for (i in 0 until rings.size - 1) {
            val r0 = rings[i]
            val r1 = rings[i + 1]
            for (j in 0 until radial) {
                val j1 = (j + 1) % radial
                builder.tri(r0[j], r0[j1], r1[j])
                builder.tri(r1[j], r0[j1], r1[j1])
            }
        }
        if (startPole >= 0) {
            val r = rings[0]
            for (j in 0 until radial) builder.tri(startPole, r[(j + 1) % radial], r[j])
        }
        if (endPole >= 0) {
            val r = rings[rings.size - 1]
            for (j in 0 until radial) builder.tri(endPole, r[j], r[(j + 1) % radial])
        }
    }

    /** The vertex at the tip of a cap, [shift] along the tangent from the centre of the end section. */
    private fun pole(
        builder: MeshBuilder,
        u: Double,
        shift: Double,
    ): Int {
        val f = frame(u)
        val top = def.section(u, PI / 2)
        val bottom = def.section(u, -PI / 2)
        val p =
            Vec3()
                .copy(f.o)
                .addScaledVector(f.n, (top[1] + bottom[1]) / 2)
                .addScaledVector(f.b, (top[0] + bottom[0]) / 2)
                .addScaledVector(f.t, shift)
        val attrs = def.attrs
        return builder.vertex(p, def.weights(u, 0.0, p), if (attrs != null) attrs(u, -1.0, p, 0.0, 0.0) else null)
    }

    /** One ring of vertices at [u], scaled by [scale] and moved by [shift] * [dirSign] along the tangent. */
    private fun ring(
        builder: MeshBuilder,
        opts: LoftBuild,
        u: Double,
        scale: Double,
        shift: Double,
        dirSign: Double,
    ): IntArray {
        val f = frame(u)
        val ids = IntArray(opts.radial)
        val attrs = def.attrs
        for (j in 0 until opts.radial) {
            val a = opts.aOffset + (TAU * j) / opts.radial
            val xy = def.section(u, a)
            val p =
                Vec3()
                    .copy(f.o)
                    .addScaledVector(f.b, xy[0] * scale)
                    .addScaledVector(f.n, xy[1] * scale)
                    .addScaledVector(f.t, shift * dirSign)
            ids[j] = builder.vertex(p, def.weights(u, a, p), if (attrs != null) attrs(u, a, p, xy[0], xy[1]) else null)
        }
        return ids
    }

    private fun capRings(
        builder: MeshBuilder,
        rings: MutableList<IntArray>,
        opts: LoftBuild,
        cap: Cap,
        u: Double,
        dirSign: Double,
        reverse: Boolean,
    ) {
        val list = ArrayList<DoubleArray>()
        for (k in 1..cap.rings) {
            val th = (k.toDouble() / (cap.rings + 1)) * (PI / 2)
            list.add(doubleArrayOf(cos(th), sin(th) * cap.len))
        }
        if (reverse) list.reverse()
        for (sc in list) rings.add(ring(builder, opts, u, sc[0], sc[1], dirSign))
    }
}

/** Options of [buildShell]. */
class ShellOptions(
    val nu: Int,
    val nv: Int,
    /** (su, sv) -> [u, a] on the loft. */
    val map: (Double, Double) -> DoubleArray,
    val thickness: (Double, Double) -> Double,
    val inset: Double = 0.002,
    /** Ring around the loft. */
    val closedV: Boolean = false,
    /** (su, sv, outer) -> extra attributes of a vertex. */
    val attrs: ((Double, Double, Boolean) -> VertexAttrs?)? = null,
    /** (u, a, point, su, sv) -> bone weights; by default those of the loft. */
    val weights: ((Double, Double, Vec3, Double, Double) -> List<BoneWeight>)? = null,
)

/**
 * Shell on a loft surface: grid (nu x nv) via map(su, sv) -> [u, a], outer surface offset by
 * thickness(su, sv), inner surface by inset; edges closed. closedV: ring around the loft.
 */
fun buildShell(
    builder: MeshBuilder,
    loft: Loft,
    opts: ShellOptions,
) {
    val grid = ShellGrid(builder, loft, opts)
    val quads = grid.quads()
    // outer surface must face away from the loft axis
    val mi = opts.nu / 2
    val jMax = grid.jMax
    val mj = jMax / 2
    val uc = opts.map(mi.toDouble() / opts.nu, mj.toDouble() / opts.nv)[0]
    val center = loft.frame(uc).o
    val outer = grid.outer
    val flip =
        builder.faceFacing(outer[mi][mj], outer[mi + 1][mj], outer[mi + 1][(mj + 1) % grid.cols], center) < 0
    for (q in quads) {
        if (flip) {
            builder.tri(q[0], q[2], q[1])
            builder.tri(q[0], q[3], q[2])
        } else {
            builder.tri(q[0], q[1], q[2])
            builder.tri(q[0], q[2], q[3])
        }
    }
}

/** The vertex grid of a shell (outer and inner surface) and the quads that join it. */
private class ShellGrid(
    builder: MeshBuilder,
    loft: Loft,
    private val opts: ShellOptions,
) {
    val cols = if (opts.closedV) opts.nv else opts.nv + 1
    val jMax = if (opts.closedV) cols else cols - 1
    val outer = ArrayList<IntArray>()
    private val inner = ArrayList<IntArray>()

    init {
        val attrsOf = opts.attrs
        val weightsOf = opts.weights
        for (i in 0..opts.nu) {
            val su = i.toDouble() / opts.nu
            val ro = IntArray(cols)
            val ri = IntArray(cols)
            for (j in 0 until cols) {
                val sv = j.toDouble() / opts.nv
                val ua = opts.map(su, sv)
                val u = ua[0]
                val a = ua[1]
                val base = loft.point(u, a)
                val nrm = loft.normal(u, a)
                val w = if (weightsOf != null) weightsOf(u, a, base, su, sv) else loft.def.weights(u, a, base)
                val th = opts.thickness(su, sv)
                val po = base.clone().addScaledVector(nrm, th)
                val pi = base.clone().addScaledVector(nrm, opts.inset)
                ro[j] = builder.vertex(po, w, if (attrsOf != null) attrsOf(su, sv, true) else null)
                ri[j] = builder.vertex(pi, w, if (attrsOf != null) attrsOf(su, sv, false) else null)
            }
            outer.add(ro)
            inner.add(ri)
        }
    }

    /** All quads: both surfaces, the edges at the ends and the sides (winding consistent with outer/inner). */
    fun quads(): List<IntArray> {
        val nu = opts.nu
        val quads = ArrayList<IntArray>()
        for (i in 0 until nu) {
            for (j in 0 until jMax) {
                val j1 = (j + 1) % cols
                quads.add(intArrayOf(outer[i][j], outer[i + 1][j], outer[i + 1][j1], outer[i][j1]))
                quads.add(intArrayOf(inner[i][j], inner[i][j1], inner[i + 1][j1], inner[i + 1][j]))
            }
        }
        for (j in 0 until jMax) {
            val j1 = (j + 1) % cols
            quads.add(intArrayOf(outer[0][j], outer[0][j1], inner[0][j1], inner[0][j]))
            quads.add(intArrayOf(outer[nu][j1], outer[nu][j], inner[nu][j], inner[nu][j1]))
        }
        if (!opts.closedV) {
            val c = cols - 1
            for (i in 0 until nu) {
                quads.add(intArrayOf(outer[i + 1][0], outer[i][0], inner[i][0], inner[i + 1][0]))
                quads.add(intArrayOf(outer[i][c], outer[i + 1][c], inner[i + 1][c], inner[i][c]))
            }
        }
        return quads
    }
}

/**
 * Collects the vertices of all parts. Each vertex: position, weights (bone names), attributes.
 * Normals are computed per part from its faces (smooth within a part).
 */
class MeshBuilder(
    private val boneIndex: Map<String, Int>,
    private val attrSpec: Map<String, Int> = emptyMap(),
) {
    private val pos = DoubleBuf(3 * 1024)
    private val skinIndex = IntBuf(4 * 1024)
    private val skinWeight = FloatBuf(4 * 1024)
    private val attrs: Map<String, FloatBuf> = attrSpec.keys.associateWith { FloatBuf(1024) }
    private val index = IntBuf(3 * 1024)
    private var current: VertexAttrs = emptyMap()

    /** Number of vertices so far. */
    val vertexCount: Int get() = pos.size / 3

    /** Number of triangles so far. */
    val triangleCount: Int get() = index.size / 3

    /** Sets default attribute values for the following parts. */
    fun setDefaults(values: VertexAttrs) {
        current = values
    }

    fun vertex(
        p: Vec3,
        weights: List<BoneWeight>?,
        vertexAttrs: VertexAttrs?,
    ): Int {
        val id = pos.size / 3
        pos.add(p.x, p.y, p.z)
        addSkin(weights)
        for ((name, size) in attrSpec) {
            val v = vertexAttrs?.get(name) ?: current[name]
            val buf = attrs.getValue(name)
            for (k in 0 until size) buf.add(if (v != null) v[k] else 0.0)
        }
        return id
    }

    /** Up to the four strongest weights, normalised; unused slots point at bone 0 with weight 0. */
    private fun addSkin(weights: List<BoneWeight>?) {
        val list = (weights ?: emptyList()).filter { it.weight > 1e-4 }.sortedByDescending { it.weight }.take(4)
        var sum = 0.0
        for (w in list) sum += w.weight
        for (k in 0 until 4) {
            val w = list.getOrNull(k)
            if (w != null) {
                val bi = boneIndex[w.bone] ?: error("Unknown bone ${w.bone}")
                skinIndex.add(bi)
                skinWeight.add(w.weight / sum)
            } else {
                skinIndex.add(0)
                skinWeight.add(0.0)
            }
        }
    }

    fun tri(
        a: Int,
        b: Int,
        c: Int,
    ) = index.add(a, b, c)

    /** Sign: does face (a, b, c) point away from [center]? */
    fun faceFacing(
        a: Int,
        b: Int,
        c: Int,
        center: Vec3,
    ): Double {
        val ax = pos[a * 3]
        val ay = pos[a * 3 + 1]
        val az = pos[a * 3 + 2]
        val e1x = pos[b * 3] - ax
        val e1y = pos[b * 3 + 1] - ay
        val e1z = pos[b * 3 + 2] - az
        val e2x = pos[c * 3] - ax
        val e2y = pos[c * 3 + 1] - ay
        val e2z = pos[c * 3 + 2] - az
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x
        return nx * (ax - center.x) + ny * (ay - center.y) + nz * (az - center.z)
    }

    /** Simple part from given local positions/indices with a transform. */
    fun addIndexed(
        data: MeshData,
        matrix: Mat4,
        weightsFn: (Vec3) -> List<BoneWeight>,
        attrsFn: ((Vec3) -> VertexAttrs?)? = null,
    ) {
        val v = Vec3()
        val p = data.p
        val base = IntArray(p.size / 3)
        for (i in base.indices) {
            v.set(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).applyMatrix4(matrix)
            base[i] = vertex(v, weightsFn(v), if (attrsFn != null) attrsFn(v) else null)
        }
        val idx = data.idx
        var i = 0
        while (i < idx.size) {
            tri(base[idx[i]], base[idx[i + 1]], base[idx[i + 2]])
            i += 3
        }
    }

    /** The skinned geometry: positions, skin indices/weights, extra attributes, index, smooth normals. */
    fun build(): Geometry {
        val geo = Geometry()
        val posF = FloatArray(pos.size) { pos[it].toFloat() }
        geo.setAttribute("position", FloatAttribute(posF, 3))
        geo.setAttribute("skinIndex", UShortAttribute(skinIndex.toIntArray(), 4))
        geo.setAttribute("skinWeight", skinWeight.toAttribute(4))
        for ((name, size) in attrSpec) geo.setAttribute(name, attrs.getValue(name).toAttribute(size))
        val idx = index.toIntArray()
        geo.setIndex(idx)
        geo.setAttribute("normal", FloatAttribute(computeNormals(posF, idx), 3))
        geo.computeBoundingSphere()
        return geo
    }
}

private fun accumulate(
    nrm: FloatArray,
    k: Int,
    nx: Double,
    ny: Double,
    nz: Double,
) {
    nrm[k] = (nrm[k] + nx).toFloat()
    nrm[k + 1] = (nrm[k + 1] + ny).toFloat()
    nrm[k + 2] = (nrm[k + 2] + nz).toFloat()
}

/** Smooth normals of the triangles, accumulated like the web code does (in float precision). */
private fun computeNormals(
    pos: FloatArray,
    index: IntArray,
): FloatArray {
    val nrm = FloatArray(pos.size)
    var i = 0
    while (i < index.size) {
        val a = index[i] * 3
        val b = index[i + 1] * 3
        val c = index[i + 2] * 3
        val e1x = pos[b].toDouble() - pos[a]
        val e1y = pos[b + 1].toDouble() - pos[a + 1]
        val e1z = pos[b + 2].toDouble() - pos[a + 2]
        val e2x = pos[c].toDouble() - pos[a]
        val e2y = pos[c + 1].toDouble() - pos[a + 1]
        val e2z = pos[c + 2].toDouble() - pos[a + 2]
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x
        accumulate(nrm, a, nx, ny, nz)
        accumulate(nrm, b, nx, ny, nz)
        accumulate(nrm, c, nx, ny, nz)
        i += 3
    }
    var j = 0
    while (j < nrm.size) {
        val len =
            sqrt(nrm[j] * nrm[j].toDouble() + nrm[j + 1] * nrm[j + 1].toDouble() + nrm[j + 2] * nrm[j + 2].toDouble())
        val l = if (len == 0.0) 1.0 else len
        nrm[j] = (nrm[j] / l).toFloat()
        nrm[j + 1] = (nrm[j + 1] / l).toFloat()
        nrm[j + 2] = (nrm[j + 2] / l).toFloat()
        j += 3
    }
    return nrm
}

private fun smooth(
    s: Double,
    joint: Double,
    b: Double,
): Double = smoothstep(joint - b, joint + b, s)

/**
 * Weights along a bone chain: joints = arc positions of the joints (ascending), bones =
 * [beforeJoint0, afterJoint0, afterJoint1, ...], [blend] = half transition width per joint.
 */
fun chainWeights(
    s: Double,
    joints: DoubleArray,
    bones: List<String>,
    blend: DoubleArray,
): List<BoneWeight> {
    val out = ArrayList<BoneWeight>(joints.size + 1)
    for (k in 0..joints.size) {
        val a = if (k == 0) 1.0 else smooth(s, joints[k - 1], blend[k - 1])
        val b = if (k < joints.size) smooth(s, joints[k], blend[k]) else 0.0
        out.add(BoneWeight(bones[k], maxOf(0.0, a - b)))
    }
    return out
}

/** Like the array variant with the same half transition width [blend] for every joint. */
fun chainWeights(
    s: Double,
    joints: DoubleArray,
    bones: List<String>,
    blend: Double,
): List<BoneWeight> = chainWeights(s, joints, bones, DoubleArray(joints.size) { blend })

/** Vertex positions (x, y, z per vertex) and triangle indices of a primitive. */
class MeshData(
    val p: DoubleArray,
    val idx: IntArray,
)

/** The positions and indices of a primitive geometry as [MeshData]. */
fun geometryData(g: Geometry): MeshData {
    val a = g.position.array
    return MeshData(DoubleArray(a.size) { a[it].toDouble() }, g.index ?: IntArray(0))
}

/** Ellipsoid part around the local origin (eyes etc.). */
fun ellipsoidData(
    rx: Double,
    ry: Double,
    rz: Double,
    ws: Int,
    hs: Int,
): MeshData {
    val g = SphereGeometry(1.0, ws, hs)
    g.scale(rx, ry, rz)
    return geometryData(g)
}

/** Torus part (bit ring, stirrup), local axis +Z. */
fun torusData(
    r: Double,
    tube: Double,
    rs: Int,
    ts: Int,
): MeshData = geometryData(TorusGeometry(r, tube, rs, ts))
