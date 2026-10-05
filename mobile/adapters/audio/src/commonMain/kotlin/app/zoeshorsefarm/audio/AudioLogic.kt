// Pure logic of the audio module (no synthesis, no platform): volume mapping, music state,
// scheduler timing, randomness and the impulse response for the reverb.
package app.zoeshorsefarm.audio

import app.zoeshorsefarm.shared.clamp
import kotlin.math.min
import kotlin.math.pow

private const val DEFAULT_VOLUME = 0.5

/** Volume and mute settings of the two channels. Volumes are linear 0..1. */
data class AudioSettings(
    val musicVolume: Double = DEFAULT_VOLUME,
    val musicMuted: Boolean = false,
    val sfxVolume: Double = DEFAULT_VOLUME,
    val sfxMuted: Boolean = false,
)

fun clamp01(
    value: Double?,
    fallback: Double = DEFAULT_VOLUME,
): Double {
    if (value == null || value.isNaN()) return fallback
    return clamp(value, 0.0, 1.0)
}

/** API volume 0..1 linear, internally quadratic (perceptual): 0.5 -> 0.25 (-12 dB). */
fun volumeToGain(volume: Double): Double {
    val v = clamp01(volume)
    return v * v
}

/** Muting keeps the volume in the state and only sets the channel to 0. */
fun channelGain(
    volume: Double,
    muted: Boolean,
): Double = if (muted) 0.0 else volumeToGain(volume)

fun normalizeSettings(
    musicVolume: Double? = null,
    musicMuted: Boolean? = null,
    sfxVolume: Double? = null,
    sfxMuted: Boolean? = null,
): AudioSettings =
    AudioSettings(
        musicVolume = clamp01(musicVolume),
        musicMuted = musicMuted == true,
        sfxVolume = clamp01(sfxVolume),
        sfxMuted = sfxMuted == true,
    )

/**
 * Should the melody be playing right now? At volume 0 it keeps running (no restart while dragging
 * the slider), when muted or in the background it does not.
 */
fun shouldMusicRun(
    wanted: Boolean,
    hidden: Boolean,
    muted: Boolean,
): Boolean = wanted && !hidden && !muted

fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

/** One step of the melody loop that starts at [time] (seconds on the audio clock). */
data class PlannedStep(
    val step: Int,
    val time: Double,
)

/**
 * Result of [planSteps]. The scheduler runs on the audio thread, so the plan is reusable and keeps
 * its events in flat arrays (no allocation per call unless a window holds more than [capacity] steps).
 */
class StepPlan(
    capacity: Int = 8,
) {
    private var steps = IntArray(capacity)
    private var times = DoubleArray(capacity)

    var count = 0
        private set

    /** Time of the step after the last planned one. */
    var nextTime = 0.0
        internal set

    /** Loop index of the step after the last planned one. */
    var step = 0
        internal set

    fun stepAt(index: Int): Int = steps[index]

    fun timeAt(index: Int): Double = times[index]

    internal fun clear() {
        count = 0
    }

    internal fun add(
        step: Int,
        time: Double,
    ) {
        if (count == steps.size) {
            steps = steps.copyOf(count * 2)
            times = times.copyOf(count * 2)
        }
        steps[count] = step
        times[count] = time
        count++
    }

    fun toList(): List<PlannedStep> = List(count) { PlannedStep(steps[it], times[it]) }
}

/**
 * Lookahead scheduler: plans all steps that start in the window [now, now + lookahead). If the next
 * step is far in the past (timer throttled), it restarts instead of catching up on a backlog.
 * Fills and returns [into].
 */
fun planSteps(
    now: Double,
    nextTime: Double,
    step: Int,
    lookahead: Double,
    stepDuration: Double,
    loopSteps: Int,
    maxLate: Double = 0.25,
    restartDelay: Double = 0.03,
    into: StepPlan = StepPlan(),
): StepPlan {
    var time = nextTime
    var index = step
    if (time < now - maxLate) time = now + restartDelay
    into.clear()
    while (time < now + lookahead) {
        into.add(index, maxOf(time, now))
        time += stepDuration
        index = (index + 1) % loopSteps
    }
    into.nextTime = time
    into.step = index
    return into
}

/** Source of numbers in [0, 1). Injected so that tests and the noise buffer are reproducible. */
fun interface RandomSource {
    fun next(): Double
}

/** Small deterministic random generator (mulberry32) for noise, jitter and reverb. */
class Mulberry32(
    seed: Int,
) : RandomSource {
    private var a = seed

    override fun next(): Double {
        a += 0x6d2b79f5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

/** Synthetic room impulse response: decaying noise, slightly darkened. One array per channel. */
fun generateImpulse(
    sampleRate: Int,
    seconds: Double = 1.8,
    decay: Double = 3.0,
    channels: Int = 2,
    rng: RandomSource = Mulberry32(7),
): Array<FloatArray> {
    val length = maxOf(1, (sampleRate * seconds).toInt())
    val attack = maxOf(1, (sampleRate * 0.004).toInt())
    return Array(channels) {
        val out = FloatArray(length)
        var low = 0.0
        for (i in 0 until length) {
            val fade = (1 - i.toDouble() / length).pow(decay) * min(1.0, i.toDouble() / attack)
            low += 0.45 * (rng.next() * 2 - 1 - low)
            out[i] = (low * fade).toFloat()
        }
        out
    }
}
