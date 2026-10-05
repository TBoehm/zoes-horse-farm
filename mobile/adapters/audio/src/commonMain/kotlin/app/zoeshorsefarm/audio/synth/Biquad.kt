package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Filter types of the WebAudio `BiquadFilterNode` that the sounds use. */
internal enum class FilterType(
    val id: String,
) {
    Lowpass("lowpass"),
    Highpass("highpass"),
    Bandpass("bandpass"),
}

/**
 * Second-order filter with the coefficients of the WebAudio specification (RBJ audio EQ cookbook).
 * As in WebAudio, `q` is in decibels for lowpass and highpass (resonance at the cutoff) and a
 * linear Q for bandpass (constant 0 dB peak gain). Transposed direct form II in doubles.
 */
internal class Biquad(
    sampleRate: Double,
) {
    private val nyquist = sampleRate / 2

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    private var type = FilterType.Lowpass
    private var frequency = Double.NaN
    private var q = Double.NaN

    /** Sets the filter. Calling it again with the same values keeps the coefficients and the state. */
    fun configure(
        type: FilterType,
        frequencyHz: Double,
        q: Double,
    ) {
        if (type == this.type && frequencyHz == frequency && q == this.q) return
        this.type = type
        frequency = frequencyHz
        this.q = q
        val cutoff = frequencyHz / nyquist // 0..1, 1 = Nyquist
        when (type) {
            FilterType.Lowpass -> lowpass(cutoff, q)
            FilterType.Highpass -> highpass(cutoff, q)
            FilterType.Bandpass -> bandpass(cutoff, q)
        }
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    private fun set(
        nb0: Double,
        nb1: Double,
        nb2: Double,
        na0: Double,
        na1: Double,
        na2: Double,
    ) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    private fun passAll() = set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    private fun blockAll() = set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    private fun lowpass(
        cutoff: Double,
        qDb: Double,
    ) {
        when {
            cutoff >= 1.0 -> {
                passAll()
            }

            cutoff <= 0.0 -> {
                blockAll()
            }

            else -> {
                val w0 = PI * cutoff
                val alpha = sin(w0) / (2 * 10.0.pow(qDb / 20))
                val c = cos(w0)
                set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }

    private fun highpass(
        cutoff: Double,
        qDb: Double,
    ) {
        when {
            cutoff >= 1.0 -> {
                blockAll()
            }

            cutoff <= 0.0 -> {
                passAll()
            }

            else -> {
                val w0 = PI * cutoff
                val alpha = sin(w0) / (2 * 10.0.pow(qDb / 20))
                val c = cos(w0)
                set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }

    private fun bandpass(
        cutoff: Double,
        q: Double,
    ) {
        when {
            cutoff <= 0.0 || cutoff >= 1.0 -> {
                blockAll()
            }

            q <= 0.0 -> {
                passAll()
            }

            else -> {
                val w0 = PI * cutoff
                val alpha = sin(w0) / (2 * q)
                val c = cos(w0)
                set(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }
}
