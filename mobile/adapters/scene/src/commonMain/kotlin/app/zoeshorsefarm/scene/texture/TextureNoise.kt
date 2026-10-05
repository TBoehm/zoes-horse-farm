package app.zoeshorsefarm.scene.texture

import kotlin.math.floor

/** Deterministic random numbers in `[0, 1)` (mulberry32), the same sequence as the web app's `createRng`. */
fun createRng(seed: Int = 1): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

/** Tileable value noise: a grid of `period` cells, `invoke(u, v)` for `u`, `v` in `[0, 1)` (any real works, it wraps). */
class TileNoise(
    private val period: Int,
    seed: Int = 1,
) {
    private val grid = FloatArray(period * period)

    init {
        val rng = createRng(seed)
        for (i in grid.indices) grid[i] = rng().toFloat()
    }

    private fun at(
        x: Int,
        y: Int,
    ): Double = grid[(((y % period) + period) % period) * period + (((x % period) + period) % period)].toDouble()

    operator fun invoke(
        u: Double,
        v: Double,
    ): Double {
        val x = u * period
        val y = v * period
        val x0 = floor(x)
        val y0 = floor(y)
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val ix = x0.toInt()
        val iy = y0.toInt()
        val a = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * sx
        val b = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }
}

/** Tileable fractal noise from several octaves (each twice the period of the one before), result roughly 0..1. */
class TileFbm(
    basePeriod: Int,
    octaves: Int,
    seed: Int = 1,
) {
    private val layers = List(octaves) { TileNoise(basePeriod shl it, seed + it * 101) }

    operator fun invoke(
        u: Double,
        v: Double,
    ): Double {
        var sum = 0.0
        var amp = 0.5
        var norm = 0.0
        for (layer in layers) {
            sum += layer(u, v) * amp
            norm += amp
            amp *= 0.5
        }
        return sum / norm
    }
}
