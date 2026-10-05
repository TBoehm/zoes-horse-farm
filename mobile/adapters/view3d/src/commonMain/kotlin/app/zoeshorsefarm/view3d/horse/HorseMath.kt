package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.clamp

// Pure math helpers of the horse and rider code.

fun lerp(
    a: Double,
    b: Double,
    t: Double,
): Double = a + (b - a) * t

fun smoothstep(
    a: Double,
    b: Double,
    x: Double,
): Double {
    val t = clamp((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Shorthand for a row of numbers of a [table]. */
fun row(vararg values: Double): DoubleArray = values

/**
 * Smooth interpolation of a table [[k, v1, v2, ...], ...] (cubic Hermite with Catmull-Rom-like
 * tangents on non-uniform knots). Returns a function k -> [v1, v2, ...]; the result array is new on
 * every call (the tables are used while building geometry, not per frame).
 */
fun table(vararg rows: DoubleArray): (Double) -> DoubleArray {
    val n = rows.size
    val m = rows[0].size - 1
    return fun(k: Double): DoubleArray {
        val out = DoubleArray(m)
        if (k <= rows[0][0]) {
            for (j in 0 until m) out[j] = rows[0][j + 1]
            return out
        }
        if (k >= rows[n - 1][0]) {
            for (j in 0 until m) out[j] = rows[n - 1][j + 1]
            return out
        }
        var i = 0
        while (k > rows[i + 1][0]) i++
        val r0 = rows[maxOf(0, i - 1)]
        val r1 = rows[i]
        val r2 = rows[i + 1]
        val r3 = rows[minOf(n - 1, i + 2)]
        val h = r2[0] - r1[0]
        val t = (k - r1[0]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        for (j in 1..m) {
            val m1 = ((r2[j] - r0[j]) / (r2[0] - r0[0])) * h
            val m2 = ((r3[j] - r1[j]) / (r3[0] - r1[0])) * h
            out[j - 1] = h00 * r1[j] + h10 * m1 + h01 * r2[j] + h11 * m2
        }
        return out
    }
}
