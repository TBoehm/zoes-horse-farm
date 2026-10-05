package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.Language
import kotlin.math.floor
import kotlin.math.max

private const val CS_PER_MINUTE = 6000
private const val CS_PER_SECOND = 100

/**
 * Ride time in hundredths of a second as `mm:ss,hh` (glossary "ride time"); English uses a decimal
 * point. Rounds to whole hundredths (halves up, like `Math.round`) and never shows a negative time.
 */
fun formatCs(
    cs: Double,
    lang: Language = Language.DE,
): String {
    val total = max(0.0, floor(cs + 0.5)).toLong()
    val minutes = total / CS_PER_MINUTE
    val seconds = total % CS_PER_MINUTE / CS_PER_SECOND
    val hundredths = total % CS_PER_SECOND
    val sep = if (lang == Language.DE) ',' else '.'

    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${two(minutes)}:${two(seconds)}$sep${two(hundredths)}"
}

/** [formatCs] for whole hundredths. */
fun formatCs(
    cs: Int,
    lang: Language = Language.DE,
): String = formatCs(cs.toDouble(), lang)
