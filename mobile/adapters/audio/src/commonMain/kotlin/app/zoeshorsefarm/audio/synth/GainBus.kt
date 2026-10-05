package app.zoeshorsefarm.audio.synth

/** Frames per render step, the render quantum of WebAudio. */
internal const val QUANTUM = 128

/**
 * A mono mix point with a gain, like a WebAudio `GainNode` that voices connect to. The voices add
 * their samples to [buffer]; the engine applies [gain] and sums the bus into the channel mix.
 *
 * Buses are pooled: [open] starts one, [killFrame] ends it (the equivalent of disconnecting the node
 * some time after a fade-out), after which its voices are dropped.
 */
internal class GainBus(
    sampleRate: Double,
) {
    val gain = AutomationParam(sampleRate, 1.0)
    val buffer = FloatArray(QUANTUM)
    var active = false
        private set
    var killFrame = Long.MAX_VALUE

    fun open(
        frame: Long,
        initialGain: Double,
    ) {
        active = true
        killFrame = Long.MAX_VALUE
        gain.reset(frame, initialGain)
        buffer.fill(0f)
    }

    fun close() {
        active = false
    }
}
