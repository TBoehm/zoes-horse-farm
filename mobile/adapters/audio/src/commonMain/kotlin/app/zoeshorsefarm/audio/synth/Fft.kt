package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT of a fixed power-of-two [size] on split real/imaginary arrays. */
internal class Fft(
    val size: Int,
) {
    private val cosTable = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
        var bits = 0
        while (1 shl bits < size) bits++
        for (i in 0 until size) {
            var r = 0
            for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            reversed[i] = r
        }
    }

    fun forward(
        re: FloatArray,
        im: FloatArray,
    ) = transform(re, im, -1f)

    /** Inverse transform including the 1/size scaling. */
    fun inverse(
        re: FloatArray,
        im: FloatArray,
    ) {
        transform(re, im, 1f)
        val scale = 1f / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(
        re: FloatArray,
        im: FloatArray,
        sign: Float,
    ) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += length
            }
            length *= 2
        }
    }
}
