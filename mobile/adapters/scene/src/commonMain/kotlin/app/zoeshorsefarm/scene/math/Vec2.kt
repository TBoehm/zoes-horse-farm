package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Mutable 2D vector (three.js `Vector2`). Methods mutate and return `this` so calls chain. */
class Vec2(
    var x: Double = 0.0,
    var y: Double = 0.0,
) {
    fun set(
        x: Double,
        y: Double,
    ): Vec2 {
        this.x = x
        this.y = y
        return this
    }

    fun setScalar(s: Double): Vec2 = set(s, s)

    fun copy(v: Vec2): Vec2 = set(v.x, v.y)

    fun clone(): Vec2 = Vec2(x, y)

    fun add(v: Vec2): Vec2 = set(x + v.x, y + v.y)

    fun addScalar(s: Double): Vec2 = set(x + s, y + s)

    fun addVectors(
        a: Vec2,
        b: Vec2,
    ): Vec2 = set(a.x + b.x, a.y + b.y)

    fun addScaledVector(
        v: Vec2,
        s: Double,
    ): Vec2 = set(x + v.x * s, y + v.y * s)

    fun sub(v: Vec2): Vec2 = set(x - v.x, y - v.y)

    fun subScalar(s: Double): Vec2 = set(x - s, y - s)

    fun subVectors(
        a: Vec2,
        b: Vec2,
    ): Vec2 = set(a.x - b.x, a.y - b.y)

    fun multiply(v: Vec2): Vec2 = set(x * v.x, y * v.y)

    fun multiplyScalar(s: Double): Vec2 = set(x * s, y * s)

    fun divide(v: Vec2): Vec2 = set(x / v.x, y / v.y)

    fun divideScalar(s: Double): Vec2 = multiplyScalar(1 / s)

    fun min(v: Vec2): Vec2 = set(min(x, v.x), min(y, v.y))

    fun max(v: Vec2): Vec2 = set(max(x, v.x), max(y, v.y))

    fun clamp(
        min: Vec2,
        max: Vec2,
    ): Vec2 = set(MathUtils.clamp(x, min.x, max.x), MathUtils.clamp(y, min.y, max.y))

    fun floor(): Vec2 = set(floor(x), floor(y))

    fun ceil(): Vec2 = set(ceil(x), ceil(y))

    fun round(): Vec2 = set(jsRound(x), jsRound(y))

    fun negate(): Vec2 = set(-x, -y)

    fun dot(v: Vec2): Double = x * v.x + y * v.y

    fun cross(v: Vec2): Double = x * v.y - y * v.x

    fun lengthSq(): Double = x * x + y * y

    fun length(): Double = sqrt(x * x + y * y)

    fun manhattanLength(): Double = abs(x) + abs(y)

    fun normalize(): Vec2 = divideScalar(length().let { if (it == 0.0) 1.0 else it })

    /** Angle in radians against the positive x axis, in `[0, 2 PI)`. */
    fun angle(): Double = atan2(-y, -x) + kotlin.math.PI

    fun distanceTo(v: Vec2): Double = sqrt(distanceToSquared(v))

    fun distanceToSquared(v: Vec2): Double {
        val dx = x - v.x
        val dy = y - v.y
        return dx * dx + dy * dy
    }

    fun setLength(length: Double): Vec2 = normalize().multiplyScalar(length)

    fun lerp(
        v: Vec2,
        alpha: Double,
    ): Vec2 = set(x + (v.x - x) * alpha, y + (v.y - y) * alpha)

    fun lerpVectors(
        a: Vec2,
        b: Vec2,
        alpha: Double,
    ): Vec2 = set(a.x + (b.x - a.x) * alpha, a.y + (b.y - a.y) * alpha)

    fun equals(v: Vec2): Boolean = v.x == x && v.y == y

    fun fromArray(
        array: DoubleArray,
        offset: Int = 0,
    ): Vec2 = set(array[offset], array[offset + 1])

    fun fromArray(
        array: FloatArray,
        offset: Int = 0,
    ): Vec2 = set(array[offset].toDouble(), array[offset + 1].toDouble())

    fun toArray(
        out: DoubleArray = DoubleArray(2),
        offset: Int = 0,
    ): DoubleArray {
        out[offset] = x
        out[offset + 1] = y
        return out
    }

    override fun toString(): String = "Vec2($x, $y)"
}
