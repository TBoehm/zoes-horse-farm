package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/** Mutable 3D vector (three.js `Vector3`). Methods mutate and return `this` so calls chain. */
class Vec3(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
) {
    fun set(
        x: Double,
        y: Double,
        z: Double,
    ): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun setScalar(s: Double): Vec3 = set(s, s, s)

    fun copy(v: Vec3): Vec3 = set(v.x, v.y, v.z)

    fun clone(): Vec3 = Vec3(x, y, z)

    fun add(v: Vec3): Vec3 = set(x + v.x, y + v.y, z + v.z)

    fun addScalar(s: Double): Vec3 = set(x + s, y + s, z + s)

    fun addVectors(
        a: Vec3,
        b: Vec3,
    ): Vec3 = set(a.x + b.x, a.y + b.y, a.z + b.z)

    fun addScaledVector(
        v: Vec3,
        s: Double,
    ): Vec3 = set(x + v.x * s, y + v.y * s, z + v.z * s)

    fun sub(v: Vec3): Vec3 = set(x - v.x, y - v.y, z - v.z)

    fun subScalar(s: Double): Vec3 = set(x - s, y - s, z - s)

    fun subVectors(
        a: Vec3,
        b: Vec3,
    ): Vec3 = set(a.x - b.x, a.y - b.y, a.z - b.z)

    fun multiply(v: Vec3): Vec3 = set(x * v.x, y * v.y, z * v.z)

    fun multiplyScalar(s: Double): Vec3 = set(x * s, y * s, z * s)

    fun multiplyVectors(
        a: Vec3,
        b: Vec3,
    ): Vec3 = set(a.x * b.x, a.y * b.y, a.z * b.z)

    fun applyEuler(euler: Euler): Vec3 = applyQuaternion(scratchQuat.setFromEuler(euler))

    fun applyAxisAngle(
        axis: Vec3,
        angle: Double,
    ): Vec3 = applyQuaternion(scratchQuat.setFromAxisAngle(axis, angle))

    fun applyMatrix3(m: Mat3): Vec3 {
        val e = m.e
        val vx = x
        val vy = y
        val vz = z
        return set(
            e[0] * vx + e[3] * vy + e[6] * vz,
            e[1] * vx + e[4] * vy + e[7] * vz,
            e[2] * vx + e[5] * vy + e[8] * vz,
        )
    }

    fun applyNormalMatrix(m: Mat3): Vec3 = applyMatrix3(m).normalize()

    /** Transforms as a point, including the perspective divide. */
    fun applyMatrix4(m: Mat4): Vec3 {
        val e = m.e
        val vx = x
        val vy = y
        val vz = z
        val w = 1 / (e[3] * vx + e[7] * vy + e[11] * vz + e[15])
        return set(
            (e[0] * vx + e[4] * vy + e[8] * vz + e[12]) * w,
            (e[1] * vx + e[5] * vy + e[9] * vz + e[13]) * w,
            (e[2] * vx + e[6] * vy + e[10] * vz + e[14]) * w,
        )
    }

    fun applyQuaternion(q: Quat): Vec3 {
        val vx = x
        val vy = y
        val vz = z
        val qx = q.x
        val qy = q.y
        val qz = q.z
        val qw = q.w
        val tx = 2 * (qy * vz - qz * vy)
        val ty = 2 * (qz * vx - qx * vz)
        val tz = 2 * (qx * vy - qy * vx)
        return set(
            vx + qw * tx + qy * tz - qz * ty,
            vy + qw * ty + qz * tx - qx * tz,
            vz + qw * tz + qx * ty - qy * tx,
        )
    }

    /** Transforms as a direction (no translation) and normalises. */
    fun transformDirection(m: Mat4): Vec3 {
        val e = m.e
        val vx = x
        val vy = y
        val vz = z
        return set(
            e[0] * vx + e[4] * vy + e[8] * vz,
            e[1] * vx + e[5] * vy + e[9] * vz,
            e[2] * vx + e[6] * vy + e[10] * vz,
        ).normalize()
    }

    fun divide(v: Vec3): Vec3 = set(x / v.x, y / v.y, z / v.z)

    fun divideScalar(s: Double): Vec3 = multiplyScalar(1 / s)

    fun min(v: Vec3): Vec3 = set(min(x, v.x), min(y, v.y), min(z, v.z))

    fun max(v: Vec3): Vec3 = set(max(x, v.x), max(y, v.y), max(z, v.z))

    fun clamp(
        min: Vec3,
        max: Vec3,
    ): Vec3 = set(MathUtils.clamp(x, min.x, max.x), MathUtils.clamp(y, min.y, max.y), MathUtils.clamp(z, min.z, max.z))

    fun clampScalar(
        minVal: Double,
        maxVal: Double,
    ): Vec3 =
        set(MathUtils.clamp(x, minVal, maxVal), MathUtils.clamp(y, minVal, maxVal), MathUtils.clamp(z, minVal, maxVal))

    fun clampLength(
        min: Double,
        max: Double,
    ): Vec3 {
        val length = length()
        return divideScalar(if (length == 0.0) 1.0 else length).multiplyScalar(MathUtils.clamp(length, min, max))
    }

    fun floor(): Vec3 = set(floor(x), floor(y), floor(z))

    fun ceil(): Vec3 = set(ceil(x), ceil(y), ceil(z))

    fun round(): Vec3 = set(jsRound(x), jsRound(y), jsRound(z))

    fun roundToZero(): Vec3 = set(truncate(x), truncate(y), truncate(z))

    fun negate(): Vec3 = set(-x, -y, -z)

    fun dot(v: Vec3): Double = x * v.x + y * v.y + z * v.z

    fun lengthSq(): Double = x * x + y * y + z * z

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun manhattanLength(): Double = abs(x) + abs(y) + abs(z)

    fun normalize(): Vec3 = divideScalar(length().let { if (it == 0.0) 1.0 else it })

    fun setLength(length: Double): Vec3 = normalize().multiplyScalar(length)

    fun lerp(
        v: Vec3,
        alpha: Double,
    ): Vec3 = set(x + (v.x - x) * alpha, y + (v.y - y) * alpha, z + (v.z - z) * alpha)

    fun lerpVectors(
        v1: Vec3,
        v2: Vec3,
        alpha: Double,
    ): Vec3 = set(v1.x + (v2.x - v1.x) * alpha, v1.y + (v2.y - v1.y) * alpha, v1.z + (v2.z - v1.z) * alpha)

    fun cross(v: Vec3): Vec3 = crossVectors(this, v)

    fun crossVectors(
        a: Vec3,
        b: Vec3,
    ): Vec3 {
        val ax = a.x
        val ay = a.y
        val az = a.z
        val bx = b.x
        val by = b.y
        val bz = b.z
        return set(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    }

    fun projectOnVector(v: Vec3): Vec3 {
        val denominator = v.lengthSq()
        if (denominator == 0.0) return set(0.0, 0.0, 0.0)
        val scalar = v.dot(this) / denominator
        return copy(v).multiplyScalar(scalar)
    }

    fun projectOnPlane(planeNormal: Vec3): Vec3 {
        scratchVec.copy(this).projectOnVector(planeNormal)
        return sub(scratchVec)
    }

    fun reflect(normal: Vec3): Vec3 = sub(scratchVec.copy(normal).multiplyScalar(2 * dot(normal)))

    fun angleTo(v: Vec3): Double {
        val denominator = sqrt(lengthSq() * v.lengthSq())
        if (denominator == 0.0) return kotlin.math.PI / 2
        val theta = dot(v) / denominator
        return acos(MathUtils.clamp(theta, -1.0, 1.0))
    }

    fun distanceTo(v: Vec3): Double = sqrt(distanceToSquared(v))

    fun distanceToSquared(v: Vec3): Double {
        val dx = x - v.x
        val dy = y - v.y
        val dz = z - v.z
        return dx * dx + dy * dy + dz * dz
    }

    fun setFromSphericalCoords(
        radius: Double,
        phi: Double,
        theta: Double,
    ): Vec3 {
        val sinPhiRadius = sin(phi) * radius
        return set(sinPhiRadius * sin(theta), cos(phi) * radius, sinPhiRadius * cos(theta))
    }

    fun setFromMatrixPosition(m: Mat4): Vec3 = set(m.e[12], m.e[13], m.e[14])

    fun setFromMatrixScale(m: Mat4): Vec3 =
        set(
            scratchVec.setFromMatrixColumn(m, 0).length(),
            scratchVec.setFromMatrixColumn(m, 1).length(),
            scratchVec.setFromMatrixColumn(m, 2).length(),
        )

    fun setFromMatrixColumn(
        m: Mat4,
        index: Int,
    ): Vec3 = fromArray(m.e, index * 4)

    fun setFromEuler(e: Euler): Vec3 = set(e.x, e.y, e.z)

    fun setFromColor(c: Color): Vec3 = set(c.r, c.g, c.b)

    fun equals(v: Vec3): Boolean = v.x == x && v.y == y && v.z == z

    fun fromArray(
        array: DoubleArray,
        offset: Int = 0,
    ): Vec3 = set(array[offset], array[offset + 1], array[offset + 2])

    fun fromArray(
        array: FloatArray,
        offset: Int = 0,
    ): Vec3 = set(array[offset].toDouble(), array[offset + 1].toDouble(), array[offset + 2].toDouble())

    fun toArray(
        out: DoubleArray = DoubleArray(3),
        offset: Int = 0,
    ): DoubleArray {
        out[offset] = x
        out[offset + 1] = y
        out[offset + 2] = z
        return out
    }

    override fun toString(): String = "Vec3($x, $y, $z)"

    private companion object {
        // Scratch objects: methods that need a temporary reuse them instead of allocating per call.
        val scratchQuat = Quat()
        val scratchVec = Vec3()
    }
}
