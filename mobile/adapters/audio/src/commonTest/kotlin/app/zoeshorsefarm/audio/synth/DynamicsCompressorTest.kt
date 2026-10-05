package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicsCompressorTest {
    private val sampleRate = 44100

    /** Peak of the output after the compressor settled for a steady 440 Hz sine of [amplitude]. */
    private fun settledPeak(
        amplitude: Float,
        seconds: Double = 1.5,
    ): Float {
        val compressor = DynamicsCompressor(sampleRate)
        val block = 128
        val left = FloatArray(block)
        val right = FloatArray(block)
        var peak = 0f
        val blocks = (seconds * sampleRate / block).toInt()
        for (b in 0 until blocks) {
            for (i in 0 until block) {
                val x = (amplitude * sin(2 * PI * 440 * (b * block + i) / sampleRate)).toFloat()
                left[i] = x
                right[i] = x
            }
            compressor.process(left, right, block)
            if (b > blocks - 40) for (i in 0 until block) peak = maxOf(peak, abs(left[i]))
        }
        return peak
    }

    private fun db(x: Float) = 20 * log10(x.toDouble())

    @Test
    fun staysSilentForSilence() {
        val compressor = DynamicsCompressor(sampleRate)
        val left = FloatArray(128)
        val right = FloatArray(128)
        repeat(20) { compressor.process(left, right, 128) }
        assertTrue(left.all { it == 0f })
        assertTrue(right.all { it == 0f })
    }

    @Test
    fun quietSignalsAreLiftedByTheAutomaticMakeupGain() {
        // below the threshold (-10 dB) there is no compression, only the makeup gain of about +3.7 dB
        val out = settledPeak(0.05f)
        val gainDb = db(out) - db(0.05f)
        assertTrue(gainDb > 3.2 && gainDb < 4.2, "makeup gain was $gainDb dB")
    }

    @Test
    fun loudSignalsAreCompressedWithTheirLevelStillRising() {
        val quiet = settledPeak(0.1f)
        val medium = settledPeak(0.4f)
        val loud = settledPeak(1.0f)
        assertTrue(quiet < medium && medium < loud)
        // the gain falls with the level: 20 dB more input gives clearly less than 20 dB more output
        assertTrue(db(loud) - db(quiet) < 16.0, "dynamic range ${db(loud) - db(quiet)} dB")
        assertTrue(loud < 1.0f, "no clipping at full scale, peak was $loud")
    }

    @Test
    fun delaysTheSignalByThePreDelayOfSixMilliseconds() {
        val compressor = DynamicsCompressor(sampleRate)
        val expectedDelay = (0.006 * sampleRate).toInt()
        val left = FloatArray(512)
        val right = FloatArray(512)
        left[0] = 0.01f
        right[0] = 0.01f
        compressor.process(left, right, 512)
        val first = left.indexOfFirst { it != 0f }
        assertEquals(expectedDelay, first)
    }

    @Test
    fun releasesAfterALoudPassage() {
        val compressor = DynamicsCompressor(sampleRate)
        val block = 128
        val left = FloatArray(block)
        val right = FloatArray(block)

        fun run(
            amplitude: Float,
            seconds: Double,
        ): Float {
            var peak = 0f
            val blocks = (seconds * sampleRate / block).toInt()
            for (b in 0 until blocks) {
                for (i in 0 until block) {
                    val x = (amplitude * sin(2 * PI * 440 * (b * block + i) / sampleRate)).toFloat()
                    left[i] = x
                    right[i] = x
                }
                compressor.process(left, right, block)
                if (b >= blocks - 4) for (i in 0 until block) peak = maxOf(peak, abs(left[i]))
            }
            return peak
        }
        val quietBefore = run(0.05f, 1.0)
        run(1.0f, 1.0)
        val quietRightAfter = run(0.05f, 0.05)
        val quietLater = run(0.05f, 2.0)
        assertTrue(quietRightAfter < quietBefore * 0.8f, "still compressed right after the loud part")
        assertEquals(quietBefore, quietLater, quietBefore * 0.05f)
    }
}
