package app.zoeshorsefarm.scene.texture

/** A parsed canvas font string such as `800 64px system-ui, sans-serif`. */
data class FontSpec(
    val weight: Int = 400,
    val sizePx: Double = 10.0,
    val family: String = "sans-serif",
) {
    companion object {
        private val PATTERN = Regex("""^\s*(?:(\d{3}|bold|normal)\s+)?(\d+(?:\.\d+)?)px\s+(.*)$""")

        /** Parses `[weight] <size>px <family>`; anything else gives the canvas default (10px sans-serif). */
        fun parse(font: String): FontSpec {
            val m = PATTERN.matchEntire(font) ?: return FontSpec()
            val weight =
                when (val w = m.groupValues[1]) {
                    "", "normal" -> 400
                    "bold" -> 700
                    else -> w.toInt()
                }
            return FontSpec(weight, m.groupValues[2].toDouble(), m.groupValues[3].trim())
        }
    }
}

/** Vertical extent of a font above and below the baseline, in pixels. */
class FontMetrics(
    val ascent: Double,
    val descent: Double,
)

/**
 * Coverage mask of rendered text. [alpha] is `width * height` bytes; ([offsetX], [offsetY]) is the
 * position of the mask's top-left pixel relative to the pen origin (left end of the baseline), so
 * the values are usually zero or negative.
 */
class TextMask(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
    val offsetX: Int,
    val offsetY: Int,
)

/**
 * Port for drawing text into a [Raster2D]. The platform (Compose / Skia / Core Text) provides the
 * real fonts; [BlockTextRasterizer] is the dependency-free fallback for tests.
 */
interface TextRasterizer {
    /** Advance width of `text` in pixels. */
    fun measure(
        text: String,
        font: FontSpec,
    ): Double

    fun metrics(font: FontSpec): FontMetrics

    /** Renders `text` as an alpha mask. */
    fun rasterize(
        text: String,
        font: FontSpec,
    ): TextMask
}

/**
 * Draws every character that is not a space as a solid box (0.5 em wide, 0.7 em tall) on an advance
 * of 0.6 em. Deterministic and good enough to test layout, centring and shrinking of labels.
 */
object BlockTextRasterizer : TextRasterizer {
    override fun measure(
        text: String,
        font: FontSpec,
    ): Double = text.length * 0.6 * font.sizePx

    override fun metrics(font: FontSpec): FontMetrics = FontMetrics(font.sizePx * 0.8, font.sizePx * 0.2)

    override fun rasterize(
        text: String,
        font: FontSpec,
    ): TextMask {
        val advance = 0.6 * font.sizePx
        val boxWidth =
            kotlin.math
                .ceil(0.5 * font.sizePx)
                .toInt()
                .coerceAtLeast(1)
        val boxHeight =
            kotlin.math
                .ceil(0.7 * font.sizePx)
                .toInt()
                .coerceAtLeast(1)
        val width =
            kotlin.math
                .ceil(advance * text.length)
                .toInt()
                .coerceAtLeast(1)
        val alpha = ByteArray(width * boxHeight)
        for ((i, ch) in text.withIndex()) {
            if (ch == ' ') continue
            val x0 = kotlin.math.round(i * advance).toInt()
            for (y in 0 until boxHeight) {
                for (x in x0 until minOf(x0 + boxWidth, width)) alpha[y * width + x] = 255.toByte()
            }
        }
        return TextMask(width, boxHeight, alpha, 0, -boxHeight)
    }
}
