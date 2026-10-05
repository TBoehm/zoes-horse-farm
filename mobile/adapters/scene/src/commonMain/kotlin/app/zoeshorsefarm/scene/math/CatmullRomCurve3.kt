package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.pow

/** Base of the 3D curves (three.js `Curve` for Vector3 curves): arc-length parametrisation. */
abstract class Curve3 {
    var arcLengthDivisions: Int = 200

    /** Set to true after changing the control points so the arc lengths are recomputed. */
    var needsUpdate: Boolean = false

    private var cacheArcLengths: DoubleArray? = null

    /** Point at the curve parameter `t` in `[0, 1]` (not uniform in length). */
    abstract fun getPoint(
        t: Double,
        target: Vec3 = Vec3(),
    ): Vec3

    /** Point at the arc-length fraction `u` in `[0, 1]`. */
    fun getPointAt(
        u: Double,
        target: Vec3 = Vec3(),
    ): Vec3 = getPoint(getUtoTmapping(u), target)

    fun getPoints(divisions: Int = 5): List<Vec3> = List(divisions + 1) { getPoint(it.toDouble() / divisions) }

    fun getSpacedPoints(divisions: Int = 5): List<Vec3> = List(divisions + 1) { getPointAt(it.toDouble() / divisions) }

    fun getLength(): Double {
        val lengths = getLengths()
        return lengths[lengths.size - 1]
    }

    fun getLengths(divisions: Int = arcLengthDivisions): DoubleArray {
        val cached = cacheArcLengths
        if (cached != null && cached.size == divisions + 1 && !needsUpdate) return cached
        needsUpdate = false
        val cache = DoubleArray(divisions + 1)
        var last = getPoint(0.0)
        var sum = 0.0
        for (p in 1..divisions) {
            val current = getPoint(p.toDouble() / divisions)
            sum += current.distanceTo(last)
            cache[p] = sum
            last = current
        }
        cacheArcLengths = cache
        return cache
    }

    fun updateArcLengths() {
        needsUpdate = true
        getLengths()
    }

    /** Maps an arc-length fraction to the curve parameter. */
    fun getUtoTmapping(u: Double): Double {
        val arcLengths = getLengths()
        val il = arcLengths.size
        val targetArcLength = u * arcLengths[il - 1]
        var low = 0
        var high = il - 1
        var i: Int
        while (low <= high) {
            i = floor(low + (high - low) / 2.0).toInt()
            val comparison = arcLengths[i] - targetArcLength
            if (comparison < 0) {
                low = i + 1
            } else if (comparison > 0) {
                high = i - 1
            } else {
                high = i
                break
            }
        }
        i = high
        if (arcLengths[i] == targetArcLength) return i.toDouble() / (il - 1)
        val lengthBefore = arcLengths[i]
        val lengthAfter = arcLengths[i + 1]
        val segmentLength = lengthAfter - lengthBefore
        val segmentFraction = (targetArcLength - lengthBefore) / segmentLength
        return (i + segmentFraction) / (il - 1)
    }

    fun getTangent(
        t: Double,
        target: Vec3 = Vec3(),
    ): Vec3 {
        val delta = 0.0001
        var t1 = t - delta
        var t2 = t + delta
        if (t1 < 0) t1 = 0.0
        if (t2 > 1) t2 = 1.0
        val pt1 = getPoint(t1)
        val pt2 = getPoint(t2)
        return target.copy(pt2).sub(pt1).normalize()
    }

    fun getTangentAt(
        u: Double,
        target: Vec3 = Vec3(),
    ): Vec3 = getTangent(getUtoTmapping(u), target)

    /** Parallel-transport frames (tangents, normals, binormals) at `segments + 1` arc-length steps. */
    fun computeFrenetFrames(
        segments: Int,
        closed: Boolean = false,
    ): FrenetFrames {
        val normal = Vec3()
        val vec = Vec3()
        val mat = Mat4()
        val tangents = List(segments + 1) { getTangentAt(it.toDouble() / segments, Vec3()) }
        val normals = MutableList(segments + 1) { Vec3() }
        val binormals = MutableList(segments + 1) { Vec3() }
        var min = Double.MAX_VALUE
        val tx = abs(tangents[0].x)
        val ty = abs(tangents[0].y)
        val tz = abs(tangents[0].z)
        if (tx <= min) {
            min = tx
            normal.set(1.0, 0.0, 0.0)
        }
        if (ty <= min) {
            min = ty
            normal.set(0.0, 1.0, 0.0)
        }
        if (tz <= min) normal.set(0.0, 0.0, 1.0)
        vec.crossVectors(tangents[0], normal).normalize()
        normals[0].crossVectors(tangents[0], vec)
        binormals[0].crossVectors(tangents[0], normals[0])
        for (i in 1..segments) {
            normals[i] = normals[i - 1].clone()
            binormals[i] = binormals[i - 1].clone()
            vec.crossVectors(tangents[i - 1], tangents[i])
            if (vec.length() > EPSILON) {
                vec.normalize()
                val theta = acos(MathUtils.clamp(tangents[i - 1].dot(tangents[i]), -1.0, 1.0))
                normals[i].applyMatrix4(mat.makeRotationAxis(vec, theta))
            }
            binormals[i].crossVectors(tangents[i], normals[i])
        }
        if (closed) {
            var theta = acos(MathUtils.clamp(normals[0].dot(normals[segments]), -1.0, 1.0))
            theta /= segments
            if (tangents[0].dot(vec.crossVectors(normals[0], normals[segments])) > 0) theta = -theta
            for (i in 1..segments) {
                normals[i].applyMatrix4(mat.makeRotationAxis(tangents[i], theta * i))
                binormals[i].crossVectors(tangents[i], normals[i])
            }
        }
        return FrenetFrames(tangents, normals, binormals)
    }

    private companion object {
        const val EPSILON = 2.220446049250313e-16
    }
}

/** Result of [Curve3.computeFrenetFrames]. */
class FrenetFrames(
    val tangents: List<Vec3>,
    val normals: List<Vec3>,
    val binormals: List<Vec3>,
)

/** Interpolation flavour of a [CatmullRomCurve3] (three.js string `curveType`). */
enum class CatmullRomType { CENTRIPETAL, CHORDAL, CATMULLROM }

/** Spline through `points` (three.js `CatmullRomCurve3`); the game uses centripetal, open curves. */
class CatmullRomCurve3(
    var points: List<Vec3> = emptyList(),
    var closed: Boolean = false,
    var curveType: CatmullRomType = CatmullRomType.CENTRIPETAL,
    var tension: Double = 0.5,
) : Curve3() {
    override fun getPoint(
        t: Double,
        target: Vec3,
    ): Vec3 {
        val l = points.size
        val p = (l - (if (closed) 0 else 1)) * t
        var intPoint = floor(p).toInt()
        var weight = p - intPoint
        if (closed) {
            intPoint += if (intPoint > 0) 0 else (abs(intPoint) / l + 1) * l
        } else if (weight == 0.0 && intPoint == l - 1) {
            intPoint = l - 2
            weight = 1.0
        }
        val p0: Vec3
        val p3: Vec3
        if (closed || intPoint > 0) {
            p0 = points[floorMod(intPoint - 1, l)]
        } else {
            // extrapolate a phantom point before the start
            tmp2.subVectors(points[0], points[1]).add(points[0])
            p0 = tmp2
        }
        val p1 = points[intPoint % l]
        val p2 = points[(intPoint + 1) % l]
        if (closed || intPoint + 2 < l) {
            p3 = points[(intPoint + 2) % l]
        } else {
            tmp.subVectors(points[l - 1], points[l - 2]).add(points[l - 1])
            p3 = tmp
        }
        initPolynomials(p0, p1, p2, p3)
        return target.set(px.calc(weight), py.calc(weight), pz.calc(weight))
    }

    private fun initPolynomials(
        p0: Vec3,
        p1: Vec3,
        p2: Vec3,
        p3: Vec3,
    ) {
        if (curveType == CatmullRomType.CATMULLROM) {
            px.initCatmullRom(p0.x, p1.x, p2.x, p3.x, tension)
            py.initCatmullRom(p0.y, p1.y, p2.y, p3.y, tension)
            pz.initCatmullRom(p0.z, p1.z, p2.z, p3.z, tension)
            return
        }
        val pow = if (curveType == CatmullRomType.CHORDAL) 0.5 else 0.25
        val dt1 = p1.distanceToSquared(p2).pow(pow).let { if (it < 1e-4) 1.0 else it }
        val dt0 = p0.distanceToSquared(p1).pow(pow).let { if (it < 1e-4) dt1 else it }
        val dt2 = p2.distanceToSquared(p3).pow(pow).let { if (it < 1e-4) dt1 else it }
        px.initNonuniform(p0.x, p1.x, p2.x, p3.x, dt0, dt1, dt2)
        py.initNonuniform(p0.y, p1.y, p2.y, p3.y, dt0, dt1, dt2)
        pz.initNonuniform(p0.z, p1.z, p2.z, p3.z, dt0, dt1, dt2)
    }

    // JS `%` keeps the sign of the dividend; indices are never negative in practice (closed curves
    // add a multiple of `l` first), but floorMod keeps the lookup safe.
    private fun floorMod(
        a: Int,
        b: Int,
    ): Int = ((a % b) + b) % b

    private class CubicPoly {
        private var c0 = 0.0
        private var c1 = 0.0
        private var c2 = 0.0
        private var c3 = 0.0

        private fun init(
            x0: Double,
            x1: Double,
            t0: Double,
            t1: Double,
        ) {
            c0 = x0
            c1 = t0
            c2 = -3 * x0 + 3 * x1 - 2 * t0 - t1
            c3 = 2 * x0 - 2 * x1 + t0 + t1
        }

        fun initCatmullRom(
            x0: Double,
            x1: Double,
            x2: Double,
            x3: Double,
            tension: Double,
        ) {
            init(x1, x2, tension * (x2 - x0), tension * (x3 - x1))
        }

        fun initNonuniform(
            x0: Double,
            x1: Double,
            x2: Double,
            x3: Double,
            dt0: Double,
            dt1: Double,
            dt2: Double,
        ) {
            var t1 = (x1 - x0) / dt0 - (x2 - x0) / (dt0 + dt1) + (x2 - x1) / dt1
            var t2 = (x2 - x1) / dt1 - (x3 - x1) / (dt1 + dt2) + (x3 - x2) / dt2
            t1 *= dt1
            t2 *= dt1
            init(x1, x2, t1, t2)
        }

        fun calc(t: Double): Double {
            val t2 = t * t
            val t3 = t2 * t
            return c0 + c1 * t + c2 * t2 + c3 * t3
        }
    }

    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val px = CubicPoly()
    private val py = CubicPoly()
    private val pz = CubicPoly()
}
