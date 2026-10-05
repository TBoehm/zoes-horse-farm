package app.zoeshorsefarm.audio.synth

import app.zoeshorsefarm.audio.createNoiseBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthTest {
    private val sampleRate = 8000
    private val rate = sampleRate.toDouble()
    private val synth = Synth(rate, createNoiseBuffer(sampleRate))
    private val bus = GainBus(rate).also { it.open(0, 1.0) }

    /** Renders [seconds] of the bus (whole quanta) and returns the samples. */
    private fun render(seconds: Double): FloatArray {
        val quanta = ceil(seconds * sampleRate / QUANTUM).toInt()
        val out = FloatArray(quanta * QUANTUM)
        for (q in 0 until quanta) {
            synth.render(QUANTUM)
            bus.buffer.copyInto(out, q * QUANTUM)
            bus.buffer.fill(0f)
            synth.currentFrame += QUANTUM
        }
        return out
    }

    private fun peak(
        x: FloatArray,
        from: Int = 0,
        to: Int = x.size,
    ) = (from until to).maxOf { abs(x[it]) }

    private fun rms(
        x: FloatArray,
        from: Int,
        to: Int,
    ): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    private fun assertNear(
        expected: Int,
        actual: Int,
        tolerance: Int,
    ) = assertTrue(abs(expected - actual) <= tolerance, "expected $expected +-$tolerance but was $actual")

    private fun crossings(
        x: FloatArray,
        from: Int,
        to: Int,
    ): Int {
        var n = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0f) != (x[i] < 0f)) n++
        return n
    }

    private fun sine(
        freq: Double = 100.0,
        peak: Double = 0.5,
        decay: Double = 0.2,
        t: Double = 0.0,
    ) = synth.tone(bus, t, OscType.Sine, freq, 0.0, 0.1, 0.01, 0.0, decay, peak, 0.0)

    @Test
    fun aToneReachesItsPeakAfterTheAttackAndDecaysToSilence() {
        // 25 Hz: the sine is at its crest when the 10 ms attack (80 samples) ends
        sine(freq = 25.0, peak = 0.5, decay = 0.2)
        val out = render(0.5)
        assertEquals(0.5f, peak(out, 70, 100), 0.03f)
        assertTrue(peak(out, 3000, out.size) < 0.001f, "silent after the decay")
        // the first samples are quiet (exponential attack from 0.0001)
        assertTrue(peak(out, 0, 8) < 0.01f)
    }

    @Test
    fun aToneHasTheRequestedFrequency() {
        sine(freq = 100.0, peak = 0.5, decay = 0.4)
        val out = render(0.3)
        // 100 Hz = 200 zero crossings per second, measured between 50 ms and 250 ms
        assertNear(40, crossings(out, 400, 2000), 1)
    }

    @Test
    fun theVoiceIsFreedWhenTheSourceStops() {
        sine(decay = 0.1)
        assertEquals(1, synth.activeVoices)
        render(0.05)
        assertEquals(1, synth.activeVoices)
        render(0.2) // envelope ends at 0.11 s, the source stops 30 ms later
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun aToneStartsAtItsScheduledTime() {
        sine(t = 0.05, peak = 0.5)
        val out = render(0.2)
        assertEquals(0f, peak(out, 0, 400)) // 50 ms = 400 samples
        assertTrue(peak(out, 400, 600) > 0.01f)
    }

    @Test
    fun theGlideMovesTheFrequencyExponentially() {
        synth.tone(bus, 0.0, OscType.Sine, 400.0, 100.0, 0.2, 0.001, 0.0, 0.5, 0.5, 0.0)
        val out = render(0.5)
        val early = crossings(out, 40, 400) // first ~45 ms: about 400 Hz down to ~300 Hz
        val late = crossings(out, 1600, 1960) // after the glide: 100 Hz
        assertTrue(early > late * 2, "early $early late $late")
        assertNear(9, late, 2) // 100 Hz over 45 ms
    }

    @Test
    fun detuneRaisesThePitchByCents() {
        synth.tone(bus, 0.0, OscType.Sine, 100.0, 0.0, 0.1, 0.001, 0.0, 1.0, 0.5, 1200.0) // one octave up
        val out = render(0.3)
        assertNear(40 * 2, crossings(out, 400, 2000), 2)
    }

    @Test
    fun aTriangleWaveIsLinearBetweenItsCrests() {
        // 50 Hz at 8 kHz: 160 samples per period, crests at samples 40 and 120, zero crossing at 80
        synth.tone(bus, 0.0, OscType.Triangle, 50.0, 0.0, 0.1, 0.001, 0.0, 5.0, 0.5, 0.0)
        val out = render(0.1)
        val rise = out[30] - out[10]
        val riseLater = out[40] - out[20]
        assertEquals(rise, riseLater, rise * 0.1f)
        assertTrue(rise > 0f)
        assertTrue(out[40] > out[39] && out[40] > out[41], "positive crest")
        assertTrue(out[120] < out[119] && out[120] < out[121], "negative crest")
        assertEquals(0f, out[80], 0.01f)
        // a triangle has far less energy in the harmonics than a square: peak to RMS ratio is sqrt(3)
        assertEquals(1.732, peak(out, 200, 440) / rms(out, 200, 440), 0.05)
    }

    @Test
    fun noiseThroughALowpassIsDarkerThanThroughAHighpass() {
        synth.noiseBurst(bus, 0.0, FilterType.Lowpass, 300.0, 0.0, 0.7, 0.003, 0.0, 0.3, 1.0, 0.0)
        val low = render(0.3)
        synth.currentFrame = 0
        synth.noiseBurst(bus, 0.0, FilterType.Highpass, 2600.0, 0.0, 1.0, 0.003, 0.0, 0.3, 1.0, 0.0)
        val high = render(0.3)
        assertTrue(crossings(low, 100, 1500) * 2 < crossings(high, 100, 1500))
        assertTrue(rms(low, 100, 1500) > 0.0 && rms(high, 100, 1500) > 0.0)
    }

    @Test
    fun noiseUsesTheOffsetIntoTheNoiseBuffer() {
        val a = Synth(rate, createNoiseBuffer(sampleRate))
        val busA = GainBus(rate).also { it.open(0, 1.0) }
        a.noiseBurst(busA, 0.0, FilterType.Highpass, 3000.0, 0.0, 1.0, 0.001, 0.0, 0.1, 1.0, 0.0)
        a.render(QUANTUM)
        val first = busA.buffer.copyOf()
        val b = Synth(rate, createNoiseBuffer(sampleRate))
        val busB = GainBus(rate).also { it.open(0, 1.0) }
        b.noiseBurst(busB, 0.0, FilterType.Highpass, 3000.0, 0.0, 1.0, 0.001, 0.0, 0.1, 1.0, 0.5)
        b.render(QUANTUM)
        assertTrue(!first.contentEquals(busB.buffer))
    }

    @Test
    fun theNoiseFilterSweepsOverTheLengthOfTheBurst() {
        // bandpass sweeping from 2.8 kHz down to 300 Hz: early part brighter than the late part
        synth.noiseBurst(bus, 0.0, FilterType.Bandpass, 2800.0, 300.0, 1.2, 0.001, 0.0, 0.5, 1.0, 0.1)
        val out = render(0.5)
        assertTrue(crossings(out, 40, 600) > crossings(out, 3000, 3560))
    }

    @Test
    fun aSoftNoteSwellsHoldsAndReleases() {
        // note length 1 s: hold point at 0.9 s, release 0.14 s after it
        synth.softNote(bus, 0.0, OscType.Sine, 200.0, 0.0, 0.4, 1.0)
        val out = render(1.4)
        assertEquals(0.4f, peak(out, 100, 140), 0.03f) // 15 ms attack = sample 120
        assertEquals(0.2f, peak(out, 7100, 7300), 0.02f) // half the peak at the hold point 0.9 s
        assertTrue(peak(out, 8500, out.size) < 0.001f)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun theTwoDetunedMelodyVoicesBeat() {
        synth.softNote(bus, 0.0, OscType.Sine, 200.0, -4.0, 0.2, 2.0)
        synth.softNote(bus, 0.0, OscType.Sine, 200.0, 4.0, 0.2, 2.0)
        val out = render(1.0)
        // 8 cents of difference at 200 Hz = 0.92 Hz beating: the sum swells and fades within about a second
        assertTrue(rms(out, 400, 800) > 0.0)
        assertEquals(2, synth.activeVoices)
    }

    @Test
    fun aNoteOnAClosedBusIsIgnored() {
        bus.close()
        sine()
        assertEquals(0, synth.activeVoices)
        assertEquals(0L, synth.voicesStarted)
    }

    @Test
    fun stoppingTheVoicesOfABusSilencesIt() {
        sine(decay = 2.0)
        render(0.05)
        synth.stopVoicesOf(bus)
        assertEquals(0, synth.activeVoices)
        assertEquals(0f, peak(render(0.05)))
    }

    @Test
    fun anExhaustedPoolDropsNewNotesWithoutFailing() {
        val small = Synth(rate, createNoiseBuffer(sampleRate), capacity = 3)
        repeat(5) { small.tone(bus, 0.0, OscType.Sine, 100.0, 0.0, 0.1, 0.01, 0.0, 1.0, 0.5, 0.0) }
        assertEquals(3, small.activeVoices)
        assertEquals(3L, small.voicesStarted)
        assertEquals(2L, small.voicesDropped)
    }

    @Test
    fun aNoteCannotStartBeforeTheNextQuantum() {
        render(0.1) // clock at 0.1 s
        sine(t = 0.0, peak = 0.5) // in the past
        val out = render(0.05)
        assertTrue(peak(out) > 0.0f)
    }
}
