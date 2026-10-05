package app.zoeshorsefarm.render.filament.backend.mapping

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The `ACESFilmicToneMapping` of three.js (the Hill/Narkowicz fit with the 1/0.6 pre-exposure and the
 * sRGB to AP1 matrices) and its inverse, for colours that must come out of the tone mapper unchanged.
 *
 * three.js mixes the fog in after tone mapping, in output space; Filament mixes it into the HDR colour
 * before tone mapping. A fully fogged pixel is therefore `ACES(fogColor)` in Filament and `fogColor`
 * in three.js. Giving Filament `inverse(fogColor)` makes the two agree at full fog (and, in between,
 * keeps the scenery's own colours close: the mix then runs towards the same end point).
 */
object AcesInverse {
    // rows of the input and output matrices of three.js (its GLSL lists them by column)
    private val INPUT =
        doubleArrayOf(0.59719, 0.35458, 0.04823, 0.07600, 0.90834, 0.01566, 0.02840, 0.13383, 0.83777)
    private val OUTPUT =
        doubleArrayOf(1.60475, -0.53108, -0.07367, -0.10208, 1.10813, -0.00605, -0.00327, -0.07276, 1.07602)
    private val INPUT_INVERSE = invert(INPUT)
    private val OUTPUT_INVERSE = invert(OUTPUT)

    private const val PRE_EXPOSURE = 0.6
    private const val FIT_A = 0.0245786
    private const val FIT_B = 0.000090537
    private const val FIT_C = 0.983729
    private const val FIT_D = 0.4329510
    private const val FIT_E = 0.238081

    /** The largest output the inverse is asked for: the curve never reaches 1. */
    private const val MAX_OUTPUT = 0.995

    /** Linear colour in, linear display colour out (clamped to 0..1), like three.js at `exposure` 1. */
    fun forward(
        rgb: FloatArray,
        exposure: Double = 1.0,
    ): FloatArray {
        val c = multiply(INPUT, DoubleArray(3) { rgb[it] * exposure / PRE_EXPOSURE })
        val t = DoubleArray(3) { fit(c[it]) }
        val out = multiply(OUTPUT, t)
        return FloatArray(3) { min(1.0, max(0.0, out[it])).toFloat() }
    }

    /**
     * The HDR colour that [forward] maps to `rgb` (a display colour 0..1), for the given exposure.
     * Values above [MAX_OUTPUT] are cut: they would need an unbounded input.
     */
    fun inverse(
        rgb: FloatArray,
        exposure: Double = 1.0,
    ): FloatArray {
        val t = multiply(OUTPUT_INVERSE, DoubleArray(3) { min(MAX_OUTPUT, max(0.0, rgb[it].toDouble())) })
        val c = multiply(INPUT_INVERSE, DoubleArray(3) { invertFit(min(MAX_OUTPUT, max(0.0, t[it]))) })
        return FloatArray(3) { max(0.0, c[it] * PRE_EXPOSURE / exposure).toFloat() }
    }

    private fun fit(v: Double): Double = (v * (v + FIT_A) - FIT_B) / (v * (FIT_C * v + FIT_D) + FIT_E)

    /** Solves `fit(v) = y` for the positive root of `(y c - 1) v^2 + (y d - a) v + (y e + b) = 0`. */
    private fun invertFit(y: Double): Double {
        val qa = y * FIT_C - 1.0
        val qb = y * FIT_D - FIT_A
        val qc = y * FIT_E + FIT_B
        val discriminant = max(0.0, qb * qb - 4.0 * qa * qc)
        // qa is negative for y below 1 / c: the larger root is the one with the minus sign
        val root = (-qb - sqrt(discriminant)) / (2.0 * qa)
        return max(0.0, root)
    }

    private fun multiply(
        m: DoubleArray,
        v: DoubleArray,
    ): DoubleArray =
        doubleArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )

    private fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]
        val b = m[1]
        val c = m[2]
        val d = m[3]
        val e = m[4]
        val f = m[5]
        val g = m[6]
        val h = m[7]
        val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det,
            (c * h - b * i) / det,
            (b * f - c * e) / det,
            (f * g - d * i) / det,
            (a * i - c * g) / det,
            (c * d - a * f) / det,
            (d * h - e * g) / det,
            (b * g - a * h) / det,
            (a * e - b * d) / det,
        )
    }
}
