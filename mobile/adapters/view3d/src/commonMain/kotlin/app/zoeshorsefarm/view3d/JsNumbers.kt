package app.zoeshorsefarm.view3d

import kotlin.math.floor

/** JS `Math.round`: halves go up (Kotlin's `round` goes to even). */
internal fun jsRound(x: Double): Int = floor(x + 0.5).toInt()
