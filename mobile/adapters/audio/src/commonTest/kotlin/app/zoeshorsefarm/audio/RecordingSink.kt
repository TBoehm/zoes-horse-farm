package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType
import app.zoeshorsefarm.audio.synth.VoiceSink

/** A noise burst as the effect schedules it: filter type, center frequency, envelope peak and length. */
internal class NoiseBurstCall(
    val type: FilterType,
    val freq: Double,
    val peak: Double,
    val length: Double,
)

internal class ToneCall(
    val time: Double,
    val type: OscType,
    val freq: Double,
    val peak: Double,
    val length: Double,
)

/**
 * Recording stand-in for the synth: remembers which sounds an effect schedules, so that the tests can
 * check them without rendering (the web test does the same with a fake of the WebAudio graph).
 */
internal class RecordingSink : VoiceSink {
    val bursts = mutableListOf<NoiseBurstCall>()
    val tones = mutableListOf<ToneCall>()

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
        tones += ToneCall(time, type, freq, maxOf(peak, 0.0001), attack + hold + decay)
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
        bursts += NoiseBurstCall(filter, freq, maxOf(peak, 0.0001), attack + hold + decay)
    }

    override fun softNote(
        out: GainBus,
        time: Double,
        type: OscType,
        freq: Double,
        detune: Double,
        peak: Double,
        length: Double,
    ) {
        tones += ToneCall(time, type, freq, peak, length)
    }
}
