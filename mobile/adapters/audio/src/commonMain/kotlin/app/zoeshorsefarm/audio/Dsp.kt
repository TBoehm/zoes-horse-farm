// Small synthesis building blocks of the sound recipes. `v` is the voice context.
package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType
import app.zoeshorsefarm.audio.synth.VoiceSink

/** Length of the shared noise buffer in seconds. */
internal const val NOISE_SECONDS = 2.0

/**
 * What a sound recipe needs: where the notes go, a random source for jitter and noise offsets, and
 * a little state (hoof counter). The rng is consumed in the same order as in the web app.
 */
internal class VoiceContext(
    val sink: VoiceSink,
    val rng: RandomSource,
    val noiseSeconds: Double = NOISE_SECONDS,
) {
    /** Number of hoof beats played; alternates the pitch of the left and right hoof. */
    var hoofCount = 0
}

/** White noise for the noise voices, reproducible through the seeded generator. */
internal fun createNoiseBuffer(
    sampleRate: Int,
    rng: RandomSource = Mulberry32(1337),
    seconds: Double = NOISE_SECONDS,
): FloatArray {
    val length = (sampleRate * seconds).toInt()
    return FloatArray(length) { (rng.next() * 2 - 1).toFloat() }
}

/** Oscillator note: attack, optional hold, exponential decay; `freqEnd` > 0 glides the pitch. */
internal fun tone(
    v: VoiceContext,
    out: GainBus,
    t: Double,
    type: OscType = OscType.Sine,
    freq: Double,
    freqEnd: Double = 0.0,
    glide: Double = 0.1,
    attack: Double = 0.003,
    hold: Double = 0.0,
    decay: Double,
    peak: Double,
    detune: Double = 0.0,
) {
    v.sink.tone(out, t, type, freq, freqEnd, glide, attack, hold, decay, peak, detune)
}

/** Filtered noise burst; `freqEnd` > 0 sweeps the filter cutoff over the length of the burst. */
internal fun noiseBurst(
    v: VoiceContext,
    out: GainBus,
    t: Double,
    filter: FilterType = FilterType.Bandpass,
    freq: Double,
    freqEnd: Double = 0.0,
    q: Double = 1.0,
    attack: Double = 0.003,
    hold: Double = 0.0,
    decay: Double,
    peak: Double,
) {
    val offset = v.rng.next() * (v.noiseSeconds - 0.1)
    v.sink.noiseBurst(out, t, filter, freq, freqEnd, q, attack, hold, decay, peak, offset)
}

/** Random deviation around 1: jitter(0.05) -> 0.95..1.05. */
internal fun jitter(
    v: VoiceContext,
    amount: Double,
): Double = 1 + (v.rng.next() * 2 - 1) * amount
