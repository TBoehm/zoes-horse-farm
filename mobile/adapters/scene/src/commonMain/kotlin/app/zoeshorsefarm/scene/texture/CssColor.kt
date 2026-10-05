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

    /** `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, `hsl()`, `hsla()` or a colour name; null if unknown. */
    fun parse(style: String): Rgba? {
        val text = style.trim()
        if (text.startsWith("#")) return parseHex(text.substring(1))
        val fn = FUNCTION.matchEntire(text)
        if (fn != null) {
            val parts =
                fn.groupValues[2]
                    .split(',', ' ', '/')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            return when (fn.groupValues[1].lowercase()) {
                "rgb", "rgba" -> parseRgb(parts)
                "hsl", "hsla" -> parseHsl(parts)
                else -> null
            }
        }
        val lower = text.lowercase()
        if (lower == "transparent") return Rgba.TRANSPARENT
        val named = Color.NAMES[lower] ?: return null
        return Rgba(
            ((named shr 16) and 255).toDouble(),
            ((named shr 8) and 255).toDouble(),
            (named and 255).toDouble(),
            1.0,
        )
    }

    /** Like [parse], but an unparsable colour is a programming error. */
    fun require(style: String): Rgba = parse(style) ?: error("unknown CSS colour '$style'")

    private fun parseHex(hex: String): Rgba? {
        if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (hex.length) {
            3, 4 -> {
                val v = hex.map { it.digitToInt(16) * 17.0 }
                Rgba(v[0], v[1], v[2], if (v.size == 4) v[3] / 255 else 1.0)
            }

            6, 8 -> {
                val v = (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16).toDouble() }
                Rgba(v[0], v[1], v[2], if (v.size == 4) v[3] / 255 else 1.0)
            }

            else -> {
                null
            }
        }
    }

    private fun channel(
        text: String,
        scale: Double,
    ): Double? =
        if (text.endsWith("%")) text.dropLast(1).toDoubleOrNull()?.let { it / 100 * scale } else text.toDoubleOrNull()

    private fun alpha(text: String?): Double? =
        if (text ==
            null
        ) {
            1.0
        } else if (text.endsWith("%")) {
            text.dropLast(1).toDoubleOrNull()?.let { it / 100 }
        } else {
            text.toDoubleOrNull()
        }

    private fun parseRgb(parts: List<String>): Rgba? {
        if (parts.size < 3) return null
        val r = channel(parts[0], 255.0) ?: return null
        val g = channel(parts[1], 255.0) ?: return null
        val b = channel(parts[2], 255.0) ?: return null
        val a = alpha(parts.getOrNull(3)) ?: return null
        return Rgba(r.coerceIn(0.0, 255.0), g.coerceIn(0.0, 255.0), b.coerceIn(0.0, 255.0), a.coerceIn(0.0, 1.0))
    }

    private fun parseHsl(parts: List<String>): Rgba? {
        if (parts.size < 3) return null
        val h = parts[0].removeSuffix("deg").toDoubleOrNull() ?: return null
        val s = parts[1].removeSuffix("%").toDoubleOrNull()?.div(100) ?: return null
        val l = parts[2].removeSuffix("%").toDoubleOrNull()?.div(100) ?: return null
        val a = alpha(parts.getOrNull(3)) ?: return null
        val hueDeg = ((h % 360) + 360) % 360
        val sat = s.coerceIn(0.0, 1.0)
        val light = l.coerceIn(0.0, 1.0)
        val chroma = sat * minOf(light, 1 - light)

        fun channel(n: Double): Double {
            val k = (n + hueDeg / 30) % 12
            return (light - chroma * maxOf(-1.0, minOf(k - 3, minOf(9 - k, 1.0)))) * 255
        }
        return Rgba(channel(0.0), channel(8.0), channel(4.0), a.coerceIn(0.0, 1.0))
    }
}
