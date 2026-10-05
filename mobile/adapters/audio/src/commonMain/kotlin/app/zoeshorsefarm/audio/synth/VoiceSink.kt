package app.zoeshorsefarm.audio.synth

/**
 * Where the sound recipes (sfx, music) put their notes. The [Synth] plays them; tests record them.
 * `freqEnd` 0 means no sweep. All times are seconds on the audio clock; the arguments are the
 * options of the web app's `tone` and `noiseBurst` helpers.
 */
internal interface VoiceSink {
    fun tone(
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
    )

    fun noiseBurst(
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
    )

    fun softNote(
        out: GainBus,
        time: Double,
        type: OscType,
        freq: Double,
        detune: Double,
        peak: Double,
        length: Double,
    )
}
