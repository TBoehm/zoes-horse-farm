package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicTest {
    private val out = GainBus(8000.0)

    private fun play(event: MusicEvent): RecordingSink {
        val sink = RecordingSink()
        playMusicEvent(VoiceContext(sink, RandomSource { 0.5 }), out, event, 1.5)
        return sink
    }

    @Test
    fun theMelodyIsPlayedByTwoSlightlyDetunedTriangleVoices() {
        val sink = play(MusicEvent(MusicVoice.Melody, 72, 2))
        assertEquals(2, sink.tones.size)
        for (tone in sink.tones) {
            assertEquals(OscType.Triangle, tone.type)
            assertEquals(midiToFreq(72), tone.freq, 1e-9)
            assertEquals(0.1, tone.peak, 1e-12)
            assertEquals(2 * STEP_SECONDS, tone.length, 1e-12)
            assertEquals(1.5, tone.time)
        }
    }

    @Test
    fun theBassIsASineWithAnOctaveOvertone() {
        val sink = play(MusicEvent(MusicVoice.Bass, 48, 3))
        assertEquals(listOf(OscType.Sine, OscType.Triangle), sink.tones.map { it.type })
        assertEquals(listOf(midiToFreq(48), midiToFreq(48) * 2), sink.tones.map { it.freq })
        assertEquals(listOf(0.3, 0.04), sink.tones.map { it.peak })
    }

    @Test
    fun theArpeggioIsAShortPluckWithAnOvertone() {
        val sink = play(MusicEvent(MusicVoice.Arp, 64, 2))
        assertEquals(2, sink.tones.size)
        assertEquals(midiToFreq(64) * 2, sink.tones[1].freq, 1e-9)
        assertTrue(sink.tones.all { it.length < 0.4 })
    }

    @Test
    fun theTickIsAQuietHighpassedBrush() {
        val sink = play(MusicEvent(MusicVoice.Tick, 0, 1))
        assertEquals(0, sink.tones.size)
        val burst = sink.bursts.single()
        assertEquals(FilterType.Highpass, burst.type)
        assertEquals(5200.0, burst.freq, 1e-9)
        assertEquals(0.03, burst.peak, 1e-12)
    }

    @Test
    fun theWholeLoopCanBePlayedThroughTheRecipes() {
        val sink = RecordingSink()
        val v = VoiceContext(sink, Mulberry32(2024))
        for (events in buildLoop()) for (event in events) playMusicEvent(v, out, event, 0.0)
        // 16 bars: melody notes x2, bass notes x2, arp x2, ticks
        assertTrue(sink.tones.size > 150)
        assertEquals(32, sink.bursts.size)
    }
}
