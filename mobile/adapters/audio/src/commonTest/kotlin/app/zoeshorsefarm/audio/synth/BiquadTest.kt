package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadTest {
    private val sampleRate = 44100.0

    /** Measured gain in dB of a steady sine through the filter. */
    private fun measuredGainDb(
        filter: Biquad,
        hz: Double,
    ): Double {
        val n = 44100
        var sumIn = 0.0
        var sumOut = 0.0
        for (i in 0 until n) {
            val x = sin(2 * PI * hz * i / sampleRate)
            val y = filter.process(x)
            if (i >= n / 2) { // steady state
                sumIn += x * x
                sumOut += y * y
            }
        }
        return 10 * log10(sumOut / sumIn)
    }

    /** Magnitude response straight from the RBJ cookbook formulas (independent of Biquad). */
    private fun cookbookGainDb(
        type: FilterType,
        cutoff: Double,
        q: Double,
        hz: Double,
    ): Double {
        val w0 = 2 * PI * cutoff / sampleRate
        val alpha = sin(w0) / (2 * if (type == FilterType.Bandpass) q else 10.0.pow(q / 20))
        val c = cos(w0)
        val a0 = 1 + alpha
        val b =
            when (type) {
                FilterType.Lowpass -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
                FilterType.Highpass -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2)
                FilterType.Bandpass -> doubleArrayOf(alpha, 0.0, -alpha)
            }
        val a = doubleArrayOf(a0, -2 * c, 1 - alpha)
        val w = 2 * PI * hz / sampleRate

        fun response(coefficients: DoubleArray): Double {
            var re = 0.0
            var im = 0.0
            for (k in 0..2) {
                re += coefficients[k] * cos(w * k)
                im -= coefficients[k] * sin(w * k)
            }
            return hypot(re, im)
        }
        return 20 * log10(response(b) / response(a))
    }

    private fun check(
        type: FilterType,
        cutoff: Double,
        q: Double,
        hz: Double,
    ) {
        val filter = Biquad(sampleRate)
        filter.configure(type, cutoff, q)
        assertEquals(
            cookbookGainDb(type, cutoff, q, hz),
            measuredGainDb(filter, hz),
            0.05,
            "$type $cutoff Hz q=$q measured at $hz Hz",
        )
    }

    @Test
    fun lowpassMatchesTheCookbookResponse() {
        for (hz in listOf(100.0, 500.0, 1000.0, 2000.0, 8000.0)) check(FilterType.Lowpass, 1000.0, 0.7, hz)
    }

    @Test
    fun highpassMatchesTheCookbookResponse() {
        for (hz in listOf(300.0, 1000.0, 2600.0, 5200.0, 12000.0)) check(FilterType.Highpass, 2600.0, 1.0, hz)
    }

    @Test
    fun bandpassMatchesTheCookbookResponse() {
        for (hz in listOf(300.0, 900.0, 1250.0, 1700.0, 6000.0)) check(FilterType.Bandpass, 1250.0, 0.8, hz)
    }

    @Test
    fun lowpassAndHighpassQIsInDecibelsAndBandpassQIsLinear() {
        val lowpass = Biquad(sampleRate)
        lowpass.configure(FilterType.Lowpass, 1000.0, 12.0) // 12 dB resonance at the cutoff
        assertEquals(12.0, measuredGainDb(lowpass, 1000.0), 0.1)
        val bandpass = Biquad(sampleRate)
        bandpass.configure(FilterType.Bandpass, 1000.0, 5.0) // peak gain 0 dB for any q
        assertEquals(0.0, measuredGainDb(bandpass, 1000.0), 0.1)
    }

    @Test
    fun bandpassIsNarrowerWithAHigherQ() {
        val wide = Biquad(sampleRate)
        wide.configure(FilterType.Bandpass, 1000.0, 1.0)
        val narrow = Biquad(sampleRate)
        narrow.configure(FilterType.Bandpass, 1000.0, 8.0)
        assertTrue(measuredGainDb(narrow, 2000.0) < measuredGainDb(wide, 2000.0) - 6)
    }

    @Test
    fun cutoffAtOrAboveNyquistPassesALowpassAndBlocksAHighpassAndBandpass() {
        val lowpass = Biquad(sampleRate)
        lowpass.configure(FilterType.Lowpass, sampleRate, 0.7)
        assertEquals(0.0, measuredGainDb(lowpass, 5000.0), 1e-6)
        val highpass = Biquad(sampleRate)
        highpass.configure(FilterType.Highpass, sampleRate, 1.0)
        assertEquals(0.0, highpass.process(1.0))
        val bandpass = Biquad(sampleRate)
        bandpass.configure(FilterType.Bandpass, sampleRate, 1.0)
        assertEquals(0.0, bandpass.process(1.0))
    }

    @Test
    fun cutoffAtZeroBlocksALowpassAndPassesAHighpass() {
        val lowpass = Biquad(sampleRate)
        lowpass.configure(FilterType.Lowpass, 0.0, 0.7)
        assertEquals(0.0, lowpass.process(1.0))
        val highpass = Biquad(sampleRate)
        highpass.configure(FilterType.Highpass, 0.0, 1.0)
        assertEquals(1.0, highpass.process(1.0))
    }

    @Test
    fun resetClearsTheFilterMemory() {
        val filter = Biquad(sampleRate)
        filter.configure(FilterType.Lowpass, 500.0, 0.7)
        repeat(100) { filter.process(1.0) }
        filter.reset()
        val fresh = Biquad(sampleRate)
        fresh.configure(FilterType.Lowpass, 500.0, 0.7)
        assertEquals(fresh.process(0.5), filter.process(0.5))
    }

    @Test
    fun configuringTheSameValuesAgainKeepsTheFilterState() {
        val a = Biquad(sampleRate)
        val b = Biquad(sampleRate)
        a.configure(FilterType.Lowpass, 500.0, 0.7)
        b.configure(FilterType.Lowpass, 500.0, 0.7)
        repeat(50) {
            a.process(sin(it * 0.3))
            b.process(sin(it * 0.3))
            b.configure(FilterType.Lowpass, 500.0, 0.7)
        }
        assertEquals(a.process(0.25), b.process(0.25))
    }
}
