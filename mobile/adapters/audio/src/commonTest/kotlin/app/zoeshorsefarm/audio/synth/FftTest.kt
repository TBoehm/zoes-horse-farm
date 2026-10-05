package app.zoeshorsefarm.audio.synth

import app.zoeshorsefarm.audio.Mulberry32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftTest {
    @Test
    fun transformsAnImpulseIntoAFlatSpectrum() {
        val fft = Fft(16)
        val re = FloatArray(16)
        val im = FloatArray(16)
        re[0] = 1f
        fft.forward(re, im)
        for (i in 0 until 16) {
            assertEquals(1f, re[i], 1e-6f)
            assertEquals(0f, im[i], 1e-6f)
        }
    }

    @Test
    fun findsTheBinOfASine() {
        val n = 64
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 5 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.forward(re, im)
        for (k in 0 until n) {
            val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
            if (k == 5 || k == n - 5) assertEquals(n / 2f, magnitude, 1e-3f) else assertTrue(magnitude < 1e-3f)
        }
    }

    @Test
    fun inverseRestoresTheInput() {
        val n = 128
        val rng = Mulberry32(11)
        val original = FloatArray(n) { (rng.next() * 2 - 1).toFloat() }
        val re = original.copyOf()
        val im = FloatArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-5f)
            assertEquals(0f, im[i], 1e-5f)
        }
    }

    @Test
    fun matchesTheDirectDftOfARandomSignal() {
        val n = 32
        val rng = Mulberry32(5)
        val x = DoubleArray(n) { rng.next() * 2 - 1 }
        val re = FloatArray(n) { x[it].toFloat() }
        val im = FloatArray(n)
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            var er = 0.0
            var ei = 0.0
            for (t in 0 until n) {
                er += x[t] * cos(2 * PI * k * t / n)
                ei -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(er.toFloat(), re[k], 1e-4f)
            assertEquals(ei.toFloat(), im[k], 1e-4f)
        }
    }
}
