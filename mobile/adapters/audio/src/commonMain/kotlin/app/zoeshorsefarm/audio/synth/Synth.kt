package app.zoeshorsefarm.audio.synth

import kotlin.math.ceil

/**
 * A pool of [SynthVoice]s. Plays the notes of the sound recipes ([VoiceSink]) and renders all
 * running voices into the buffers of their buses. The pool is allocated up front: when it is
 * exhausted a new note is dropped (the worst effect, railDown, needs about 30 voices).
 *
 * Not thread safe: the engine serialises access.
 */
internal class Synth(
    private val sampleRate: Double,
    noise: FloatArray,
    capacity: Int = VOICE_CAPACITY,
) : VoiceSink {
    private val voices = Array(capacity) { SynthVoice(sampleRate, noise) }

    /** Index of the first quantum frame that has not been rendered yet. */
    var currentFrame = 0L

    /** Number of notes started since creation (telemetry and tests). */
    var voicesStarted = 0L
        private set

    /** Notes that were dropped because the pool was exhausted. */
    var voicesDropped = 0L
        private set

    val activeVoices: Int
        get() {
            var n = 0
            for (v in voices) if (v.active) n++
            return n
        }

    private fun allocate(out: GainBus): SynthVoice? {
        if (!out.active) return null
        for (v in voices) {
            if (!v.active) {
                voicesStarted++
                return v
            }
        }
        voicesDropped++
        return null
    }

    // A note cannot start before the quantum that is rendered next
    private fun startFrame(time: Double): Long = maxOf(ceil(time * sampleRate).toLong(), currentFrame)

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
        val v = allocate(out) ?: return
        v.setupTone(out, startFrame(time), time, type, freq, freqEnd, glide, attack, hold, decay, peak, detune)
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
        val v = allocate(out) ?: return
        v.setupNoise(
            out,
            startFrame(time),
            time,
            filter,
            freq,
            freqEnd,
            q,
            attack,
            hold,
            decay,
            peak,
            offsetSeconds,
        )
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
        val v = allocate(out) ?: return
        v.setupSoftNote(out, startFrame(time), time, type, freq, detune, peak, length)
    }

    /** Renders the running voices of one quantum into their bus buffers and frees finished ones. */
    fun render(frames: Int) {
        for (v in voices) {
            if (!v.active) continue
            val out = v.bus
            if (out == null || !out.active) {
                v.stop()
            } else {
                v.render(currentFrame, frames)
            }
        }
    }

    /** Drops the running voices of [out] (the bus is about to be reused or closed). */
    fun stopVoicesOf(out: GainBus) {
        for (v in voices) if (v.active && v.bus === out) v.stop()
    }

    companion object {
        const val VOICE_CAPACITY = 160
    }
}
