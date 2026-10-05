package app.zoeshorsefarm.domain.testing

import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Like vitest's `toBeCloseTo`: passes when |expected - actual| < 10^-digits / 2 (default 2 digits,
 * as in vitest).
 */
fun assertCloseTo(
    expected: Double,
    actual: Double,
    digits: Int = 2,
    message: String? = null,
) {
    var tolerance = 0.5
    repeat(digits) { tolerance /= 10 }
    assertTrue(abs(expected - actual) < tolerance, message ?: "expected $expected (+-$tolerance) but was $actual")
}

/** JS `Math.round`: halves round up (Kotlin's `round` rounds halves to even). */
fun jsRound(x: Double): Double = kotlin.math.floor(x + 0.5)
