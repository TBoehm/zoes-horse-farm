package app.zoeshorsefarm.scene

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** JavaScript `Number.prototype.toFixed` for finite values (used in program keys that include numbers). */
fun Double.toFixed(digits: Int): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
    val scale = 10.0.pow(digits)
    // round half up on the magnitude, like JS
    val scaled = floor(abs(this) * scale + 0.5)
    val whole = floor(scaled / scale)
    val fraction = scaled - whole * scale
    val negative = this < 0
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(whole.toLong())
    if (digits > 0) {
        sb.append('.')
        sb.append(fraction.toLong().toString().padStart(digits, '0'))
    }
    return sb.toString()
}
