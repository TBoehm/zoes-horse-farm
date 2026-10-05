package app.zoeshorsefarm.scene.math

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour spaces of three.js ColorManagement that the game uses. */
enum class ColorSpace { SRGB, LINEAR_SRGB }

/** HSL triple filled by [Color.getHSL]. */
class Hsl(
    var h: Double = 0.0,
    var s: Double = 0.0,
    var l: Double = 0.0,
)

/**
 * RGB colour in the working space (linear sRGB), like three.js `Color` with ColorManagement on:
 * hex values, CSS strings and `setRGB(..., SRGB)` are converted to linear when they are set.
 */
class Color(
    r: Double = 1.0,
    g: Double = 1.0,
    b: Double = 1.0,
) {
    var r: Double = r
    var g: Double = g
    var b: Double = b

    constructor(hex: Int) : this() {
        setHex(hex)
    }

    constructor(style: String) : this() {
        setStyle(style)
    }

    fun set(color: Color): Color = copy(color)

    fun set(hex: Int): Color = setHex(hex)

    fun set(style: String): Color = setStyle(style)

    fun setScalar(scalar: Double): Color {
        r = scalar
        g = scalar
        b = scalar
        return this
    }

    /** `hex` is `0xRRGGBB`. */
    fun setHex(
        hex: Int,
        colorSpace: ColorSpace = ColorSpace.SRGB,
    ): Color {
        r = ((hex shr 16) and 255) / 255.0
        g = ((hex shr 8) and 255) / 255.0
        b = (hex and 255) / 255.0
        return colorSpaceToWorking(colorSpace)
    }

    fun setRGB(
        r: Double,
        g: Double,
        b: Double,
        colorSpace: ColorSpace = ColorSpace.LINEAR_SRGB,
    ): Color {
        this.r = r
        this.g = g
        this.b = b
        return colorSpaceToWorking(colorSpace)
    }

    fun setHSL(
        h: Double,
        s: Double,
        l: Double,
        colorSpace: ColorSpace = ColorSpace.LINEAR_SRGB,
    ): Color {
        val hh = MathUtils.euclideanModulo(h, 1.0)
        val ss = MathUtils.clamp(s, 0.0, 1.0)
        val ll = MathUtils.clamp(l, 0.0, 1.0)
        if (ss == 0.0) {
            r = ll
            g = ll
            b = ll
        } else {
            val p = if (ll <= 0.5) ll * (1 + ss) else ll + ss - (ll * ss)
            val q = (2 * ll) - p
            r = hue2rgb(q, p, hh + 1.0 / 3)
            g = hue2rgb(q, p, hh)
            b = hue2rgb(q, p, hh - 1.0 / 3)
        }
        return colorSpaceToWorking(colorSpace)
    }

    /**
     * Parses `#rgb`, `#rrggbb`, `rgb()/rgba()` (alpha ignored), `hsl()/hsla()` and CSS colour
     * names. Unknown input leaves the colour unchanged.
     */
    fun setStyle(
        style: String,
        colorSpace: ColorSpace = ColorSpace.SRGB,
    ): Color {
        val text = style.trim()
        val fn = FUNCTION.matchEntire(text) ?: FUNCTION.find(text)
        if (fn != null) {
            val parts = fn.groupValues[2].split(',').map { it.trim() }
            when (fn.groupValues[1]) {
                "rgb", "rgba" -> parseRgb(parts, colorSpace)
                "hsl", "hsla" -> parseHsl(parts, colorSpace)
            }
            return this
        }
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                if (hex.length == 3) {
                    return setRGB(
                        hex[0].digitToInt(16) / 15.0,
                        hex[1].digitToInt(16) / 15.0,
                        hex[2].digitToInt(16) / 15.0,
                        colorSpace,
                    )
                }
                if (hex.length == 6) return setHex(hex.toInt(16), colorSpace)
            }
            return this
        }
        if (text.isNotEmpty()) {
            val named = NAMES[text.lowercase()]
            if (named != null) setHex(named, colorSpace)
        }
        return this
    }

    private fun parseRgb(
        parts: List<String>,
        colorSpace: ColorSpace,
    ) {
        if (parts.size < 3) return
        val percent = parts[0].endsWith("%")
        val scale = if (percent) 100.0 else 255.0
        val values = parts.take(3).map { it.removeSuffix("%").toDoubleOrNull() ?: return }
        setRGB(
            min(scale, values[0]) / scale,
            min(scale, values[1]) / scale,
            min(scale, values[2]) / scale,
            colorSpace,
        )
    }

    private fun parseHsl(
        parts: List<String>,
        colorSpace: ColorSpace,
    ) {
        if (parts.size < 3) return
        val h = parts[0].toDoubleOrNull() ?: return
        val s = parts[1].removeSuffix("%").toDoubleOrNull() ?: return
        val l = parts[2].removeSuffix("%").toDoubleOrNull() ?: return
        setHSL(h / 360, s / 100, l / 100, colorSpace)
    }

    fun clone(): Color = Color(r, g, b)

    fun copy(color: Color): Color {
        r = color.r
        g = color.g
        b = color.b
        return this
    }

    fun copySRGBToLinear(color: Color): Color {
        r = srgbToLinear(color.r)
        g = srgbToLinear(color.g)
        b = srgbToLinear(color.b)
        return this
    }

    fun copyLinearToSRGB(color: Color): Color {
        r = linearToSrgb(color.r)
        g = linearToSrgb(color.g)
        b = linearToSrgb(color.b)
        return this
    }

    fun convertSRGBToLinear(): Color = copySRGBToLinear(this)

    fun convertLinearToSRGB(): Color = copyLinearToSRGB(this)

    /** `0xRRGGBB` in the given space (default sRGB). */
    fun getHex(colorSpace: ColorSpace = ColorSpace.SRGB): Int {
        scratch.copy(this).workingToColorSpace(colorSpace)
        return jsRound(MathUtils.clamp(scratch.r * 255, 0.0, 255.0)).toInt() * 65536 +
            jsRound(MathUtils.clamp(scratch.g * 255, 0.0, 255.0)).toInt() * 256 +
            jsRound(MathUtils.clamp(scratch.b * 255, 0.0, 255.0)).toInt()
    }

    fun getHSL(
        target: Hsl,
        colorSpace: ColorSpace = ColorSpace.LINEAR_SRGB,
    ): Hsl {
        scratch.copy(this).workingToColorSpace(colorSpace)
        val cr = scratch.r
        val cg = scratch.g
        val cb = scratch.b
        val maxC = max(cr, max(cg, cb))
        val minC = min(cr, min(cg, cb))
        var hue = 0.0
        val saturation: Double
        val lightness = (minC + maxC) / 2.0
        if (minC == maxC) {
            saturation = 0.0
        } else {
            val delta = maxC - minC
            saturation = if (lightness <= 0.5) delta / (maxC + minC) else delta / (2 - maxC - minC)
            hue =
                when (maxC) {
                    cr -> (cg - cb) / delta + (if (cg < cb) 6 else 0)
                    cg -> (cb - cr) / delta + 2
                    else -> (cr - cg) / delta + 4
                }
            hue /= 6
        }
        target.h = hue
        target.s = saturation
        target.l = lightness
        return target
    }

    /** CSS string `rgb(r,g,b)` in sRGB. */
    fun getStyle(): String {
        scratch.copy(this).workingToColorSpace(ColorSpace.SRGB)
        return "rgb(${jsRound(scratch.r * 255).toInt()},${jsRound(scratch.g * 255).toInt()}," +
            "${jsRound(scratch.b * 255).toInt()})"
    }

    fun offsetHSL(
        h: Double,
        s: Double,
        l: Double,
    ): Color {
        getHSL(hslA)
        return setHSL(hslA.h + h, hslA.s + s, hslA.l + l)
    }

    fun add(color: Color): Color {
        r += color.r
        g += color.g
        b += color.b
        return this
    }

    fun addColors(
        c1: Color,
        c2: Color,
    ): Color {
        r = c1.r + c2.r
        g = c1.g + c2.g
        b = c1.b + c2.b
        return this
    }

    fun addScalar(s: Double): Color {
        r += s
        g += s
        b += s
        return this
    }

    fun sub(color: Color): Color {
        r = max(0.0, r - color.r)
        g = max(0.0, g - color.g)
        b = max(0.0, b - color.b)
        return this
    }

    fun multiply(color: Color): Color {
        r *= color.r
        g *= color.g
        b *= color.b
        return this
    }

    fun multiplyScalar(s: Double): Color {
        r *= s
        g *= s
        b *= s
        return this
    }

    fun lerp(
        color: Color,
        alpha: Double,
    ): Color {
        r += (color.r - r) * alpha
        g += (color.g - g) * alpha
        b += (color.b - b) * alpha
        return this
    }

    fun lerpColors(
        c1: Color,
        c2: Color,
        alpha: Double,
    ): Color {
        r = c1.r + (c2.r - c1.r) * alpha
        g = c1.g + (c2.g - c1.g) * alpha
        b = c1.b + (c2.b - c1.b) * alpha
        return this
    }

    fun lerpHSL(
        color: Color,
        alpha: Double,
    ): Color {
        getHSL(hslA)
        color.getHSL(hslB)
        return setHSL(
            MathUtils.lerp(hslA.h, hslB.h, alpha),
            MathUtils.lerp(hslA.s, hslB.s, alpha),
            MathUtils.lerp(hslA.l, hslB.l, alpha),
        )
    }

    fun setFromVector3(v: Vec3): Color {
        r = v.x
        g = v.y
        b = v.z
        return this
    }

    fun equals(c: Color): Boolean = c.r == r && c.g == g && c.b == b

    fun fromArray(
        array: DoubleArray,
        offset: Int = 0,
    ): Color {
        r = array[offset]
        g = array[offset + 1]
        b = array[offset + 2]
        return this
    }

    fun fromArray(
        array: FloatArray,
        offset: Int = 0,
    ): Color {
        r = array[offset].toDouble()
        g = array[offset + 1].toDouble()
        b = array[offset + 2].toDouble()
        return this
    }

    fun toArray(
        out: DoubleArray = DoubleArray(3),
        offset: Int = 0,
    ): DoubleArray {
        out[offset] = r
        out[offset + 1] = g
        out[offset + 2] = b
        return out
    }

    override fun toString(): String = "Color($r, $g, $b)"

    private fun colorSpaceToWorking(source: ColorSpace): Color {
        if (source == ColorSpace.SRGB) convertSRGBToLinear()
        return this
    }

    private fun workingToColorSpace(target: ColorSpace): Color {
        if (target == ColorSpace.SRGB) convertLinearToSRGB()
        return this
    }

    companion object {
        private val FUNCTION = Regex("""^(\w+)\(([^)]*)\)""")
        private val scratch = Color()
        private val hslA = Hsl()
        private val hslB = Hsl()

        /** sRGB transfer function (encoded -> linear). */
        fun srgbToLinear(c: Double): Double =
            if (c <
                0.04045
            ) {
                c * 0.0773993808
            } else {
                (c * 0.9478672986 + 0.0521327014).pow(2.4)
            }

        /** Inverse sRGB transfer function (linear -> encoded). */
        fun linearToSrgb(c: Double): Double = if (c < 0.0031308) c * 12.92 else 1.055 * c.pow(0.41666) - 0.055

        private fun hue2rgb(
            p: Double,
            q: Double,
            tIn: Double,
        ): Double {
            var t = tIn
            if (t < 0) t += 1
            if (t > 1) t -= 1
            if (t < 1.0 / 6) return p + (q - p) * 6 * t
            if (t < 1.0 / 2) return q
            if (t < 2.0 / 3) return p + (q - p) * 6 * (2.0 / 3 - t)
            return p
        }

        /** CSS colour names (subset of three.js `Color.NAMES` that matters for drawing). */
        val NAMES: Map<String, Int> =
            mapOf(
                "black" to 0x000000,
                "white" to 0xFFFFFF,
                "red" to 0xFF0000,
                "green" to 0x008000,
                "blue" to 0x0000FF,
                "yellow" to 0xFFFF00,
                "orange" to 0xFFA500,
                "gray" to 0x808080,
                "grey" to 0x808080,
                "silver" to 0xC0C0C0,
                "brown" to 0xA52A2A,
                "pink" to 0xFFC0CB,
                "purple" to 0x800080,
                "cyan" to 0x00FFFF,
                "magenta" to 0xFF00FF,
                "lime" to 0x00FF00,
                "navy" to 0x000080,
                "teal" to 0x008080,
                "maroon" to 0x800000,
                "olive" to 0x808000,
                "gold" to 0xFFD700,
                "transparent" to 0x000000,
            )
    }
}
