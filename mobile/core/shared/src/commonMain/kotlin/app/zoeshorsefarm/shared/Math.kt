package app.zoeshorsefarm.shared

// Small math/type helpers shared by every layer.

/** Limits [v] to the range [min, max]. NaN passes through like in the web app. */
fun clamp(
    v: Double,
    min: Double,
    max: Double,
): Double =
    if (v < min) {
        min
    } else if (v > max) {
        max
    } else {
        v
    }

/** Limits [v] to the range [min, max]. */
fun clamp(
    v: Int,
    min: Int,
    max: Int,
): Int =
    if (v < min) {
        min
    } else if (v > max) {
        max
    } else {
        v
    }

/** True for non-null, non-list objects (a JSON object after parsing: a map). */
fun isPlainObject(v: Any?): Boolean = v is Map<*, *>
