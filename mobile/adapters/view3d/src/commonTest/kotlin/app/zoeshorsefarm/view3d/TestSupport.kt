package app.zoeshorsefarm.view3d

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertTrue

/** Like vitest's `toBeCloseTo`: passes when |expected - actual| < 10^-digits / 2 (digits may be negative). */
fun assertClose(
    expected: Double,
    actual: Double,
    digits: Int = 2,
    message: String = "",
) {
    val tolerance = 10.0.pow(-digits) / 2
    assertTrue(abs(expected - actual) < tolerance, "$message expected $expected (+-$tolerance) but was $actual")
}

/** Passes when |expected - actual| <= [eps]. */
fun assertNear(
    expected: Double,
    actual: Double,
    eps: Double = 1e-9,
    message: String = "",
) {
    assertTrue(abs(expected - actual) <= eps, "$message expected $expected (+-$eps) but was $actual")
}

fun assertLess(
    actual: Double,
    limit: Double,
    message: String = "",
) = assertTrue(actual < limit, "$message expected < $limit but was $actual")

fun assertLessOrEqual(
    actual: Double,
    limit: Double,
    message: String = "",
) = assertTrue(actual <= limit, "$message expected <= $limit but was $actual")

fun assertGreater(
    actual: Double,
    limit: Double,
    message: String = "",
) = assertTrue(actual > limit, "$message expected > $limit but was $actual")

fun assertGreaterOrEqual(
    actual: Double,
    limit: Double,
    message: String = "",
) = assertTrue(actual >= limit, "$message expected >= $limit but was $actual")

fun assertFinite(
    value: Double,
    message: String = "",
) = assertTrue(value.isFinite(), "$message expected a finite number but was $value")
