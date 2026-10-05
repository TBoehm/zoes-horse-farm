package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType
import app.zoeshorsefarm.audio.synth.VoiceSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspTest {
    @Test
    fun theNoiseBufferIsWhiteNoiseInTheRangeMinusOneToOne() {
        val noise = createNoiseBuffer(sampleRate = 8000)
        assertEquals(16000, noise.size)
        assertTrue(noise.all { it >= -1f && it <= 1f })
        assertEquals(0.0, noise.average(), 0.03)
        // uncorrelated neighbours: the mean product is near zero
        var product = 0.0
        for (i in 1 until noise.size) product += noise[i] * noise[i - 1].toDouble()
        assertEquals(0.0, product / noise.size, 0.02)
    }

    @Test
    fun theNoiseBufferIsReproducibleAndMatchesTheWebSeed() {
        assertContentEquals(createNoiseBuffer(1000), createNoiseBuffer(1000))
        // mulberry32(1337): 0.1844118325971067 -> -0.6311763...
        assertEquals((0.1844118325971067 * 2 - 1).toFloat(), createNoiseBuffer(1000)[0])
    }

    @Test
    fun jitterStaysWithinTheGivenAmountAroundOne() {
        val v = VoiceContext(NullSink, Mulberry32(9))
        repeat(500) {
            val j = jitter(v, 0.05)
            assertTrue(j >= 0.95 && j <= 1.05)
        }
        assertEquals(1.0, jitter(VoiceContext(NullSink, RandomSource { 0.5 }), 0.3), 1e-12)
    }

    @Test
    fun aNoiseBurstDrawsItsStartOffsetFromTheRandomSourceWithinTheBuffer() {
        val offsets = mutableListOf<Double>()
        val sink =
            object : VoiceSink by NullSink {
                override fun noiseBurst(
                    out: GainBus,
                    time: Double,
                    filter: FilterType,
                    freq: Double,
                    freqEnd: Double,
                    q: Double,
                    attack: Double,
                    hold: Double,
                    decay: Double,
                    peak: Double,
                    offsetSeconds: Double,
                ) {
                    offsets += offsetSeconds
                }
            }
        val v = VoiceContext(sink, RandomSource { 0.5 })
        noiseBurst(v, GainBus(8000.0), 0.0, freq = 1000.0, decay = 0.1, peak = 0.5)
        assertEquals(listOf(0.5 * (2.0 - 0.1)), offsets)
    }

    @Test
    fun toneAndNoiseBurstPassTheWebDefaults() {
        var tone: DoubleArray? = null
        var burst: DoubleArray? = null
        val sink =
            object : VoiceSink by NullSink {
                override fun tone(
                    out: GainBus,
                    time: Double,
                    type: OscType,
                    freq: Double,
                    freqEnd: Double,
                    glide: Double,
                    attack: Double,
                    hold: Double,
                    decay: Double,
                    peak: Double,
                    detune: Double,
                ) {
                    tone = doubleArrayOf(freqEnd, glide, attack, hold, detune)
                    assertEquals(OscType.Sine, type)
                }

                override fun noiseBurst(
                    out: GainBus,
                    time: Double,
                    filter: FilterType,
                    freq: Double,
                    freqEnd: Double,
                    q: Double,
                    attack: Double,
                    hold: Double,
                    decay: Double,
                    peak: Double,
                    offsetSeconds: Double,
                ) {
                    burst = doubleArrayOf(freqEnd, q, attack, hold)
                    assertEquals(FilterType.Bandpass, filter)
                }
            }
        val v = VoiceContext(sink, RandomSource { 0.0 })
        val bus = GainBus(8000.0)
        tone(v, bus, 0.0, freq = 440.0, decay = 0.1, peak = 0.5)
        noiseBurst(v, bus, 0.0, freq = 440.0, decay = 0.1, peak = 0.5)
        assertContentEquals(doubleArrayOf(0.0, 0.1, 0.003, 0.0, 0.0), tone)
        assertContentEquals(doubleArrayOf(0.0, 1.0, 0.003, 0.0), burst)
    }

    private object NullSink : VoiceSink {
        override fun tone(
            out: GainBus,
            time: Double,
            type: OscType,
            freq: Double,
            freqEnd: Double,
            glide: Double,
            attack: Double,
            hold: Double,
            decay: Double,
            peak: Double,
            detune: Double,
        ) = Unit

        override fun noiseBurst(
            out: GainBus,
            time: Double,
            filter: FilterType,
            freq: Double,
            freqEnd: Double,
            q: Double,
            attack: Double,
            hold: Double,
            decay: Double,
            peak: Double,
            offsetSeconds: Double,
        ) = Unit

        override fun softNote(
            out: GainBus,
            time: Double,
            type: OscType,
            freq: Double,
            detune: Double,
            peak: Double,
            length: Double,
        ) = Unit
    }
}
