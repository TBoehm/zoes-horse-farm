package app.zoeshorsefarm.presentation.theme

import kotlin.math.roundToInt

private const val CHANNEL_MAX = 255
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private const val HEX_DIGITS = 16

/**
 * A colour as four 0..255 channels (sRGB, not premultiplied). The Compose layer turns it into its
 * own colour type with [toArgbInt]; the model layer needs the channels for the contrast maths.
 */
data class Argb(
    val alpha: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    init {
        require(alpha in 0..CHANNEL_MAX && red in 0..CHANNEL_MAX && green in 0..CHANNEL_MAX && blue in 0..CHANNEL_MAX) {
            "Colour channel out of range: $alpha, $red, $green, $blue"
        }
    }

    /** The colour as `0xAARRGGBB` in an Int (the usual platform packing). */
    fun toArgbInt(): Int = (alpha shl 3 * BYTE_BITS) or (red shl 2 * BYTE_BITS) or (green shl BYTE_BITS) or blue

    /** `#rrggbb` (without the alpha channel), the form of the style sheet. */
    fun toHex(): String = "#" + listOf(red, green, blue).joinToString("") { it.toString(HEX_DIGITS).padStart(2, '0') }

    companion object {
        /** An opaque colour from `0xRRGGBB`. */
        fun rgb(rgb: Int): Argb = rgba(rgb, 1.0)

        /** A colour from `0xRRGGBB` and an alpha fraction 0..1 (CSS `rgba(r, g, b, a)`). */
        fun rgba(
            rgb: Int,
            alpha: Double,
        ): Argb =
            Argb(
                alpha = (alpha * CHANNEL_MAX).roundToInt(),
                red = (rgb shr 2 * BYTE_BITS) and BYTE_MASK,
                green = (rgb shr BYTE_BITS) and BYTE_MASK,
                blue = rgb and BYTE_MASK,
            )
    }
}
