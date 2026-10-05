package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.math.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A 2D curve parametrised by `t` in `[0, 1]` (the parts of three.js `Curve` that shapes need). */
abstract class Curve2 {
    abstract fun getPoint(
        t: Double,
        target: Vec2 = Vec2(),
    ): Vec2

    open fun getPoints(divisions: Int = 5): List<Vec2> = List(divisions + 1) { getPoint(it.toDouble() / divisions) }

    /** How many divisions [Path.getPoints] spends on this curve, given the path's `divisions`. */
    open fun resolution(divisions: Int): Int = divisions
}

class LineCurve(
    val v1: Vec2 = Vec2(),
    val v2: Vec2 = Vec2(),
) : Curve2() {
    override fun getPoint(
        t: Double,
        target: Vec2,
    ): Vec2 {
        if (t == 1.0) {
            target.copy(v2)
        } else {
            target.copy(v2).sub(v1)
            target.multiplyScalar(t).add(v1)
        }
        return target
    }

    override fun resolution(divisions: Int): Int = 1
}

class QuadraticBezierCurve(
    val v0: Vec2,
    val v1: Vec2,
    val v2: Vec2,
) : Curve2() {
    override fun getPoint(
        t: Double,
        target: Vec2,
    ): Vec2 = target.set(bezier2(t, v0.x, v1.x, v2.x), bezier2(t, v0.y, v1.y, v2.y))

    private fun bezier2(
        t: Double,
        p0: Double,
        p1: Double,
        p2: Double,
    ): Double {
        val k = 1 - t
        return k * k * p0 + 2 * (1 - t) * t * p1 + t * t * p2
    }
}

class CubicBezierCurve(
    val v0: Vec2,
    val v1: Vec2,
    val v2: Vec2,
    val v3: Vec2,
) : Curve2() {
    override fun getPoint(
        t: Double,
        target: Vec2,
    ): Vec2 = target.set(bezier3(t, v0.x, v1.x, v2.x, v3.x), bezier3(t, v0.y, v1.y, v2.y, v3.y))

    private fun bezier3(
        t: Double,
        p0: Double,
        p1: Double,
        p2: Double,
        p3: Double,
    ): Double {
        val k = 1 - t
        return k * k * k * p0 + 3 * k * k * t * p1 + 3 * (1 - t) * t * t * p2 + t * t * t * p3
    }
}

class EllipseCurve(
    val aX: Double = 0.0,
    val aY: Double = 0.0,
    val xRadius: Double = 1.0,
    val yRadius: Double = 1.0,
    val aStartAngle: Double = 0.0,
    val aEndAngle: Double = PI * 2,
    val aClockwise: Boolean = false,
    val aRotation: Double = 0.0,
) : Curve2() {
    override fun getPoint(
        t: Double,
        target: Vec2,
    ): Vec2 {
        val twoPi = PI * 2
        var deltaAngle = aEndAngle - aStartAngle
        val samePoints = abs(deltaAngle) < EPSILON
        while (deltaAngle < 0) deltaAngle += twoPi
        while (deltaAngle > twoPi) deltaAngle -= twoPi
        if (deltaAngle < EPSILON) deltaAngle = if (samePoints) 0.0 else twoPi
        if (aClockwise && !samePoints) {
            deltaAngle = if (deltaAngle == twoPi) -twoPi else deltaAngle - twoPi
        }
        val angle = aStartAngle + t * deltaAngle
        var x = aX + xRadius * cos(angle)
        var y = aY + yRadius * sin(angle)
        if (aRotation != 0.0) {
            val c = cos(aRotation)
            val s = sin(aRotation)
            val tx = x - aX
            val ty = y - aY
            x = tx * c - ty * s + aX
            y = tx * s + ty * c + aY
        }
        return target.set(x, y)
    }

    override fun resolution(divisions: Int): Int = divisions * 2

    private companion object {
        const val EPSILON = 2.220446049250313e-16
    }
}

/** A sequence of curves drawn with moveTo/lineTo/curve calls (three.js `Path`). */
open class Path(
    points: List<Vec2>? = null,
) {
    val curves: MutableList<Curve2> = ArrayList()
    val currentPoint: Vec2 = Vec2()

    init {
        if (points != null) setFromPoints(points)
    }

    fun setFromPoints(points: List<Vec2>): Path {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        return this
    }

    fun moveTo(
        x: Double,
        y: Double,
    ): Path {
        currentPoint.set(x, y)
        return this
    }

    fun lineTo(
        x: Double,
        y: Double,
    ): Path {
        curves.add(LineCurve(currentPoint.clone(), Vec2(x, y)))
        currentPoint.set(x, y)
        return this
    }

    fun quadraticCurveTo(
        cpX: Double,
        cpY: Double,
        x: Double,
        y: Double,
    ): Path {
        curves.add(QuadraticBezierCurve(currentPoint.clone(), Vec2(cpX, cpY), Vec2(x, y)))
        currentPoint.set(x, y)
        return this
    }

    fun bezierCurveTo(
        cp1X: Double,
        cp1Y: Double,
        cp2X: Double,
        cp2Y: Double,
        x: Double,
        y: Double,
    ): Path {
        curves.add(CubicBezierCurve(currentPoint.clone(), Vec2(cp1X, cp1Y), Vec2(cp2X, cp2Y), Vec2(x, y)))
        currentPoint.set(x, y)
        return this
    }

    fun absarc(
        x: Double,
        y: Double,
        radius: Double,
        startAngle: Double,
        endAngle: Double,
        clockwise: Boolean = false,
    ) = absellipse(x, y, radius, radius, startAngle, endAngle, clockwise)

    fun absellipse(
        x: Double,
        y: Double,
        xRadius: Double,
        yRadius: Double,
        startAngle: Double,
        endAngle: Double,
        clockwise: Boolean = false,
        rotation: Double = 0.0,
    ): Path {
        val curve = EllipseCurve(x, y, xRadius, yRadius, startAngle, endAngle, clockwise, rotation)
        if (curves.isNotEmpty()) {
            val first = curve.getPoint(0.0)
            if (!first.equals(currentPoint)) lineTo(first.x, first.y)
        }
        curves.add(curve)
        currentPoint.copy(curve.getPoint(1.0))
        return this
    }

    /** Closes the path with a line back to the start unless it is closed already. */
    fun closePath(): Path {
        val start = curves[0].getPoint(0.0)
        val end = curves[curves.size - 1].getPoint(1.0)
        if (!start.equals(end)) curves.add(LineCurve(end, start))
        return this
    }

    /** Polyline of the whole path; consecutive duplicate points are skipped. */
    fun getPoints(divisions: Int = 12): List<Vec2> {
        val points = ArrayList<Vec2>()
        var last: Vec2? = null
        for (curve in curves) {
            val pts = curve.getPoints(curve.resolution(divisions))
            for (point in pts) {
                if (last?.equals(point) == true) continue
                points.add(point)
                last = point
            }
        }
        return points
    }
}

/** A closed outline with optional holes, for [ShapeGeometry] and [ExtrudeGeometry]. */
class Shape(
    points: List<Vec2>? = null,
) : Path(points) {
    val holes: MutableList<Path> = ArrayList()

    fun getPointsHoles(divisions: Int): List<List<Vec2>> = holes.map { it.getPoints(divisions) }
}

/** Shape helpers (three.js `ShapeUtils`). */
object ShapeUtils {
    fun area(contour: List<Vec2>): Double {
        val n = contour.size
        var a = 0.0
        var p = n - 1
        var q = 0
        while (q < n) {
            a += contour[p].x * contour[q].y - contour[q].x * contour[p].y
            p = q++
        }
        return a * 0.5
    }

    fun isClockWise(pts: List<Vec2>): Boolean = area(pts) < 0

    /** Triangle vertex indices into `contour + holes...` (earcut order). The lists are modified like in three.js. */
    fun triangulateShape(
        contour: MutableList<Vec2>,
        holes: List<MutableList<Vec2>>,
    ): List<IntArray> {
        val vertices = ArrayList<Double>()
        val holeIndices = ArrayList<Int>()
        removeDupEndPts(contour)
        addContour(vertices, contour)
        var holeIndex = contour.size
        holes.forEach { removeDupEndPts(it) }
        for (hole in holes) {
            holeIndices.add(holeIndex)
            holeIndex += hole.size
            addContour(vertices, hole)
        }
        val triangles = Earcut.triangulate(vertices.toDoubleArray(), holeIndices.toIntArray())
        return List(triangles.size / 3) { intArrayOf(triangles[it * 3], triangles[it * 3 + 1], triangles[it * 3 + 2]) }
    }

    private fun removeDupEndPts(points: MutableList<Vec2>) {
        val l = points.size
        if (l > 2 && points[l - 1].equals(points[0])) points.removeAt(l - 1)
    }

    private fun addContour(
        vertices: MutableList<Double>,
        contour: List<Vec2>,
    ) {
        for (p in contour) {
            vertices.add(p.x)
            vertices.add(p.y)
        }
    }
}
