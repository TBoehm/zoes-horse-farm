package app.zoeshorsefarm.audio.synth

import kotlin.math.pow
import kotlin.math.sqrt

private const val GAIN_CALIBRATION_DB = -58.0
private const val GAIN_CALIBRATION_SAMPLE_RATE = 44100.0
private const val MIN_POWER = 0.000125

/**
 * The normalisation of the WebAudio `ConvolverNode` (`normalize = true`): scales the impulse so that
 * the perceived loudness of the wet signal matches the dry one. Based on the RMS of all channels.
 */
internal fun convolverNormalizationScale(
    impulse: Array<FloatArray>,
    sampleRate: Int,
): Float {
    var sum = 0.0
    var samples = 0
    for (channel in impulse) {
        for (x in channel) sum += x.toDouble() * x
        samples += channel.size
    }
    var power = sqrt(sum / samples)
    if (!power.isFinite() || power < MIN_POWER) power = MIN_POWER
    return (1 / power * 10.0.pow(GAIN_CALIBRATION_DB * 0.05) * GAIN_CALIBRATION_SAMPLE_RATE / sampleRate).toFloat()
}

/**
 * Mono input, stereo output convolution with a two-channel impulse (true stereo as a WebAudio
 * `ConvolverNode` does it for a mono input). Uniformly partitioned overlap-save with a frequency
 * domain delay line: the cost per sample is small even for an impulse of seconds. The output is
 * delayed by one [partition] (the time to collect a block). All memory is allocated up front;
 * [process] does not allocate.
 */
internal class PartitionedConvolver(
    impulse: Array<FloatArray>,
    private val partition: Int,
    scale: Float,
) {
    private val fftSize = partition * 2
    private val bins = partition + 1
    private val fft = Fft(fftSize)
    private val partitions = (impulse.maxOf { it.size } + partition - 1) / partition

    // Spectra (bins 0..partition) of the impulse partitions: [partition index * bins + bin]
    private val impulseLeftRe = FloatArray(partitions * bins)
    private val impulseLeftIm = FloatArray(partitions * bins)
    private val impulseRightRe = FloatArray(partitions * bins)
    private val impulseRightIm = FloatArray(partitions * bins)

    // Frequency domain delay line of the input spectra, newest at [newest]
    private val delayRe = FloatArray(partitions * bins)
    private val delayIm = FloatArray(partitions * bins)
    private var newest = 0

    private val previousInput = FloatArray(partition)
    private val currentInput = FloatArray(partition)
    private val outputLeft = FloatArray(partition)
    private val outputRight = FloatArray(partition)
    private var position = 0

    private val frameRe = FloatArray(fftSize)
    private val frameIm = FloatArray(fftSize)
    private val accLeftRe = FloatArray(bins)
    private val accLeftIm = FloatArray(bins)
    private val accRightRe = FloatArray(bins)
    private val accRightIm = FloatArray(bins)

    init {
        require(impulse.size == 2) { "A stereo impulse is required" }
        for (c in 0 until 2) {
            val outRe = if (c == 0) impulseLeftRe else impulseRightRe
            val outIm = if (c == 0) impulseLeftIm else impulseRightIm
            for (k in 0 until partitions) {
                frameRe.fill(0f)
                frameIm.fill(0f)
                val from = k * partition
                val to = minOf(from + partition, impulse[c].size)
                for (i in from until to) frameRe[i - from] = impulse[c][i] * scale
                fft.forward(frameRe, frameIm)
                frameRe.copyInto(outRe, k * bins, 0, bins)
                frameIm.copyInto(outIm, k * bins, 0, bins)
            }
        }
    }

    /** Convolves [frames] samples of [input] into [outLeft] and [outRight]. */
    fun process(
        input: FloatArray,
        outLeft: FloatArray,
        outRight: FloatArray,
        frames: Int,
    ) {
        for (i in 0 until frames) {
            currentInput[position] = input[i]
            outLeft[i] = outputLeft[position]
            outRight[i] = outputRight[position]
            position++
            if (position == partition) {
                computeBlock()
                position = 0
            }
        }
    }

    /** Clears the memory, e.g. when the tail has died out. */
    fun reset() {
        delayRe.fill(0f)
        delayIm.fill(0f)
        previousInput.fill(0f)
        currentInput.fill(0f)
        outputLeft.fill(0f)
        outputRight.fill(0f)
        position = 0
    }

    private fun computeBlock() {
        // Newest 2 * partition input samples -> spectrum -> delay line
        for (i in 0 until partition) {
            frameRe[i] = previousInput[i]
            frameRe[partition + i] = currentInput[i]
        }
        frameIm.fill(0f)
        currentInput.copyInto(previousInput)
        fft.forward(frameRe, frameIm)
        newest = if (newest == 0) partitions - 1 else newest - 1
        frameRe.copyInto(delayRe, newest * bins, 0, bins)
        frameIm.copyInto(delayIm, newest * bins, 0, bins)

        accLeftRe.fill(0f)
        accLeftIm.fill(0f)
        accRightRe.fill(0f)
        accRightIm.fill(0f)
        for (k in 0 until partitions) {
            var slot = newest + k
            if (slot >= partitions) slot -= partitions
            val x = slot * bins
            val h = k * bins
            for (b in 0 until bins) {
                val xr = delayRe[x + b]
                val xi = delayIm[x + b]
                val lr = impulseLeftRe[h + b]
                val li = impulseLeftIm[h + b]
                val rr = impulseRightRe[h + b]
                val ri = impulseRightIm[h + b]
                accLeftRe[b] += xr * lr - xi * li
                accLeftIm[b] += xr * li + xi * lr
                accRightRe[b] += xr * rr - xi * ri
                accRightIm[b] += xr * ri + xi * rr
            }
        }

        // Both channels in one inverse FFT: z = left + i * right (both spectra are Hermitian)
        for (b in 0 until bins) {
            frameRe[b] = accLeftRe[b] - accRightIm[b]
            frameIm[b] = accLeftIm[b] + accRightRe[b]
        }
        for (b in 1 until partition) {
            frameRe[fftSize - b] = accLeftRe[b] + accRightIm[b]
            frameIm[fftSize - b] = -accLeftIm[b] + accRightRe[b]
        }
        fft.inverse(frameRe, frameIm)
        for (i in 0 until partition) {
            outputLeft[i] = frameRe[partition + i]
            outputRight[i] = frameIm[partition + i]
        }
    }
}
