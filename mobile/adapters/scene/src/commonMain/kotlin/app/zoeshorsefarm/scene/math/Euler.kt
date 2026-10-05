package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/** Rotation order of an [Euler] (three.js: the string `'XYZ'` etc.). */
enum class EulerOrder { XYZ, YXZ, ZXY, ZYX, YZX, XZY }

/**
 * Mutable Euler angles in radians (three.js `Euler`). [onChange] fires after every change so a
 * node can keep its quaternion in sync.
 *
 * Mutable value type: [equals] and [hashCode] compare the current values, so do not use an
 * instance as a hash key while it is being mutated.
 */
class Euler(
    x: Double = 0.0,
    y: Double = 0.0,
    z: Double = 0.0,
    order: EulerOrder = DEFAULT_ORDER,
) {
    private var ex = x
    private var ey = y
    private var ez = z
    private var eorder = order

    internal var onChange: (() -> Unit)? = null

    var x: Double
        get() = ex
        set(value) {
            ex = value
            onChange?.invoke()
        }

    var y: Double
        get() = ey
        set(value) {
            ey = value
            onChange?.invoke()
        }

    var z: Double
        get() = ez
        set(value) {
            ez = value
            onChange?.invoke()
        }

    var order: EulerOrder
        get() = eorder
        set(value) {
            eorder = value
            onChange?.invoke()
        }

    fun set(
        x: Double,
        y: Double,
        z: Double,
        order: EulerOrder = eorder,
    ): Euler {
        ex = x
        ey = y
        ez = z
        eorder = order
        onChange?.invoke()
        return this
    }

    fun clone(): Euler = Euler(ex, ey, ez, eorder)

    fun copy(e: Euler): Euler = set(e.ex, e.ey, e.ez, e.eorder)

    /** Expects the upper 3x3 of `m` to be a pure rotation. `update = false` skips [onChange]. */
    @Suppress("LongMethod") // one branch per rotation order, as in three.js
    fun setFromRotationMatrix(
        m: Mat4,
        order: EulerOrder = eorder,
        update: Boolean = true,
    ): Euler {
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
        when (order) {
            EulerOrder.XYZ -> {
                ey = asin(MathUtils.clamp(m13, -1.0, 1.0))
                if (abs(m13) < 0.9999999) {
                    ex = atan2(-m23, m33)
                    ez = atan2(-m12, m11)
                } else {
                    ex = atan2(m32, m22)
                    ez = 0.0
                }
            }

            EulerOrder.YXZ -> {
                ex = asin(-MathUtils.clamp(m23, -1.0, 1.0))
                if (abs(m23) < 0.9999999) {
                    ey = atan2(m13, m33)
                    ez = atan2(m21, m22)
                } else {
                    ey = atan2(-m31, m11)
                    ez = 0.0
                }
            }

            EulerOrder.ZXY -> {
                ex = asin(MathUtils.clamp(m32, -1.0, 1.0))
                if (abs(m32) < 0.9999999) {
                    ey = atan2(-m31, m33)
                    ez = atan2(-m12, m22)
                } else {
                    ey = 0.0
                    ez = atan2(m21, m11)
                }
            }

            EulerOrder.ZYX -> {
                ey = asin(-MathUtils.clamp(m31, -1.0, 1.0))
                if (abs(m31) < 0.9999999) {
                    ex = atan2(m32, m33)
                    ez = atan2(m21, m11)
                } else {
                    ex = 0.0
                    ez = atan2(-m12, m22)
                }
            }

            EulerOrder.YZX -> {
                ez = asin(MathUtils.clamp(m21, -1.0, 1.0))
                if (abs(m21) < 0.9999999) {
                    ex = atan2(-m23, m22)
                    ey = atan2(-m31, m11)
                } else {
                    ex = 0.0
                    ey = atan2(m13, m33)
                }
            }

            EulerOrder.XZY -> {
                ez = asin(-MathUtils.clamp(m12, -1.0, 1.0))
                if (abs(m12) < 0.9999999) {
                    ex = atan2(m32, m22)
                    ey = atan2(m13, m11)
                } else {
                    ex = atan2(-m23, m33)
                    ey = 0.0
                }
            }
        }
        eorder = order
        if (update) onChange?.invoke()
        return this
    }

    fun setFromQuaternion(
        q: Quat,
        order: EulerOrder = eorder,
        update: Boolean = true,
    ): Euler {
        scratchMatrix.makeRotationFromQuaternion(q)
        return setFromRotationMatrix(scratchMatrix, order, update)
    }

    fun setFromVector3(
        v: Vec3,
        order: EulerOrder = eorder,
    ): Euler = set(v.x, v.y, v.z, order)

    fun reorder(newOrder: EulerOrder): Euler {
        scratchQuat.setFromEuler(this)
        return setFromQuaternion(scratchQuat, newOrder)
    }

    override fun equals(other: Any?): Boolean =
        other is Euler && other.ex == ex && other.ey == ey && other.ez == ez && other.eorder == eorder

    override fun hashCode(): Int = 31 * (31 * (31 * ex.hashCode() + ey.hashCode()) + ez.hashCode()) + eorder.hashCode()

    override fun toString(): String = "Euler($ex, $ey, $ez, $eorder)"

    companion object {
        val DEFAULT_ORDER = EulerOrder.XYZ

        private val scratchMatrix = Mat4()
        private val scratchQuat = Quat()
    }
}
