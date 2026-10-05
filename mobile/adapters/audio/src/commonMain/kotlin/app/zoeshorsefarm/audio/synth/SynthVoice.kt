package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Gain at the start and end of the envelopes: exponential ramps cannot reach zero. */
internal const val SILENT = 0.0001

/** The source is stopped this long after its envelope ended. */
private const val STOP_MARGIN = 0.03
private const val SOFT_NOTE_STOP = 0.18

/** Oscillator waveforms of the sounds (the WebAudio `OscillatorNode` types that are used). */
internal enum class OscType(
    val id: String,
) {
    Sine("sine"),
    Triangle("triangle"),
}

/** Wave table of one sine period (plus the wrap-around point) for linear interpolation. */
private object SineTable {
    const val SIZE = 2048
    val table = FloatArray(SIZE + 1) { sin(2 * PI * it / SIZE).toFloat() }

    fun at(phase: Double): Double {
        val position = phase * SIZE
        val index = position.toInt()
        val fraction = position - index
        val a = table[index]
        return a + (table[index + 1] - a) * fraction
    }
}

private fun triangleAt(phase: Double): Double =
    when {
        phase < 0.25 -> 4 * phase
        phase < 0.75 -> 2 - 4 * phase
        else -> 4 * phase - 4
    }

/**
 * One sound source with its filter and gain envelope: oscillator or looped noise, then an optional
 * biquad filter, then the envelope. Equivalent of the WebAudio chain
 * `source -> [filter] -> gain -> bus` that the web app builds for every note.
 *
 * Voices are pooled and configured by [Synth]; [render] adds the samples of one quantum to the bus.
 */
internal class SynthVoice(
    private val sampleRate: Double,
    private val noise: FloatArray,
) {
    var active = false
    var bus: GainBus? = null
        private set
    private var startFrame = 0L
    private var stopFrame = 0L

    private var isNoise = false

    // Oscillator
    private var oscType = OscType.Sine
    private var phase = 0.0
    private var baseFrequency = 0.0
    private var detuneFactor = 1.0
    private var frequencyRamp = false

    // Noise and filter
    private var noisePosition = 0
    private val filter = Biquad(sampleRate)
    private var filterType = FilterType.Lowpass
    private var filterQ = 1.0
    private var filterRamp = false
    private var filterCounter = 0

    /** Oscillator frequency or filter cutoff, depending on the voice. */
    private val frequency = AutomationParam(sampleRate, 0.0, capacity = 4)
    private val envelope = AutomationParam(sampleRate, 0.0, capacity = 6)

    private fun begin(
        out: GainBus,
        start: Long,
        stopSeconds: Double,
    ) {
        bus = out
        startFrame = start
        stopFrame = maxOf(start + 1, ceil(stopSeconds * sampleRate).toLong())
        frequency.reset(start, 0.0)
        envelope.reset(start, 0.0)
        active = true
    }

    /** Oscillator with an optional exponential glide of the frequency and an attack-hold-decay envelope. */
    fun setupTone(
        out: GainBus,
        start: Long,
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
        val end = time + attack + hold + decay
        begin(out, start, end + STOP_MARGIN)
        isNoise = false
        oscType = type
        phase = 0.0
        baseFrequency = freq
        detuneFactor = 2.0.pow(detune / 1200)
        frequencyRamp = freqEnd > 0.0
        if (frequencyRamp) {
            frequency.setValueAtTime(freq, time)
            frequency.exponentialRampToValueAtTime(freqEnd, time + glide)
        }
        setupEnvelope(time, attack, hold, decay, peak)
    }

    /** Looped noise through a filter (cutoff optionally sweeping exponentially) with an envelope. */
    fun setupNoise(
        out: GainBus,
        start: Long,
        time: Double,
        type: FilterType,
        freq: Double,
        freqEnd: Double,
        q: Double,
        attack: Double,
        hold: Double,
        decay: Double,
        peak: Double,
        offsetSeconds: Double,
    ) {
        val end = time + attack + hold + decay
        begin(out, start, end + STOP_MARGIN)
        isNoise = true
        noisePosition = ((offsetSeconds * sampleRate).toInt() and Int.MAX_VALUE) % noise.size
        filter.reset()
        filterType = type
        filterQ = q
        filterRamp = freqEnd > 0.0
        filterCounter = 0
        filter.configure(type, freq, q)
        if (filterRamp) {
            frequency.setValueAtTime(freq, time)
            frequency.exponentialRampToValueAtTime(freqEnd, end)
        }
        setupEnvelope(time, attack, hold, decay, peak)
    }

    /** Soft note of the melody: short attack, slow decay to half, release after the note length. */
    fun setupSoftNote(
        out: GainBus,
        start: Long,
        time: Double,
        type: OscType,
        freq: Double,
        detune: Double,
        peak: Double,
        length: Double,
    ) {
        val hold = time + maxOf(0.05, length * 0.9)
        begin(out, start, hold + SOFT_NOTE_STOP)
        isNoise = false
        oscType = type
        phase = 0.0
        baseFrequency = freq
        detuneFactor = 2.0.pow(detune / 1200)
        frequencyRamp = false
        envelope.setValueAtTime(SILENT, time)
        envelope.exponentialRampToValueAtTime(peak, time + 0.015)
        envelope.exponentialRampToValueAtTime(peak * 0.5, hold)
        envelope.exponentialRampToValueAtTime(SILENT, hold + 0.14)
    }

    // Attack, optional hold, exponential decay
    private fun setupEnvelope(
        time: Double,
        attack: Double,
        hold: Double,
        decay: Double,
        peak: Double,
    ) {
        val level = maxOf(peak, SILENT)
        envelope.setValueAtTime(SILENT, time)
        envelope.exponentialRampToValueAtTime(level, time + attack)
        if (hold > 0) envelope.setValueAtTime(level, time + attack + hold)
        envelope.exponentialRampToValueAtTime(SILENT, time + attack + hold + decay)
    }

    /** Adds the samples of the quantum that starts at [quantumStart] to the bus buffer. */
    fun render(
        quantumStart: Long,
        frames: Int,
    ) {
        val out = bus ?: return
        val quantumEnd = quantumStart + frames
        if (stopFrame <= quantumStart) {
            active = false
            return
        }
        if (startFrame >= quantumEnd) return
        val from = (maxOf(startFrame, quantumStart) - quantumStart).toInt()
        val to = (minOf(stopFrame, quantumEnd) - quantumStart).toInt()
        if (isNoise) renderNoise(out.buffer, from, to) else renderOscillator(out.buffer, from, to)
        if (stopFrame <= quantumEnd) active = false
    }

    private fun renderOscillator(
        buffer: FloatArray,
        from: Int,
        to: Int,
    ) {
        val inverseRate = 1.0 / sampleRate
        var p = phase
        for (i in from until to) {
            val hz = (if (frequencyRamp) frequency.next() else baseFrequency) * detuneFactor
            val x = if (oscType == OscType.Sine) SineTable.at(p) else triangleAt(p)
            buffer[i] += (x * envelope.next()).toFloat()
            p += hz * inverseRate
            if (p >= 1.0) p -= floor(p)
        }
        phase = p
    }

    private fun renderNoise(
        buffer: FloatArray,
        from: Int,
        to: Int,
    ) {
        var position = noisePosition
        for (i in from until to) {
            if (filterRamp) {
                val cutoff = frequency.next()
                // The cutoff is updated every 8 samples: coefficients are expensive, sweeps are slow
                if ((filterCounter++ and 7) == 0) filter.configure(filterType, cutoff, filterQ)
            }
            val x = filter.process(noise[position].toDouble())
            buffer[i] += (x * envelope.next()).toFloat()
            if (++position == noise.size) position = 0
        }
        noisePosition = position
    }

    fun stop() {
        active = false
    }
}
