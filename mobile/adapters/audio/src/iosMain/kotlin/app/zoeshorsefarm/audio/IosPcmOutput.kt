@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.zoeshorsefarm.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

private const val MAX_CALLBACK_FRAMES = 4096
private const val DEFAULT_SAMPLE_RATE = 44100
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * iOS output: an `AVAudioSourceNode` in an `AVAudioEngine` pulls the PCM of the synth on the real-time
 * audio thread. The session category is "ambient": it respects the silent switch and mixes with other
 * apps like the web audio of Safari did. Interruptions (call, Siri, other app) and route changes are
 * reported through [state]; the app resumes with [Audio.unlock] on the next interaction.
 *
 * Only compiled on Apple targets; the logic worth testing lives in the common synth.
 */
internal class IosPcmOutput : PcmOutput {
    private val engine = AVAudioEngine()
    private var listener: ((OutputState) -> Unit)? = null
    private var renderer: PcmRenderer? = null
    private var observers = emptyList<NSObjectProtocol>()

    // Scratch buffers of the audio callback, allocated once
    private val left = FloatArray(MAX_CALLBACK_FRAMES)
    private val right = FloatArray(MAX_CALLBACK_FRAMES)

    override val sampleRate: Int =
        engine.outputNode
            .outputFormatForBus(0u)
            .sampleRate
            .toInt()
            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

    override var state = OutputState.Suspended
        private set

    override fun setStateListener(listener: ((OutputState) -> Unit)?) {
        this.listener = listener
    }

    private fun setState(next: OutputState) {
        if (state == next) return
        state = next
        listener?.invoke(next)
    }

    override fun start(renderer: PcmRenderer) {
        this.renderer = renderer
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        session.setActive(true, error = null)

        val format = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 2u)
        val source =
            AVAudioSourceNode(format = format) { isSilence, _, frameCount, outputData ->
                isSilence?.pointed?.value = false
                val list = outputData?.pointed
                val current = this.renderer
                if (list != null && current != null) {
                    val buffers = list.mBuffers
                    val planar = list.mNumberBuffers >= 2u
                    var done = 0
                    val total = frameCount.toInt()
                    while (done < total) {
                        val n = minOf(MAX_CALLBACK_FRAMES, total - done)
                        current.render(left, right, n)
                        if (planar) {
                            val l = buffers[0].mData?.reinterpret<kotlinx.cinterop.FloatVar>()
                            val r = buffers[1].mData?.reinterpret<kotlinx.cinterop.FloatVar>()
                            for (i in 0 until n) {
                                l?.set(done + i, left[i])
                                r?.set(done + i, right[i])
                            }
                        } else {
                            val both = buffers[0].mData?.reinterpret<kotlinx.cinterop.FloatVar>()
                            for (i in 0 until n) {
                                both?.set((done + i) * 2, left[i])
                                both?.set((done + i) * 2 + 1, right[i])
                            }
                        }
                        done += n
                    }
                }
                0
            }
        engine.attachNode(source)
        engine.connect(source, to = engine.mainMixerNode, format = format)
        engine.prepare()
        if (!startEngine()) throw IllegalStateException("AVAudioEngine did not start")
        observe()
        setState(OutputState.Running)
    }

    private fun startEngine(): Boolean =
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            engine.startAndReturnError(error.ptr)
        }

    private fun observe() {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        val interruption =
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, main) { _: NSNotification? ->
                // Began or ended: either way the output needs a new resume from the app
                if (state == OutputState.Running) setState(OutputState.Interrupted)
            }
        val configuration =
            center.addObserverForName(AVAudioEngineConfigurationChangeNotification, null, main) { _: NSNotification? ->
                // Route change (headphones, Bluetooth): the engine stopped, try to continue
                if (state == OutputState.Running && !engine.isRunning()) {
                    if (!startEngine()) setState(OutputState.Interrupted)
                }
            }
        observers = listOf(interruption, configuration)
    }

    override fun resume() {
        if (state == OutputState.Closed || state == OutputState.Running) return
        AVAudioSession.sharedInstance().setActive(true, error = null)
        if (startEngine()) setState(OutputState.Running)
    }

    override fun suspend() {
        if (state != OutputState.Running) return
        engine.pause()
        setState(OutputState.Suspended)
    }

    override fun close() {
        if (state == OutputState.Closed) return
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers = emptyList()
        engine.stop()
        renderer = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
        setState(OutputState.Closed)
    }
}

private class IosTimer(
    var cancelled: Boolean = false,
)

/** Runs delayed tasks on the main queue. */
internal class IosScheduler : Scheduler {
    override fun postDelayed(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val timer = IosTimer()
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMillis * NANOS_PER_MILLI), dispatch_get_main_queue()) {
            if (!timer.cancelled) task()
        }
        return Cancellable { timer.cancelled = true }
    }
}

actual fun createPlatformAudio(): AudioPlatform = AudioPlatform({ IosPcmOutput() }, IosScheduler())
