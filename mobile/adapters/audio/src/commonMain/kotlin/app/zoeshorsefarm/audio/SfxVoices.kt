// Effects: pure synthesis. Each function schedules its sounds from time `t` onto the output `out`.
package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType

/** The sound recipes of the effects, one function per effect. */
internal object SfxVoices {
    // Sand surface: dull thud (sine with pitch drop) + soft noise + a short mid-range "clop".
    // The clop is what small speakers (phones, tablets) can actually play: they reproduce next to
    // nothing below about 350 Hz.
    private class Gait(
        val vol: Double,
        val freq: Double,
        val noiseFreq: Double,
        val noiseDecay: Double,
        val clopFreq: Double,
    )

    private val GAITS =
        mapOf(
            // rein-back: slow, soft steps
            "back" to Gait(vol = 0.58, freq = 100.0, noiseFreq = 480.0, noiseDecay = 0.07, clopFreq = 1250.0),
            "walk" to Gait(vol = 0.62, freq = 105.0, noiseFreq = 520.0, noiseDecay = 0.07, clopFreq = 1300.0),
            "trot" to Gait(vol = 0.8, freq = 122.0, noiseFreq = 700.0, noiseDecay = 0.06, clopFreq = 1500.0),
            "canter" to Gait(vol = 0.98, freq = 138.0, noiseFreq = 860.0, noiseDecay = 0.085, clopFreq = 1700.0),
        )

    // Hoof on sand: band-passed noise knock (about 20 ms) plus a tiny falling "tock" tone.
    private fun clop(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        freq: Double,
        peak: Double,
    ) {
        noiseBurst(v, out, t, freq = freq, q = 1.0, attack = 0.001, decay = 0.02, peak = peak)
        tone(v, out, t, freq = freq * 0.7, freqEnd = freq * 0.45, glide = 0.02, decay = 0.03, peak = peak * 0.2)
    }

    private fun thud(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        freq: Double,
        peak: Double,
        decay: Double = 0.1,
        sand: Double = 1.0,
    ) {
        tone(v, out, t, freq = freq, freqEnd = freq * 0.42, glide = 0.07, decay = decay, peak = peak)
        noiseBurst(
            v,
            out,
            t,
            filter = FilterType.Lowpass,
            freq = 380.0,
            q = 0.7,
            decay = decay * 0.9,
            peak = peak * 0.5 * sand,
        )
    }

    private val GAIT_NAMES = listOf("back", "walk", "trot", "canter")
    private val GAIT_BY_INDEX = GAIT_NAMES.map { GAITS.getValue(it) }

    /** Index of a gait for [hoofAt], -1 for gaits without a hoof sound. Does not allocate. */
    fun gaitIndex(gait: String): Int = GAIT_NAMES.indexOf(gait)

    /** One hoof beat of the given gait ("back", "walk", "trot", "canter"); other gaits are silent. */
    fun hoof(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        gait: String,
    ) = hoofAt(v, out, t, gaitIndex(gait))

    /** [hoof] for a gait given by its [gaitIndex]. */
    fun hoofAt(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        gaitIndex: Int,
    ) {
        val g = GAIT_BY_INDEX.getOrNull(gaitIndex) ?: return
        v.hoofCount++
        val side = if (v.hoofCount % 2 == 0) 1.0 else 1.06 // left/right hoof slightly different
        val vol = g.vol * jitter(v, 0.1)
        val freq = g.freq * side * jitter(v, 0.04)
        tone(v, out, t, freq = freq, freqEnd = freq * 0.45, glide = 0.06, decay = 0.07, peak = 0.55 * vol)
        noiseBurst(
            v,
            out,
            t,
            freq = g.noiseFreq * jitter(v, 0.08),
            q = 0.8,
            attack = 0.004,
            decay = g.noiseDecay,
            peak = 0.5 * vol,
        )
        clop(v, out, t, freq = g.clopFreq * jitter(v, 0.08), peak = 1.4 * vol)
        noiseBurst(v, out, t, filter = FilterType.Lowpass, freq = 260.0, decay = 0.06, peak = 0.3 * vol)
        noiseBurst(
            v,
            out,
            t,
            filter = FilterType.Highpass,
            freq = 2600.0,
            attack = 0.001,
            decay = 0.012,
            peak = 0.035 * vol,
        )
    }

    fun takeoff(
        v: VoiceContext,
        out: GainBus,
        t: Double,
    ) {
        thud(v, out, t, freq = 150.0, peak = 0.6, decay = 0.1)
        thud(v, out, t + 0.085, freq = 175.0, peak = 0.85, decay = 0.12)
        // Snort: burst of air, filter falls
        noiseBurst(
            v,
            out,
            t + 0.05,
            freq = 1900.0,
            freqEnd = 700.0,
            q = 1.2,
            attack = 0.05,
            decay = 0.28,
            peak = 0.4,
        )
        noiseBurst(v, out, t + 0.06, freq = 3200.0, q = 3.0, attack = 0.04, decay = 0.2, peak = 0.07)
    }

    fun landing(
        v: VoiceContext,
        out: GainBus,
        t: Double,
    ) {
        thud(v, out, t, freq = 112.0, peak = 0.75, decay = 0.15)
        thud(v, out, t + 0.07, freq = 100.0, peak = 0.65, decay = 0.16)
        noiseBurst(v, out, t, freq = 800.0, q = 0.7, decay = 0.13, peak = 0.7)
        noiseBurst(v, out, t + 0.03, freq = 1400.0, q = 0.7, attack = 0.03, decay = 0.2, peak = 0.3)
        clop(v, out, t, freq = 1400.0 * jitter(v, 0.05), peak = 2.2)
        clop(v, out, t + 0.07, freq = 1250.0 * jitter(v, 0.05), peak = 1.8)
    }

    private fun woodClack(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        freq: Double,
        peak: Double,
    ) {
        tone(v, out, t, freq = freq, freqEnd = freq * 0.92, glide = 0.05, attack = 0.001, decay = 0.07, peak = peak)
        tone(v, out, t, freq = freq * 2.7, attack = 0.001, decay = 0.035, peak = peak * 0.45)
        noiseBurst(v, out, t, freq = freq * 3, q = 2.5, attack = 0.001, decay = 0.025, peak = peak * 0.6)
    }

    // Wood on wood: hits get denser and quieter, then impact and bouncing on sand. Per hit: delay in
    // seconds, frequency in Hz, peak.
    private val RAIL_HITS =
        arrayOf(
            doubleArrayOf(0.0, 780.0, 0.8),
            doubleArrayOf(0.085, 640.0, 0.6),
            doubleArrayOf(0.14, 910.0, 0.44),
            doubleArrayOf(0.18, 700.0, 0.36),
            doubleArrayOf(0.21, 820.0, 0.24),
            doubleArrayOf(0.235, 740.0, 0.16),
        )

    fun railDown(
        v: VoiceContext,
        out: GainBus,
        t: Double,
    ) {
        for (hit in RAIL_HITS) woodClack(v, out, t + hit[0], hit[1] * jitter(v, 0.05), hit[2])
        thud(v, out, t + 0.3, freq = 95.0, peak = 0.85, decay = 0.16, sand = 1.1)
        thud(v, out, t + 0.39, freq = 110.0, peak = 0.3, decay = 0.08)
        woodClack(v, out, t + 0.385, 600.0, 0.3)
        woodClack(v, out, t + 0.43, 680.0, 0.12)
    }

    // Bell with inharmonic partials (Risset), plus a short strike click. Per partial: frequency ratio,
    // amplitude, life (decay factor).
    private val BELL =
        arrayOf(
            doubleArrayOf(0.56, 1.0, 1.0),
            doubleArrayOf(0.92, 0.67, 0.9),
            doubleArrayOf(1.19, 1.0, 0.65),
            doubleArrayOf(1.71, 1.8, 0.55),
            doubleArrayOf(2.0, 2.67, 0.33),
            doubleArrayOf(2.74, 1.67, 0.35),
            doubleArrayOf(3.0, 1.46, 0.25),
            doubleArrayOf(3.76, 1.33, 0.2),
            doubleArrayOf(4.07, 1.33, 0.15),
        )

    fun startSignal(
        v: VoiceContext,
        out: GainBus,
        t: Double,
    ) {
        val f0 = 440.0
        val total = BELL.sumOf { it[1] }
        for (partial in BELL) {
            tone(
                v,
                out,
                t,
                freq = f0 * partial[0],
                attack = 0.002,
                decay = 2.2 * partial[2],
                peak = 1.08 * partial[1] / total,
            )
        }
        noiseBurst(v, out, t, filter = FilterType.Highpass, freq = 3000.0, attack = 0.001, decay = 0.03, peak = 0.1)
    }

    private fun chime(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        midi: Int,
        peak: Double,
        decay: Double,
    ) {
        val f = midiToFreq(midi)
        tone(v, out, t, type = OscType.Triangle, freq = f, attack = 0.003, decay = decay, peak = peak)
        tone(v, out, t, freq = f * 2.76, attack = 0.002, decay = decay * 0.3, peak = peak * 0.25)
    }

    private val FINISH_RUN = intArrayOf(72, 76, 79, 76, 79, 84)
    private val FINISH_CHORD = intArrayOf(72, 76, 79, 84, 88)

    fun finishSignal(
        v: VoiceContext,
        out: GainBus,
        t: Double,
    ) {
        // Ascending run in C major, then a bright final chord
        FINISH_RUN.forEachIndexed { i, midi -> chime(v, out, t + i * 0.09, midi, 0.32, 0.3) }
        val end = t + FINISH_RUN.size * 0.09 + 0.04
        for (midi in FINISH_CHORD) chime(v, out, end, midi, 0.2, 0.9)
    }

    /** Plays the effect with the ordinal [nameOrdinal] of [SfxName] (the command of the audio thread). */
    fun play(
        v: VoiceContext,
        out: GainBus,
        t: Double,
        nameOrdinal: Int,
        gaitIndex: Int,
    ) {
        when (SfxName.entries[nameOrdinal]) {
            SfxName.Hoof -> hoofAt(v, out, t, gaitIndex)
            SfxName.Takeoff -> takeoff(v, out, t)
            SfxName.Landing -> landing(v, out, t)
            SfxName.RailDown -> railDown(v, out, t)
            SfxName.StartSignal -> startSignal(v, out, t)
            SfxName.FinishSignal -> finishSignal(v, out, t)
        }
    }
}
