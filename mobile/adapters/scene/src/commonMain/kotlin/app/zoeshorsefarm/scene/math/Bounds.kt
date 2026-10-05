package app.zoeshorsefarm.scene.math

import kotlin.math.max
import kotlin.math.sqrt

private val sphereTmp1 = Vec3()
private val sphereTmp2 = Vec3()

/** Plane `normal . p + constant = 0` (three.js `Plane`). */
class Plane(
    val normal: Vec3 = Vec3(1.0, 0.0, 0.0),
    var constant: Double = 0.0,
) {
    fun set(
        normal: Vec3,
        constant: Double,
    ): Plane {
        this.normal.copy(normal)
        this.constant = constant
        return this
    }

    fun setComponents(
        x: Double,
        y: Double,
        z: Double,
        w: Double,
    ): Plane {
        normal.set(x, y, z)
        constant = w
        return this
    }

    fun copy(p: Plane): Plane {
        normal.copy(p.normal)
        constant = p.constant
        return this
    }

    fun normalize(): Plane {
        val inverseNormalLength = 1.0 / normal.length()
        normal.multiplyScalar(inverseNormalLength)
        constant *= inverseNormalLength
        return this
    }

    fun distanceToPoint(point: Vec3): Double = normal.dot(point) + constant
}

/**
 * Bounding sphere (three.js `Sphere`). Mutable value type: [equals] and [hashCode] compare the
 * current values, so do not use an instance as a hash key while it is being mutated.
 */
class Sphere(
    val center: Vec3 = Vec3(),
    var radius: Double = -1.0,
) {
    fun set(
        center: Vec3,
        radius: Double,
    ): Sphere {
        this.center.copy(center)
        this.radius = radius
        return this
    }

    fun copy(s: Sphere): Sphere = set(s.center, s.radius)

    fun clone(): Sphere = Sphere(center.clone(), radius)

    fun isEmpty(): Boolean = radius < 0

    fun containsPoint(point: Vec3): Boolean = point.distanceToSquared(center) <= radius * radius

    fun distanceToPoint(point: Vec3): Double = point.distanceTo(center) - radius

    fun intersectsSphere(sphere: Sphere): Boolean {
        val radiusSum = radius + sphere.radius
        return sphere.center.distanceToSquared(center) <= radiusSum * radiusSum
    }

    fun applyMatrix4(matrix: Mat4): Sphere {
        center.applyMatrix4(matrix)
        radius *= matrix.getMaxScaleOnAxis()
        return this
    }

    fun translate(offset: Vec3): Sphere {
        center.add(offset)
        return this
    }

    fun makeEmpty(): Sphere {
        center.set(0.0, 0.0, 0.0)
        radius = -1.0
        return this
    }

    /** Grows the sphere just enough to contain `point`. */
    fun expandByPoint(point: Vec3): Sphere {
        if (isEmpty()) {
            center.copy(point)
            radius = 0.0
            return this
        }
        sphereTmp1.subVectors(point, center)
        val lengthSq = sphereTmp1.lengthSq()
        if (lengthSq > radius * radius) {
            val length = sqrt(lengthSq)
            val delta = (length - radius) * 0.5
            center.addScaledVector(sphereTmp1, delta / length)
            radius += delta
        }
        return this
    }

    /** Grows the sphere to contain `sphere`. */
    fun union(sphere: Sphere): Sphere {
        if (sphere.isEmpty()) return this
        if (isEmpty()) {
            copy(sphere)
            return this
        }
        if (center == sphere.center) {
            radius = max(radius, sphere.radius)
        } else {
            sphereTmp2.subVectors(sphere.center, center).setLength(sphere.radius)
            expandByPoint(sphereTmp1.copy(sphere.center).add(sphereTmp2))
            expandByPoint(sphereTmp1.copy(sphere.center).sub(sphereTmp2))
        }
        return this
    }

    override fun equals(other: Any?): Boolean = other is Sphere && other.center == center && other.radius == radius

    override fun hashCode(): Int = 31 * center.hashCode() + radius.hashCode()

    /** Smallest sphere around `points` (centre = box centre, like [Geometry.computeBoundingSphere]). */
    fun setFromPoints(points: List<Vec3>): Sphere {
        val box = Box3().setFromPoints(points)
        box.getCenter(center)
        var maxRadiusSq = 0.0
        for (p in points) maxRadiusSq = max(maxRadiusSq, center.distanceToSquared(p))
        radius = sqrt(maxRadiusSq)
        return this
    }

    override fun toString(): String = "Sphere($center, $radius)"
}

/** Axis-aligned box (three.js `Box3`). Starts empty (min > max). */
class Box3(
    val min: Vec3 = Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
    val max: Vec3 = Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
) {
    fun set(
        min: Vec3,
        max: Vec3,
    ): Box3 {
        this.min.copy(min)
        this.max.copy(max)
        return this
    }

    fun makeEmpty(): Box3 {
        min.set(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        max.set(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        return this
    }

    fun copy(b: Box3): Box3 = set(b.min, b.max)

    fun clone(): Box3 = Box3().copy(this)

    fun isEmpty(): Boolean = max.x < min.x || max.y < min.y || max.z < min.z

    fun setFromPoints(points: List<Vec3>): Box3 {
        makeEmpty()
        for (p in points) expandByPoint(p)
        return this
    }

    fun expandByPoint(point: Vec3): Box3 {
        min.min(point)
        max.max(point)
        return this
    }

    fun expandByScalar(scalar: Double): Box3 {
        min.addScalar(-scalar)
        max.addScalar(scalar)
        return this
    }

    fun getCenter(target: Vec3): Vec3 =
        if (isEmpty()) target.set(0.0, 0.0, 0.0) else target.addVectors(min, max).multiplyScalar(0.5)

    fun getSize(target: Vec3): Vec3 = if (isEmpty()) target.set(0.0, 0.0, 0.0) else target.subVectors(max, min)

    fun containsPoint(p: Vec3): Boolean =
        p.x >= min.x && p.x <= max.x && p.y >= min.y && p.y <= max.y && p.z >= min.z && p.z <= max.z

    fun intersectsBox(box: Box3): Boolean =
        box.max.x >= min.x && box.min.x <= max.x &&
            box.max.y >= min.y && box.min.y <= max.y &&
            box.max.z >= min.z && box.min.z <= max.z

    /** Box around the eight transformed corners. */
    fun applyMatrix4(matrix: Mat4): Box3 {
        if (isEmpty()) return this
        val corners = scratchCorners
        corners[0].set(min.x, min.y, min.z).applyMatrix4(matrix)
        corners[1].set(min.x, min.y, max.z).applyMatrix4(matrix)
        corners[2].set(min.x, max.y, min.z).applyMatrix4(matrix)
        corners[3].set(min.x, max.y, max.z).applyMatrix4(matrix)
        corners[4].set(max.x, min.y, min.z).applyMatrix4(matrix)
        corners[5].set(max.x, min.y, max.z).applyMatrix4(matrix)
        corners[6].set(max.x, max.y, min.z).applyMatrix4(matrix)
        corners[7].set(max.x, max.y, max.z).applyMatrix4(matrix)
        makeEmpty()
        for (c in corners) expandByPoint(c)
        return this
    }

    fun union(box: Box3): Box3 {
        min.min(box.min)
        max.max(box.max)
        return this
    }

    override fun toString(): String = "Box3($min, $max)"

    private companion object {
        val scratchCorners = Array(8) { Vec3() }
    }
}

/** View frustum of six planes (three.js `Frustum`, WebGL clip space). */
class Frustum {
    val planes: Array<Plane> = Array(6) { Plane() }

    fun copy(f: Frustum): Frustum {
        for (i in 0 until 6) planes[i].copy(f.planes[i])
        return this
    }

    /** `m` is `projection * viewMatrix` (camera.projectionMatrix * camera.matrixWorldInverse). */
    fun setFromProjectionMatrix(m: Mat4): Frustum {
        val me = m.e
        val me0 = me[0]
        val me1 = me[1]
        val me2 = me[2]
        val me3 = me[3]
        val me4 = me[4]
        val me5 = me[5]
        val me6 = me[6]
        val me7 = me[7]
        val me8 = me[8]
        val me9 = me[9]
        val me10 = me[10]
        val me11 = me[11]
        val me12 = me[12]
        val me13 = me[13]
        val me14 = me[14]
        val me15 = me[15]
        planes[0].setComponents(me3 - me0, me7 - me4, me11 - me8, me15 - me12).normalize()
        planes[1].setComponents(me3 + me0, me7 + me4, me11 + me8, me15 + me12).normalize()
        planes[2].setComponents(me3 + me1, me7 + me5, me11 + me9, me15 + me13).normalize()
        planes[3].setComponents(me3 - me1, me7 - me5, me11 - me9, me15 - me13).normalize()
        planes[4].setComponents(me3 - me2, me7 - me6, me11 - me10, me15 - me14).normalize()
        planes[5].setComponents(me3 + me2, me7 + me6, me11 + me10, me15 + me14).normalize()
        return this
    }

    fun intersectsSphere(sphere: Sphere): Boolean {
        val negRadius = -sphere.radius
        for (i in 0 until 6) {
            if (planes[i].distanceToPoint(sphere.center) < negRadius) return false
        }
        return true
    }

    fun intersectsBox(box: Box3): Boolean {
        for (i in 0 until 6) {
            val plane = planes[i]
            scratch.x = if (plane.normal.x > 0) box.max.x else box.min.x
            scratch.y = if (plane.normal.y > 0) box.max.y else box.min.y
            scratch.z = if (plane.normal.z > 0) box.max.z else box.min.z
            if (plane.distanceToPoint(scratch) < 0) return false
        }
        return true
    }

    fun containsPoint(point: Vec3): Boolean {
        for (i in 0 until 6) if (planes[i].distanceToPoint(point) < 0) return false
        return true
    }

    private companion object {
        val scratch = Vec3()
    }
}
