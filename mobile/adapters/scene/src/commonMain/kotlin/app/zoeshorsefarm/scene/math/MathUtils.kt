package app.zoeshorsefarm.scene.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Port of three.js `MathUtils` (the parts the game uses). */
object MathUtils {
    const val DEG2RAD: Double = PI / 180.0
    const val RAD2DEG: Double = 180.0 / PI

    private val LN2 = ln(2.0)

    fun clamp(
        value: Double,
        min: Double,
        max: Double,
    ): Double = max(min, min(max, value))

    fun clamp(
        value: Int,
        min: Int,
        max: Int,
    ): Int = max(min, min(max, value))

    /** Modulo that always returns a value in `[0, m)` for positive `m`. */
    fun euclideanModulo(
        n: Double,
        m: Double,
    ): Double = ((n % m) + m) % m

    fun mapLinear(
        x: Double,
        a1: Double,
        a2: Double,
        b1: Double,
        b2: Double,
    ): Double = b1 + (x - a1) * (b2 - b1) / (a2 - a1)

    fun inverseLerp(
        x: Double,
        y: Double,
        value: Double,
    ): Double = if (x != y) (value - x) / (y - x) else 0.0

    fun lerp(
        x: Double,
        y: Double,
        t: Double,
    ): Double = (1 - t) * x + t * y

    fun damp(
        x: Double,
        y: Double,
        lambda: Double,
        dt: Double,
    ): Double = lerp(x, y, 1 - exp(-lambda * dt))

    fun pingpong(
        x: Double,
        length: Double = 1.0,
    ): Double = length - abs(euclideanModulo(x, length * 2) - length)

    fun smoothstep(
        x: Double,
        min: Double,
        max: Double,
    ): Double {
        if (x <= min) return 0.0
        if (x >= max) return 1.0
        val t = (x - min) / (max - min)
        return t * t * (3 - 2 * t)
    }

    fun smootherstep(
        x: Double,
        min: Double,
        max: Double,
    ): Double {
        if (x <= min) return 0.0
        if (x >= max) return 1.0
        val t = (x - min) / (max - min)
        return t * t * t * (t * (t * 6 - 15) + 10)
    }

    fun degToRad(degrees: Double): Double = degrees * DEG2RAD

    fun radToDeg(radians: Double): Double = radians * RAD2DEG

    fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    fun ceilPowerOfTwo(value: Double): Double = 2.0.pow(ceil(ln(value) / LN2))

    fun ceilPowerOfTwo(value: Int): Int = ceilPowerOfTwo(value.toDouble()).toInt()

    fun floorPowerOfTwo(value: Double): Double = 2.0.pow(floor(ln(value) / LN2))

    fun floorPowerOfTwo(value: Int): Int = floorPowerOfTwo(value.toDouble()).toInt()
}

/** JavaScript `Math.round`: halves round towards positive infinity. */
internal fun jsRound(x: Double): Double = floor(x + 0.5)
