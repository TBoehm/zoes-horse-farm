package app.zoeshorsefarm.render.filament.math

import kotlin.math.pow

/** A colour in linear sRGB (what Filament expects for lights, fog and clear colours). */
data class LinearRgb(
    val r: Float,
    val g: Float,
    val b: Float,
) {
    fun scaled(factor: Float) = LinearRgb(r * factor, g * factor, b * factor)

    companion object {
        /** Decodes an sRGB colour `0xRRGGBB` like three.js ColorManagement does. */
        fun fromSrgbHex(hex: Int) =
            LinearRgb(
                srgbToLinear(((hex shr 16) and 0xff) / 255f),
                srgbToLinear(((hex shr 8) and 0xff) / 255f),
                srgbToLinear((hex and 0xff) / 255f),
            )

        fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}
