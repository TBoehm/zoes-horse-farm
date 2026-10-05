package app.zoeshorsefarm.render.filament.material

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Float literals for generated shader code, and for the keys that name it. */
object GlslNumber {
    /**
     * `toFixed(decimals)` of JavaScript (three decimals by default): always with a decimal point, so
     * that GLSL reads a float.
     */
    fun format(
        value: Float,
        decimals: Int = DEFAULT_DECIMALS,
    ): String {
        require(value.isFinite()) { "a shader constant must be finite, not $value" }
        val unit = 10.0.pow(decimals).roundToLong()
        val scaled = (abs(value.toDouble()) * unit).roundToLong()
        val sign = if (value < 0f && scaled != 0L) "-" else ""
        val whole = scaled / unit
        val fraction = (scaled % unit).toString().padStart(decimals, '0')
        return "$sign$whole.$fraction"
    }

    /** The same number as a piece of an identifier: `9.5` is `9p500`, `-0.25` is `m0p250`. */
    fun keyPart(value: Float): String = format(value).replace("-", "m").replace('.', 'p')

    private const val DEFAULT_DECIMALS = 3
}
