package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mutable quaternion (three.js `Quaternion`). Setting a component or calling a mutating method
 * fires [onChange], which a [app.zoeshorsefarm.scene.graph.Node] uses to keep its Euler angles in sync.
 *
 * Mutable value type: [equals] and [hashCode] compare the current values, so do not use an
 * instance as a hash key while it is being mutated.
 */
@Suppress("TooManyFunctions") // mirrors the three.js Quaternion API
class Quat(
    x: Double = 0.0,
    y: Double = 0.0,
    z: Double = 0.0,
    w: Double = 1.0,
) {
    private var qx = x
    private var qy = y
    private var qz = z
    private var qw = w

    /** Called after every change (null = nobody listens). */
    internal var onChange: (() -> Unit)? = null

    var x: Double
        get() = qx
        set(value) {
            qx = value
            onChange?.invoke()
        }

    var y: Double
        get() = qy
        set(value) {
            qy = value
            onChange?.invoke()
        }

    var z: Double
        get() = qz
        set(value) {
            qz = value
            onChange?.invoke()
        }

    var w: Double
        get() = qw
        set(value) {
            qw = value
            onChange?.invoke()
        }

    fun set(
        x: Double,
        y: Double,
        z: Double,
        w: Double,
    ): Quat {
        qx = x
        qy = y
        qz = z
        qw = w
        onChange?.invoke()
        return this
    }

    fun clone(): Quat = Quat(qx, qy, qz, qw)

    fun copy(q: Quat): Quat = set(q.qx, q.qy, q.qz, q.qw)

    /** `update = false` skips [onChange] (used by the node to avoid a feedback loop). */
    fun setFromEuler(
        euler: Euler,
        update: Boolean = true,
    ): Quat {
        val x = euler.x
        val y = euler.y
        val z = euler.z
        val c1 = cos(x / 2)
        val c2 = cos(y / 2)
        val c3 = cos(z / 2)
        val s1 = sin(x / 2)
        val s2 = sin(y / 2)
        val s3 = sin(z / 2)
        when (euler.order) {
            EulerOrder.XYZ -> {
                qx = s1 * c2 * c3 + c1 * s2 * s3
                qy = c1 * s2 * c3 - s1 * c2 * s3
                qz = c1 * c2 * s3 + s1 * s2 * c3
                qw = c1 * c2 * c3 - s1 * s2 * s3
            }

            EulerOrder.YXZ -> {
                qx = s1 * c2 * c3 + c1 * s2 * s3
                qy = c1 * s2 * c3 - s1 * c2 * s3
                qz = c1 * c2 * s3 - s1 * s2 * c3
                qw = c1 * c2 * c3 + s1 * s2 * s3
            }

            EulerOrder.ZXY -> {
                qx = s1 * c2 * c3 - c1 * s2 * s3
                qy = c1 * s2 * c3 + s1 * c2 * s3
                qz = c1 * c2 * s3 + s1 * s2 * c3
                qw = c1 * c2 * c3 - s1 * s2 * s3
            }

            EulerOrder.ZYX -> {
                qx = s1 * c2 * c3 - c1 * s2 * s3
                qy = c1 * s2 * c3 + s1 * c2 * s3
                qz = c1 * c2 * s3 - s1 * s2 * c3
                qw = c1 * c2 * c3 + s1 * s2 * s3
            }

            EulerOrder.YZX -> {
                qx = s1 * c2 * c3 + c1 * s2 * s3
                qy = c1 * s2 * c3 + s1 * c2 * s3
                qz = c1 * c2 * s3 - s1 * s2 * c3
                qw = c1 * c2 * c3 - s1 * s2 * s3
            }

            EulerOrder.XZY -> {
                qx = s1 * c2 * c3 - c1 * s2 * s3
                qy = c1 * s2 * c3 - s1 * c2 * s3
                qz = c1 * c2 * s3 + s1 * s2 * c3
                qw = c1 * c2 * c3 + s1 * s2 * s3
            }
        }
        if (update) onChange?.invoke()
        return this
    }

    fun setFromAxisAngle(
        axis: Vec3,
        angle: Double,
    ): Quat {
        val half = angle / 2
        val s = sin(half)
        return set(axis.x * s, axis.y * s, axis.z * s, cos(half))
    }

    /** Expects the upper 3x3 of `m` to be a pure rotation. */
    fun setFromRotationMatrix(m: Mat4): Quat {
        val te = m.e
        val m11 = te[0]
        val m12 = te[4]
        val m13 = te[8]
        val m21 = te[1]
        val m22 = te[5]
        val m23 = te[9]
        val m31 = te[2]
        val m32 = te[6]
        val m33 = te[10]
        val trace = m11 + m22 + m33
        if (trace > 0) {
            val s = 0.5 / sqrt(trace + 1.0)
            qw = 0.25 / s
            qx = (m32 - m23) * s
            qy = (m13 - m31) * s
            qz = (m21 - m12) * s
        } else if (m11 > m22 && m11 > m33) {
            val s = 2.0 * sqrt(1.0 + m11 - m22 - m33)
            qw = (m32 - m23) / s
            qx = 0.25 * s
            qy = (m12 + m21) / s
            qz = (m13 + m31) / s
        } else if (m22 > m33) {
            val s = 2.0 * sqrt(1.0 + m22 - m11 - m33)
            qw = (m13 - m31) / s
            qx = (m12 + m21) / s
            qy = 0.25 * s
            qz = (m23 + m32) / s
        } else {
            val s = 2.0 * sqrt(1.0 + m33 - m11 - m22)
            qw = (m21 - m12) / s
            qx = (m13 + m31) / s
            qy = (m23 + m32) / s
            qz = 0.25 * s
        }
        onChange?.invoke()
        return this
    }

    /** Both vectors must be normalised. */
    fun setFromUnitVectors(
        vFrom: Vec3,
        vTo: Vec3,
    ): Quat {
        var r = vFrom.dot(vTo) + 1
        if (r < 1e-8) {
            r = 0.0
            if (abs(vFrom.x) > abs(vFrom.z)) {
                qx = -vFrom.y
                qy = vFrom.x
                qz = 0.0
                qw = r
            } else {
                qx = 0.0
                qy = -vFrom.z
                qz = vFrom.y
                qw = r
            }
        } else {
            qx = vFrom.y * vTo.z - vFrom.z * vTo.y
            qy = vFrom.z * vTo.x - vFrom.x * vTo.z
            qz = vFrom.x * vTo.y - vFrom.y * vTo.x
            qw = r
        }
        return normalize()
    }

    fun angleTo(q: Quat): Double = 2 * acos(abs(MathUtils.clamp(dot(q), -1.0, 1.0)))

    fun rotateTowards(
        q: Quat,
        step: Double,
    ): Quat {
        val angle = angleTo(q)
        if (angle == 0.0) return this
        slerp(q, min(1.0, step / angle))
        return this
    }

    fun identity(): Quat = set(0.0, 0.0, 0.0, 1.0)

    fun invert(): Quat = conjugate()

    fun conjugate(): Quat {
        qx *= -1
        qy *= -1
        qz *= -1
        onChange?.invoke()
        return this
    }

    fun dot(v: Quat): Double = qx * v.qx + qy * v.qy + qz * v.qz + qw * v.qw

    fun lengthSq(): Double = qx * qx + qy * qy + qz * qz + qw * qw

    fun length(): Double = sqrt(lengthSq())

    fun normalize(): Quat {
        var l = length()
        if (l == 0.0) {
            qx = 0.0
            qy = 0.0
            qz = 0.0
            qw = 1.0
        } else {
            l = 1 / l
            qx *= l
            qy *= l
            qz *= l
            qw *= l
        }
        onChange?.invoke()
        return this
    }

    fun multiply(q: Quat): Quat = multiplyQuaternions(this, q)

    fun premultiply(q: Quat): Quat = multiplyQuaternions(q, this)

    fun multiplyQuaternions(
        a: Quat,
        b: Quat,
    ): Quat {
        val qax = a.qx
        val qay = a.qy
        val qaz = a.qz
        val qaw = a.qw
        val qbx = b.qx
        val qby = b.qy
        val qbz = b.qz
        val qbw = b.qw
        qx = qax * qbw + qaw * qbx + qay * qbz - qaz * qby
        qy = qay * qbw + qaw * qby + qaz * qbx - qax * qbz
        qz = qaz * qbw + qaw * qbz + qax * qby - qay * qbx
        qw = qaw * qbw - qax * qbx - qay * qby - qaz * qbz
        onChange?.invoke()
        return this
    }

    fun slerp(
        qb: Quat,
        t: Double,
    ): Quat {
        var x = qb.qx
        var y = qb.qy
        var z = qb.qz
        var w = qb.qw
        var dot = dot(qb)
        if (dot < 0) {
            x = -x
            y = -y
            z = -z
            w = -w
            dot = -dot
        }
        var s = 1 - t
        var tt = t
        if (dot < 0.9995) {
            val theta = acos(dot)
            val sinTheta = sin(theta)
            s = sin(s * theta) / sinTheta
            tt = sin(t * theta) / sinTheta
            qx = qx * s + x * tt
            qy = qy * s + y * tt
            qz = qz * s + z * tt
            qw = qw * s + w * tt
            onChange?.invoke()
        } else {
            qx = qx * s + x * tt
            qy = qy * s + y * tt
            qz = qz * s + z * tt
            qw = qw * s + w * tt
            normalize()
        }
        return this
    }

    fun slerpQuaternions(
        qa: Quat,
        qb: Quat,
        t: Double,
    ): Quat = copy(qa).slerp(qb, t)

    override fun equals(other: Any?): Boolean =
        other is Quat && other.qx == qx && other.qy == qy && other.qz == qz && other.qw == qw

    override fun hashCode(): Int = 31 * (31 * (31 * qx.hashCode() + qy.hashCode()) + qz.hashCode()) + qw.hashCode()

    fun fromArray(
        array: DoubleArray,
        offset: Int = 0,
    ): Quat = set(array[offset], array[offset + 1], array[offset + 2], array[offset + 3])

    fun toArray(
        out: DoubleArray = DoubleArray(4),
        offset: Int = 0,
    ): DoubleArray {
        out[offset] = qx
        out[offset + 1] = qy
        out[offset + 2] = qz
        out[offset + 3] = qw
        return out
    }

    override fun toString(): String = "Quat($qx, $qy, $qz, $qw)"
}
