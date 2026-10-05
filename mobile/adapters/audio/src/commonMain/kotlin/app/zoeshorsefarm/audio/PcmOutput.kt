package app.zoeshorsefarm.audio

/** Fills the output buffers with stereo audio. Called on the platform's audio thread. */
fun interface PcmRenderer {
    /** Writes [frames] samples into the first [frames] entries of [left] and [right] (-1..1). */
    fun render(
        left: FloatArray,
        right: FloatArray,
        frames: Int,
    )
}

/** State of the platform output, the counterpart of the `AudioContext.state` of the web. */
enum class OutputState(
    val id: String,
) {
    /** The device is pulling audio. */
    Running("running"),

    /** Stopped on purpose (background) or not allowed to run yet. */
    Suspended("suspended"),

    /** Stopped by the system (phone call, other app). Needs a new [PcmOutput.resume]. */
    Interrupted("interrupted"),

    /** Released for good. */
    Closed("closed"),
}

/**
 * The platform's PCM output: a pull-based stereo float stream at [sampleRate]. iOS uses
 * AVAudioEngine, the JVM a SourceDataLine; Android (AudioTrack) follows.
 */
interface PcmOutput {
    val sampleRate: Int
    val state: OutputState

    /** Called whenever [state] changes (any thread). */
    fun setStateListener(listener: ((OutputState) -> Unit)?)

    /** Opens the device and starts pulling from [renderer]. Throws when the device cannot be opened. */
    fun start(renderer: PcmRenderer)

    /** Asks the platform to run again after a suspend or an interruption. May be refused: see [state]. */
    fun resume()

    fun suspend()

    fun close()
}

/** Creates the output when audio is activated. Throwing counts as "no audio available". */
fun interface PcmOutputFactory {
    fun create(): PcmOutput
}

/** Handle of a delayed task. */
fun interface Cancellable {
    fun cancel()
}

/** Runs a task once after a delay on any thread; the audio facade is thread safe. */
fun interface Scheduler {
    fun postDelayed(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable
}

/** What the [Audio] facade needs from the platform. A null factory means there is no audio output. */
class AudioPlatform(
    val outputFactory: PcmOutputFactory?,
    val scheduler: Scheduler,
)
