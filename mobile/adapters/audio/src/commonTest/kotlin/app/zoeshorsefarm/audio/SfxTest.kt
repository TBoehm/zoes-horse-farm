package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SfxTest {
    private val out = GainBus(8000.0)

    private fun record(play: (VoiceContext, GainBus, Double) -> Unit): RecordingSink {
        val sink = RecordingSink()
        play(VoiceContext(sink, Mulberry32(1)), out, 0.0)
        return sink
    }

    // Small speakers (phones, tablets) hardly play anything below 350 Hz: every footfall and landing
    // needs a short mid-range "clop" on top of the dull thud.
    private fun isClop(b: NoiseBurstCall) =
        b.type == FilterType.Bandpass && b.freq in 1000.0..2500.0 && b.length in 0.015..0.03

    private fun clopsOf(sink: RecordingSink) = sink.bursts.filter(::isClop)

    @Test
    fun everyHoofHasAClopBetween1And25KhzThatIsClearlyAudible() {
        for (gait in listOf("back", "walk", "trot", "canter")) {
            val clop = clopsOf(record { v, o, t -> hoof(v, o, t, gait) })
            assertTrue(clop.isNotEmpty(), gait)
            assertTrue(clop.maxOf { it.peak } >= 0.7, gait)
        }
    }

    @Test
    fun landingHasAClopForEachOfItsTwoHoofImpacts() {
        val clop = clopsOf(record { v, o, t -> landing(v, o, t) })
        assertTrue(clop.size >= 2)
        assertTrue(clop.maxOf { it.peak } >= 1.5)
    }

    @Test
    fun theLouderGaitsAreNotQuieterInTheMidRange() {
        fun peak(gait: String) = clopsOf(record { v, o, t -> hoof(v, o, t, gait) }).maxOf { it.peak }
        assertTrue(peak("canter") >= peak("trot"))
        assertTrue(peak("trot") >= peak("walk"))
        assertTrue(peak("walk") >= peak("back"))
    }

    @Test
    fun anUnknownGaitSchedulesNothingAndDoesNotAdvanceTheHoofCounter() {
        val sink = RecordingSink()
        val v = VoiceContext(sink, Mulberry32(1))
        hoof(v, out, 0.0, "halt")
        assertEquals(0, sink.tones.size + sink.bursts.size)
        assertEquals(0, v.hoofCount)
        hoof(v, out, 0.0, "walk")
        assertEquals(1, v.hoofCount)
    }

    @Test
    fun theHoofsAlternateBetweenALeftAndARightPitch() {
        val sink = RecordingSink()
        val v = VoiceContext(sink, RandomSource { 0.5 }) // no jitter
        hoof(v, out, 0.0, "walk")
        hoof(v, out, 0.0, "walk")
        val thuds = sink.tones.filter { it.freq in 90.0..130.0 }
        assertEquals(listOf(105.0 * 1.06, 105.0), thuds.map { it.freq })
    }

    @Test
    fun theEffectsConsumeTheRandomSourceInTheSameOrderAsTheWebApp() {
        // hoof: jitter(vol), jitter(freq), jitter(noise freq), noise offset, jitter(clop), noise offset (clop),
        // noise offset (lowpass), noise offset (highpass)
        val counted = CountingRandom()
        hoof(VoiceContext(RecordingSink(), counted), out, 0.0, "trot")
        assertEquals(8, counted.calls)
        val landingRandom = CountingRandom()
        landing(VoiceContext(RecordingSink(), landingRandom), out, 0.0)
        // 2 thuds, 2 bursts, 2 clops (jitter + noise offset each)
        assertEquals(8, landingRandom.calls)
    }

    @Test
    fun theStartSignalIsABellOfNinePartialsPlusAClick() {
        val sink = record { v, o, t -> startSignal(v, o, t) }
        assertEquals(9, sink.tones.size)
        assertEquals(1, sink.bursts.size)
        assertEquals(440.0 * 0.56, sink.tones.first().freq, 1e-9)
        // the amplitudes are normalised: the partial peaks add up to 1.08
        assertEquals(1.08, sink.tones.sumOf { it.peak }, 1e-9)
    }

    @Test
    fun theFinishSignalIsARunOfSixChimesAndAFiveNoteChord() {
        val sink = record { v, o, t -> finishSignal(v, o, t) }
        assertEquals(2 * (6 + 5), sink.tones.size)
        val triangles = sink.tones.filter { it.type == OscType.Triangle }
        assertEquals(11, triangles.size)
        assertEquals(midiToFreq(72), triangles.first().freq, 1e-9)
        assertEquals(midiToFreq(88), triangles.last().freq, 1e-9)
        // the chord starts after the run
        assertEquals(6 * 0.09 + 0.04, triangles.last().time, 1e-9)
    }

    @Test
    fun theRailFallsWithSixDenserAndQuieterHitsBeforeTheImpact() {
        val sink = record { v, o, t -> railDown(v, o, t) }
        val clacks = sink.tones.filter { it.freq in 400.0..1000.0 && it.length > 0.07 && it.length < 0.072 }
        assertEquals(8, clacks.size)
        val firstSix = clacks.take(6)
        for (i in 1 until firstSix.size) assertTrue(firstSix[i].peak < firstSix[i - 1].peak)
        for (i in 2 until firstSix.size) {
            assertTrue(firstSix[i].time - firstSix[i - 1].time < firstSix[i - 1].time - firstSix[i - 2].time)
        }
    }

    @Test
    fun takeoffHasTwoThudsAndASnort() {
        val sink = record { v, o, t -> takeoff(v, o, t) }
        assertEquals(2, sink.tones.size)
        assertEquals(4, sink.bursts.size)
        assertEquals(1, sink.bursts.count { it.type == FilterType.Bandpass && it.freq == 1900.0 })
    }

    private class CountingRandom : RandomSource {
        var calls = 0

        override fun next(): Double {
            calls++
            return 0.5
        }
    }
}
