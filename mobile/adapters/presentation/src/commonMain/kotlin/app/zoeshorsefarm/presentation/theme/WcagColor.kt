package app.zoeshorsefarm.presentation.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// WCAG 2.x colour maths: relative luminance and contrast ratio of sRGB colours (web:
// tests/support/color.js). Production code here because the palette check runs against the real
// tokens, and a screen may use it to pick a readable ink.

private val HEX_COLOR = Regex("""^#([0-9a-f]{3}|[0-9a-f]{6})$""", RegexOption.IGNORE_CASE)

private const val CHANNEL_MAX = 255.0
private const val LINEAR_LIMIT = 0.03928
private const val LINEAR_DIVISOR = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_SCALE = 1.055
private const val GAMMA = 2.4
private const val WEIGHT_RED = 0.2126
private const val WEIGHT_GREEN = 0.7152
private const val WEIGHT_BLUE = 0.0722
private const val LUMINANCE_OFFSET = 0.05
private const val HEX_RADIX = 16

/** Parses `#rgb` or `#rrggbb` into `[r, g, b]` (0..255); null when it is not a hex colour. */
fun parseHex(value: String?): IntArray? {
    val digits = HEX_COLOR.matchEntire(value?.trim() ?: return null)?.groupValues?.get(1) ?: return null
    val full = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
    return IntArray(3) { full.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX) }
}

private fun channel(value: Int): Double {
    val s = value / CHANNEL_MAX
    return if (s <= LINEAR_LIMIT) s / LINEAR_DIVISOR else ((s + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA)
}

/** Relative luminance (0..1) of an `[r, g, b]` colour. */
fun relativeLuminance(rgb: IntArray): Double =
    WEIGHT_RED * channel(rgb[0]) + WEIGHT_GREEN * channel(rgb[1]) + WEIGHT_BLUE * channel(rgb[2])

private fun contrastOfLuminances(
    a: Double,
    b: Double,
): Double = (max(a, b) + LUMINANCE_OFFSET) / (min(a, b) + LUMINANCE_OFFSET)

/** WCAG contrast ratio (1..21) between two hex colours. */
fun contrastRatio(
    a: String,
    b: String,
): Double {
    val rgbA = requireNotNull(parseHex(a)) { "Cannot parse color: $a" }
    val rgbB = requireNotNull(parseHex(b)) { "Cannot parse color: $b" }
    return contrastOfLuminances(relativeLuminance(rgbA), relativeLuminance(rgbB))
}

/** WCAG contrast ratio between two colours; the alpha channel is ignored (compare against the opaque fill). */
fun contrastRatio(
    a: Argb,
    b: Argb,
): Double =
    contrastOfLuminances(
        relativeLuminance(intArrayOf(a.red, a.green, a.blue)),
        relativeLuminance(intArrayOf(b.red, b.green, b.blue)),
    )
