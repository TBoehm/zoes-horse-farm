package app.zoeshorsefarm.audio.synth

import app.zoeshorsefarm.audio.Mulberry32
import app.zoeshorsefarm.audio.generateImpulse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartitionedConvolverTest {
    private fun direct(
        input: FloatArray,
        ir: FloatArray,
    ): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            var sum = 0.0
            for (k in ir.indices) if (i - k >= 0) sum += input[i - k] * ir[k].toDouble()
            out[i] = sum.toFloat()
        }
        return out
    }

    private fun run(
        convolver: PartitionedConvolver,
        input: FloatArray,
        block: Int,
    ): Array<FloatArray> {
        val left = FloatArray(input.size)
        val right = FloatArray(input.size)
        var i = 0
        val inBlock = FloatArray(block)
        val outL = FloatArray(block)
        val outR = FloatArray(block)
        while (i < input.size) {
            val n = minOf(block, input.size - i)
            input.copyInto(inBlock, 0, i, i + n)
            convolver.process(inBlock, outL, outR, n)
            outL.copyInto(left, i, 0, n)
            outR.copyInto(right, i, 0, n)
            i += n
        }
        return arrayOf(left, right)
    }

    @Test
    fun equalsDirectConvolutionDelayedByOnePartition() {
        val partition = 16
        val rng = Mulberry32(21)
        val ir =
            arrayOf(
                FloatArray(150) { (rng.next() * 2 - 1).toFloat() * 0.3f },
                FloatArray(150) { (rng.next() * 2 - 1).toFloat() * 0.3f },
            )
        val input = FloatArray(400) { (rng.next() * 2 - 1).toFloat() }
        val convolver = PartitionedConvolver(ir, partition, scale = 1f)
        val (left, right) = run(convolver, input, block = 8)
        val expectedLeft = direct(input, ir[0])
        val expectedRight = direct(input, ir[1])
        for (i in partition until input.size) {
            assertEquals(expectedLeft[i - partition], left[i], 2e-4f, "left $i")
            assertEquals(expectedRight[i - partition], right[i], 2e-4f, "right $i")
        }
        for (i in 0 until partition) {
            assertEquals(0f, left[i])
            assertEquals(0f, right[i])
        }
    }

    @Test
    fun theResultDoesNotDependOnTheCallBlockSize() {
        val rng = Mulberry32(3)
        val ir =
            arrayOf(
                FloatArray(100) { (rng.next() * 2 - 1).toFloat() },
                FloatArray(100) { (rng.next() * 2 - 1).toFloat() },
            )
        val input = FloatArray(300) { (rng.next() * 2 - 1).toFloat() }
        val a = run(PartitionedConvolver(ir, 32, 1f), input, block = 128)
        val b = run(PartitionedConvolver(ir, 32, 1f), input, block = 7)
        for (i in input.indices) assertEquals(a[0][i], b[0][i], 1e-5f)
    }

    @Test
    fun appliesTheScaleToTheImpulse() {
        val ir = arrayOf(floatArrayOf(1f), floatArrayOf(1f))
        val input = FloatArray(64) { if (it == 0) 1f else 0f }
        val (left, _) = run(PartitionedConvolver(ir, 16, scale = 0.25f), input, block = 16)
        assertEquals(0.25f, left[16], 1e-6f)
    }

    @Test
    fun resetSilencesTheTail() {
        val ir = arrayOf(FloatArray(64) { 0.1f }, FloatArray(64) { 0.1f })
        val convolver = PartitionedConvolver(ir, 16, 1f)
        val loud = FloatArray(16) { 1f }
        val outL = FloatArray(16)
        val outR = FloatArray(16)
        repeat(3) { convolver.process(loud, outL, outR, 16) }
        convolver.reset()
        convolver.process(FloatArray(16), outL, outR, 16)
        assertTrue(outL.all { it == 0f })
    }

    @Test
    fun theTailOfTheRoomImpulseDecaysToSilence() {
        val sampleRate = 8000
        val ir = generateImpulse(sampleRate, seconds = 0.5)
        val convolver = PartitionedConvolver(ir, 64, 1f)
        val input = FloatArray(sampleRate) { if (it == 0) 1f else 0f }
        val (left, _) = run(convolver, input, block = 128)
        val early = (0 until 800).maxOf { abs(left[it]) }
        val late = (6000 until 8000).maxOf { abs(left[it]) }
        assertTrue(early > 0.05f)
        assertEquals(0f, late, 1e-6f)
    }

    @Test
    fun normalizationScaleFollowsTheWebAudioFormula() {
        // RMS 1 at 44.1 kHz: 10^(-58/20)
        val ones = arrayOf(FloatArray(100) { 1f }, FloatArray(100) { -1f })
        assertEquals(0.001258925f, convolverNormalizationScale(ones, 44100), 1e-7f)
        // the scale depends on the sample rate
        assertEquals(0.001258925f * 44100f / 48000f, convolverNormalizationScale(ones, 48000), 1e-7f)
        // a nearly silent impulse is limited by the minimum power 0.000125
        val quiet = arrayOf(FloatArray(10), FloatArray(10))
        assertEquals((1.0 / 0.000125 * 0.001258925).toFloat(), convolverNormalizationScale(quiet, 44100), 1e-3f)
    }
}
