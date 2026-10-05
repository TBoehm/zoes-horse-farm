package app.zoeshorsefarm.audio

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock

private const val BLOCK_FRAMES = 512
private const val BUFFER_SECONDS = 0.1

/**
 * Converts planar float samples (-1..1) to interleaved 16-bit little-endian stereo PCM.
 * Returns the number of bytes written to [out].
 */
internal fun floatToPcm16(
    left: FloatArray,
    right: FloatArray,
    frames: Int,
    out: ByteArray,
): Int {
    var o = 0
    for (i in 0 until frames) {
        val l = (left[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        val r = (right[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        out[o++] = l.toByte()
        out[o++] = (l shr 8).toByte()
        out[o++] = r.toByte()
        out[o++] = (r shr 8).toByte()
    }
    return o
}

/**
 * Desktop output through a `javax.sound.sampled.SourceDataLine`: a daemon thread renders blocks and
 * writes them to the line (the blocking write paces it). For development and the JVM tests of the
 * app; not shipped on the phones.
 */
class JvmPcmOutput(
    override val sampleRate: Int = 44100,
) : PcmOutput {
    @Volatile
    override var state = OutputState.Suspended
        private set

    @Volatile
    private var listener: ((OutputState) -> Unit)? = null
    private var line: SourceDataLine? = null
    private var thread: Thread? = null
    private val pauseLock = ReentrantLock()
    private val resumed = pauseLock.newCondition()

    @Volatile
    private var playing = false

    @Volatile
    private var closed = false

    override fun setStateListener(listener: ((OutputState) -> Unit)?) {
        this.listener = listener
    }

    private fun setState(next: OutputState) {
        if (state == next) return
        state = next
        listener?.invoke(next)
    }

    override fun start(renderer: PcmRenderer) {
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        // Throws when there is no audio device
        val newLine = AudioSystem.getSourceDataLine(format)
        newLine.open(format, (sampleRate * BUFFER_SECONDS).toInt() * format.frameSize)
        newLine.start()
        line = newLine
        playing = true
        thread =
            Thread({ pump(newLine, renderer) }, "zhf-audio-output").apply {
                isDaemon = true
                start()
            }
        setState(OutputState.Running)
    }

    private fun pump(
        line: SourceDataLine,
        renderer: PcmRenderer,
    ) {
        val left = FloatArray(BLOCK_FRAMES)
        val right = FloatArray(BLOCK_FRAMES)
        val bytes = ByteArray(BLOCK_FRAMES * 4)
        while (!closed) {
            pauseLock.withLock {
                while (!playing && !closed) resumed.await()
            }
            if (closed) break
            renderer.render(left, right, BLOCK_FRAMES)
            val n = floatToPcm16(left, right, BLOCK_FRAMES, bytes)
            line.write(bytes, 0, n)
        }
    }

    override fun resume() {
        if (closed || line == null) return
        pauseLock.withLock {
            playing = true
            resumed.signalAll()
        }
        line?.start()
        setState(OutputState.Running)
    }

    override fun suspend() {
        if (closed || line == null) return
        playing = false
        line?.stop()
        setState(OutputState.Suspended)
    }

    override fun close() {
        if (closed) return
        closed = true
        pauseLock.withLock { resumed.signalAll() }
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        thread?.join(500)
        setState(OutputState.Closed)
    }
}

/** Runs delayed tasks on one daemon timer thread. */
internal class JvmScheduler : Scheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "zhf-audio-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    override fun postDelayed(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val future = executor.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }
}

actual fun createPlatformAudio(): AudioPlatform = AudioPlatform({ JvmPcmOutput() }, JvmScheduler())
