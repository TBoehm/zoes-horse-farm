package app.zoeshorsefarm.audio.synth

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val MAX_PRE_DELAY_FRAMES = 1024
private const val MAX_PRE_DELAY_MASK = MAX_PRE_DELAY_FRAMES - 1
private const val DIVISION_FRAMES = 32
private const val SPACING_DB = 5f

// Release zones of the adaptive release (fractions of the release time)
private const val RELEASE_ZONE_1 = 0.09f
private const val RELEASE_ZONE_2 = 0.16f
private const val RELEASE_ZONE_3 = 0.42f
private const val RELEASE_ZONE_4 = 0.98f

private fun linearToDecibels(x: Float): Float = if (x <= 0f) -1000f else 20f * log10(x)

private fun decibelsToLinear(db: Float): Float = 10f.pow(0.05f * db)

/**
 * Port of the WebAudio `DynamicsCompressorNode` as implemented by WebKit/Blink
 * (`DynamicsCompressorKernel`): soft-knee static curve, automatic makeup gain, 6 ms look-ahead
 * pre-delay, adaptive release and the sine warp of the gain. Stereo, processed in place in blocks
 * of a multiple of 32 frames. The defaults are the values the web app sets (threshold -10 dB,
 * knee 8 dB, ratio 10, attack 3 ms, release 250 ms).
 *
 * All state lives in preallocated fields; [process] does not allocate.
 */
internal class DynamicsCompressor(
    sampleRate: Int,
    private val dbThreshold: Float = -10f,
    private val dbKnee: Float = 8f,
    private val ratio: Float = 10f,
    attackTime: Float = 0.003f,
    releaseTime: Float = 0.25f,
    preDelayTime: Float = 0.006f,
) {
    private val preDelayLeft = FloatArray(MAX_PRE_DELAY_FRAMES)
    private val preDelayRight = FloatArray(MAX_PRE_DELAY_FRAMES)
    private var preDelayReadIndex = 0
    private var preDelayWriteIndex: Int

    private var detectorAverage = 0f
    private var compressorGain = 1f
    private var maxAttackCompressionDiffDb = -1f // uninitialized state

    // Static curve
    private val linearThreshold = decibelsToLinear(dbThreshold)
    private val slope = 1 / ratio
    private val kneeThresholdDb = dbThreshold + dbKnee
    private val kneeThreshold = decibelsToLinear(kneeThresholdDb)
    private val k: Float
    private val ykneeThresholdDb: Float
    private val masterLinearGain: Float

    private val attackFrames = maxOf(0.001f, attackTime) * sampleRate
    private val satReleaseFrames = 0.0025f * sampleRate

    // Adaptive release polynomial
    private val kA: Float
    private val kB: Float
    private val kC: Float
    private val kD: Float
    private val kE: Float

    init {
        val preDelayFrames = (preDelayTime * sampleRate).toInt().coerceAtMost(MAX_PRE_DELAY_FRAMES - 1)
        preDelayWriteIndex = preDelayFrames

        k = kAtSlope(1 / ratio)
        ykneeThresholdDb = linearToDecibels(kneeCurve(kneeThreshold, k))
        // Makeup gain with the empirical/perceptual tuning of the original
        val fullRangeMakeupGain = (1 / saturate(1f, k)).pow(0.6f)
        masterLinearGain = fullRangeMakeupGain

        val releaseFrames = sampleRate * releaseTime
        val y1 = releaseFrames * RELEASE_ZONE_1
        val y2 = releaseFrames * RELEASE_ZONE_2
        val y3 = releaseFrames * RELEASE_ZONE_3
        val y4 = releaseFrames * RELEASE_ZONE_4
        // Coefficients of a 4th order polynomial through the four zone points (x = 0..3)
        kA = 0.9999999999999998f * y1 + 1.8432219684323923e-16f * y2 - 1.9373394351676423e-16f * y3 +
            8.824516011816245e-18f * y4
        kB = -1.5788320352845888f * y1 + 2.3305837032074286f * y2 - 0.9141194204840429f * y3 +
            0.1623677525612032f * y4
        kC = 0.5334142869106424f * y1 - 1.272736789213631f * y2 + 0.9258856042207512f * y3 -
            0.18656310191776226f * y4
        kD = 0.08783463138207234f * y1 - 0.1694162967925622f * y2 + 0.08588057951595272f * y3 -
            0.00429891410546283f * y4
        kE = -0.042416883008123074f * y1 + 0.1115693827987602f * y2 - 0.09764676325265872f * y3 +
            0.028494263462021576f * y4
    }

    /** Exponential knee: first derivative matched at the linear threshold, approaches threshold + 1/k. */
    private fun kneeCurve(
        x: Float,
        k: Float,
    ): Float {
        if (x < linearThreshold) return x
        return linearThreshold + (1 - exp(-k * (x - linearThreshold))) / k
    }

    /** Full compression curve with a constant ratio after the knee. */
    private fun saturate(
        x: Float,
        k: Float,
    ): Float =
        if (x < kneeThreshold) {
            kneeCurve(x, k)
        } else {
            val xDb = linearToDecibels(x)
            val yDb = ykneeThresholdDb + slope * (xDb - kneeThresholdDb)
            decibelsToLinear(yDb)
        }

    private fun slopeAt(
        x: Float,
        k: Float,
    ): Float {
        if (x < linearThreshold) return 1f
        val x2 = x * 1.001f
        val xDb = linearToDecibels(x)
        val x2Db = linearToDecibels(x2)
        val yDb = linearToDecibels(kneeCurve(x, k))
        val y2Db = linearToDecibels(kneeCurve(x2, k))
        return (y2Db - yDb) / (x2Db - xDb)
    }

    private fun kAtSlope(desiredSlope: Float): Float {
        val x = decibelsToLinear(dbThreshold + dbKnee)
        var minK = 0.1f
        var maxK = 10000f
        var k = 5f
        for (i in 0 until 15) {
            // A high value for k approaches a slope of 0 faster
            if (slopeAt(x, k) < desiredSlope) maxK = k else minK = k
            k = sqrt(minK * maxK)
        }
        return k
    }

    /** Compresses [frames] samples (a multiple of 32) of both channels in place. */
    fun process(
        left: FloatArray,
        right: FloatArray,
        frames: Int,
    ) {
        require(frames % DIVISION_FRAMES == 0) { "frames must be a multiple of $DIVISION_FRAMES" }
        var frameIndex = 0
        for (division in 0 until frames / DIVISION_FRAMES) {
            if (detectorAverage.isNaN() || detectorAverage.isInfinite()) detectorAverage = 1f
            val desiredGain = detectorAverage

            // Pre-warp so that we get desiredGain after the sin() warp below
            val scaledDesiredGain = asin(desiredGain) / (0.5f * PI.toFloat())

            // The rate we slew from the current compressor level to the desired level
            val envelopeRate: Float
            val isReleasing = scaledDesiredGain > compressorGain
            var compressionDiffDb = linearToDecibels(compressorGain / scaledDesiredGain)
            if (isReleasing) {
                maxAttackCompressionDiffDb = -1f
                if (compressionDiffDb.isNaN() || compressionDiffDb.isInfinite()) compressionDiffDb = -1f
                // Higher compression (lower compressionDiffDb) releases faster: -12..0 dB -> x = 0..3
                var x = compressionDiffDb
                x = maxOf(-12f, x)
                x = minOf(0f, x)
                x = 0.25f * (x + 12)
                val x2 = x * x
                val x3 = x2 * x
                val x4 = x2 * x2
                val releaseFrames = kA + kB * x + kC * x2 + kD * x3 + kE * x4
                val dbPerFrame = SPACING_DB / releaseFrames
                envelopeRate = decibelsToLinear(dbPerFrame)
            } else {
                if (compressionDiffDb.isNaN() || compressionDiffDb.isInfinite()) compressionDiffDb = 1f
                // While still attacking, use the rate of the largest difference seen so far
                if (maxAttackCompressionDiffDb == -1f || maxAttackCompressionDiffDb < compressionDiffDb) {
                    maxAttackCompressionDiffDb = compressionDiffDb
                }
                val effAttenDiffDb = maxOf(0.5f, maxAttackCompressionDiffDb)
                val x = 0.25f / effAttenDiffDb
                envelopeRate = 1 - x.pow(1 / attackFrames)
            }

            var readIndex = preDelayReadIndex
            var writeIndex = preDelayWriteIndex
            var detector = detectorAverage
            var gain = compressorGain
            for (i in 0 until DIVISION_FRAMES) {
                // Pre-delay the signal, computing the compression amount from the undelayed one
                val l = left[frameIndex]
                val r = right[frameIndex]
                preDelayLeft[writeIndex] = l
                preDelayRight[writeIndex] = r
                val absL = if (l > 0f) l else -l
                val absR = if (r > 0f) r else -r
                val absInput = if (absL > absR) absL else absR

                // Shaped power of the undelayed input: linear up to the threshold, then knee and ratio
                val shapedInput = saturate(absInput, k)
                val attenuation = if (absInput <= 0.0001f) 1f else shapedInput / absInput
                var attenuationDb = -linearToDecibels(attenuation)
                attenuationDb = maxOf(2f, attenuationDb)
                val satReleaseRate = decibelsToLinear(attenuationDb / satReleaseFrames) - 1f
                val rate = if (attenuation > detector) satReleaseRate else 1f
                detector += (attenuation - detector) * rate
                detector = minOf(1f, detector)
                if (detector.isNaN() || detector.isInfinite()) detector = 1f

                // Exponential approach to the desired gain
                if (envelopeRate < 1f) {
                    gain += (scaledDesiredGain - gain) * envelopeRate // attack
                } else {
                    gain *= envelopeRate // release
                    gain = minOf(1f, gain)
                }

                // Warp the pre-compression gain to smooth out sharp exponential transition points
                val postWarpGain = sin(0.5f * PI.toFloat() * gain)
                val totalGain = masterLinearGain * postWarpGain
                left[frameIndex] = preDelayLeft[readIndex] * totalGain
                right[frameIndex] = preDelayRight[readIndex] * totalGain

                frameIndex++
                readIndex = (readIndex + 1) and MAX_PRE_DELAY_MASK
                writeIndex = (writeIndex + 1) and MAX_PRE_DELAY_MASK
            }
            preDelayReadIndex = readIndex
            preDelayWriteIndex = writeIndex
            detectorAverage = flushDenormal(detector)
            compressorGain = flushDenormal(gain)
        }
    }

    private fun flushDenormal(x: Float): Float = if (x > -1.17549435e-38f && x < 1.17549435e-38f) 0f else x
}
