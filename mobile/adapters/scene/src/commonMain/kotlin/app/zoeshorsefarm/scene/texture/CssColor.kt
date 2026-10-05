package app.zoeshorsefarm.scene.texture

import app.zoeshorsefarm.scene.math.Color

/** An sRGB colour with 0..255 channels and alpha 0..1 (what a canvas keeps for a CSS colour). */
class Rgba(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double,
) {
    companion object {
        val BLACK = Rgba(0.0, 0.0, 0.0, 1.0)
        val TRANSPARENT = Rgba(0.0, 0.0, 0.0, 0.0)
    }
}

/** Parser for the CSS colour strings of canvas drawing code. */
object CssColor {
    private val FUNCTION = Regex("""^([a-zA-Z]+)\(\s*([^)]*)\)$""")

    /** `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, `hsl()`, `hsla()` or a name; null if unknown. */
    fun parse(style: String): Rgba? {
        val text = style.trim()
        val fn = FUNCTION.matchEntire(text)
        return when {
            text.startsWith("#") -> parseHex(text.substring(1))
            fn != null -> parseFunction(fn.groupValues[1].lowercase(), fn.groupValues[2])
            else -> parseName(text.lowercase())
        }
    }

    /** Like [parse], but an unparsable colour is a programming error. */
    fun require(style: String): Rgba = parse(style) ?: error("unknown CSS colour '$style'")

    private fun parseName(lower: String): Rgba? {
        val named = Color.NAMES[lower]
        return when {
            lower == "transparent" -> {
                Rgba.TRANSPARENT
            }

            named == null -> {
                null
            }

            else -> {
                Rgba(
                    ((named shr 16) and 255).toDouble(),
                    ((named shr 8) and 255).toDouble(),
                    (named and 255).toDouble(),
                    1.0,
                )
            }
        }
    }

    private fun parseFunction(
        name: String,
        arguments: String,
    ): Rgba? {
        val parts = arguments.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        return when (name) {
            "rgb", "rgba" -> parseRgb(parts)
            "hsl", "hsla" -> parseHsl(parts)
            else -> null
        }
    }

    private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun parseHex(hex: String): Rgba? {
        val values =
            when {
                hex.isEmpty() || !hex.all(::isHexDigit) -> {
                    null
                }

                hex.length == 3 || hex.length == 4 -> {
                    hex.map { it.digitToInt(16) * 17.0 }
                }

                hex.length == 6 || hex.length == 8 -> {
                    (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16).toDouble() }
                }

                else -> {
                    null
                }
            }
        return values?.let { Rgba(it[0], it[1], it[2], if (it.size == 4) it[3] / 255 else 1.0) }
    }

    private fun channel(
        text: String,
        scale: Double,
    ): Double? =
        if (text.endsWith("%")) text.dropLast(1).toDoubleOrNull()?.let { it / 100 * scale } else text.toDoubleOrNull()

    private fun alpha(text: String?): Double? =
        when {
            text == null -> 1.0
            text.endsWith("%") -> text.dropLast(1).toDoubleOrNull()?.let { it / 100 }
            else -> text.toDoubleOrNull()
        }

    private fun parseRgb(parts: List<String>): Rgba? {
        val values = parts.take(3).map { channel(it, 255.0) }
        val a = alpha(parts.getOrNull(3))
        val valid = parts.size >= 3 && a != null && values.none { it == null }
        return if (valid) {
            val (r, g, b) = values.map { it?.coerceIn(0.0, 255.0) ?: 0.0 }
            Rgba(r, g, b, (a ?: 1.0).coerceIn(0.0, 1.0))
        } else {
            null
        }
    }

    private fun parseHsl(parts: List<String>): Rgba? {
        val h = parts.getOrNull(0)?.removeSuffix("deg")?.toDoubleOrNull()
        val s = parts.getOrNull(1)?.removeSuffix("%")?.toDoubleOrNull()
        val l = parts.getOrNull(2)?.removeSuffix("%")?.toDoubleOrNull()
        val a = alpha(parts.getOrNull(3))
        val hsl = listOfNotNull(h, s, l)
        return if (hsl.size == 3 && a != null) hslToRgba(hsl[0], hsl[1] / 100, hsl[2] / 100, a) else null
    }

    private fun hslToRgba(
        h: Double,
        s: Double,
        l: Double,
        a: Double,
    ): Rgba {
        val hueDeg = ((h % 360) + 360) % 360
        val light = l.coerceIn(0.0, 1.0)
        val chroma = s.coerceIn(0.0, 1.0) * minOf(light, 1 - light)

        fun channel(n: Double): Double {
            val k = (n + hueDeg / 30) % 12
            return (light - chroma * maxOf(-1.0, minOf(k - 3, minOf(9 - k, 1.0)))) * 255
        }
        return Rgba(channel(0.0), channel(8.0), channel(4.0), a.coerceIn(0.0, 1.0))
    }
}
