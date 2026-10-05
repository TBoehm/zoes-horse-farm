package app.zoeshorsefarm.audio

import kotlin.math.abs

/** Stand-in for the platform output: the test decides when the "device" pulls audio and what state it is in. */
class FakePcmOutput(
    override val sampleRate: Int = 44100,
) : PcmOutput {
    override var state = OutputState.Suspended
        private set
    private var listener: ((OutputState) -> Unit)? = null
    private var renderer: PcmRenderer? = null

    var startFails = false
    var refuseResume = false
    var resumeCalls = 0
    var suspendCalls = 0
    var closed = false
    var started = false

    override fun setStateListener(listener: ((OutputState) -> Unit)?) {
        this.listener = listener
    }

    override fun start(renderer: PcmRenderer) {
        if (startFails) throw IllegalStateException("no audio device")
        this.renderer = renderer
        started = true
        setState(OutputState.Running)
    }

    override fun resume() {
        resumeCalls++
        if (!refuseResume) setState(OutputState.Running)
    }

    override fun suspend() {
        suspendCalls++
        setState(OutputState.Suspended)
    }

    override fun close() {
        closed = true
        state = OutputState.Closed
    }

    /** The platform changes the state (interruption, route change) and notifies the listener. */
    fun setState(next: OutputState) {
        state = next
        listener?.invoke(next)
    }

    /** The device pulls [seconds] of audio, if it is running. Returns the peak of the left channel. */
    fun pump(seconds: Double): Float {
        val total = (seconds * sampleRate).toInt()
        val left = FloatArray(512)
        val right = FloatArray(512)
        var peak = 0f
        var done = 0
        while (done < total && state == OutputState.Running) {
            val n = minOf(512, total - done)
            renderer?.render(left, right, n)
            for (i in 0 until n) peak = maxOf(peak, abs(left[i]))
            done += n
        }
        return peak
    }
}

/** Timer that only fires when the test advances time. */
class ManualScheduler : Scheduler {
    private class Task(
        val at: Long,
        val action: () -> Unit,
    ) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var now = 0L

    override fun postDelayed(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val t = Task(now + delayMillis, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    fun advance(millis: Long) {
        val end = now + millis
        while (true) {
            val due = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(due)
            now = due.at
            due.action()
        }
        now = end
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}
