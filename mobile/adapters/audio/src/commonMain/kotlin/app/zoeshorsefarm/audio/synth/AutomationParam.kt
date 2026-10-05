package app.zoeshorsefarm.audio.synth

import kotlin.math.exp
import kotlin.math.ln

private const val SET = 0
private const val LINEAR = 1
private const val EXPONENTIAL = 2
private const val TARGET = 3

private const val HOLD_SEGMENT = 0
private const val LINEAR_SEGMENT = 1
private const val EXPONENTIAL_SEGMENT = 2
private const val TARGET_SEGMENT = 3

/**
 * An automatable value like a WebAudio `AudioParam` (a-rate): `setValueAtTime`,
 * `linearRampToValueAtTime`, `exponentialRampToValueAtTime`, `setTargetAtTime` and
 * `cancelAndHoldAtTime` with the semantics of the WebAudio specification. Times are seconds on the
 * audio clock.
 *
 * The parameter is a sequential generator: [next] returns the value of the sample at [position]
 * and advances by one sample. Ramps and target curves are advanced incrementally (one multiply or
 * add per sample, no `exp` per sample). Events are kept in flat arrays; they only grow when more
 * events are pending than the capacity, so scheduling does not allocate in the normal case.
 *
 * Not thread safe: the owner (the synth) serialises access.
 */
internal class AutomationParam(
    private val sampleRate: Double,
    initialValue: Double = 0.0,
    capacity: Int = 8,
) {
    private var kinds = IntArray(capacity)
    private var times = DoubleArray(capacity)
    private var values = DoubleArray(capacity)
    private var constants = DoubleArray(capacity)
    private var head = 0
    private var count = 0

    private val dt = 1.0 / sampleRate
    private var segment = HOLD_SEGMENT
    private var current = initialValue

    /** Linear: added per sample. Exponential and target: multiplied per sample. */
    private var delta = 0.0
    private var target = 0.0
    private var targetOffset = 0.0

    /** Time and value of the last event that took effect: start of a following ramp. */
    private var lastTime = 0.0
    private var lastValue = initialValue

    /** Index of the sample that [next] returns next. */
    var position = 0L
        private set

    /** Value of the sample that [next] returns next (events that become due are not applied yet). */
    val peek: Double get() = current

    /** Restarts the parameter at [frame] with [value] and no events (voices are pooled). */
    fun reset(
        frame: Long,
        value: Double,
    ) {
        head = 0
        count = 0
        segment = HOLD_SEGMENT
        current = value
        lastTime = frame * dt
        lastValue = value
        position = frame
    }

    fun setValueAtTime(
        value: Double,
        time: Double,
    ) = insert(SET, value, time, 0.0)

    fun linearRampToValueAtTime(
        value: Double,
        time: Double,
    ) {
        startImplicitly()
        insert(LINEAR, value, time, 0.0)
    }

    fun exponentialRampToValueAtTime(
        value: Double,
        time: Double,
    ) {
        startImplicitly()
        insert(EXPONENTIAL, value, time, 0.0)
    }

    fun setTargetAtTime(
        target: Double,
        startTime: Double,
        timeConstant: Double,
    ) = insert(TARGET, target, startTime, timeConstant)

    /**
     * Drops all events after [time] and holds the value the parameter has at [time] (which must be
     * the current position of the parameter, as the synth always calls it with the audio clock).
     */
    fun cancelAndHoldAtTime(time: Double) {
        while (count > head && times[count - 1] > time) count--
        if (segment != HOLD_SEGMENT) segment = HOLD_SEGMENT
        lastTime = time
        lastValue = current
    }

    /** Returns the value of the current sample and advances to the next one. */
    fun next(): Double {
        if (head < count && times[head] <= position * dt) applyDueEvents(position * dt)
        val out = current
        when (segment) {
            LINEAR_SEGMENT -> {
                current += delta
            }

            EXPONENTIAL_SEGMENT -> {
                current *= delta
            }

            TARGET_SEGMENT -> {
                targetOffset *= delta
                current = target + targetOffset
            }
        }
        position++
        return out
    }

    private fun startImplicitly() {
        // Spec: a ramp without a preceding event starts at the current value, now.
        if (head == count) insert(SET, current, position * dt, 0.0)
    }

    private fun insert(
        kind: Int,
        value: Double,
        time: Double,
        constant: Double,
    ) {
        if (count == kinds.size) {
            if (head > 0) {
                val live = count - head
                kinds.copyInto(kinds, 0, head, count)
                times.copyInto(times, 0, head, count)
                values.copyInto(values, 0, head, count)
                constants.copyInto(constants, 0, head, count)
                head = 0
                count = live
            } else {
                val size = kinds.size * 2
                kinds = kinds.copyOf(size)
                times = times.copyOf(size)
                values = values.copyOf(size)
                constants = constants.copyOf(size)
            }
        }
        // Sorted by time; events with equal time keep their insertion order.
        var i = count
        while (i > head && times[i - 1] > time) {
            kinds[i] = kinds[i - 1]
            times[i] = times[i - 1]
            values[i] = values[i - 1]
            constants[i] = constants[i - 1]
            i--
        }
        kinds[i] = kind
        times[i] = time
        values[i] = value
        constants[i] = constant
        count++
    }

    private fun applyDueEvents(now: Double) {
        while (head < count && times[head] <= now) {
            val i = head++
            when (kinds[i]) {
                TARGET -> {
                    startTarget(i)
                }

                // SET, or the end of a ramp: the value is exact from here on
                else -> {
                    current = values[i]
                    segment = HOLD_SEGMENT
                    lastTime = times[i]
                    lastValue = current
                    startFollowingRamp(now)
                }
            }
        }
    }

    private fun startTarget(i: Int) {
        target = values[i]
        lastTime = times[i]
        val timeConstant = constants[i]
        if (timeConstant <= 0.0) {
            current = target
            segment = HOLD_SEGMENT
        } else {
            targetOffset = current - target
            delta = exp(-dt / timeConstant)
            segment = TARGET_SEGMENT
        }
        lastValue = current
        startFollowingRamp(times[i])
    }

    /** A ramp event right after the event that just took effect runs from that event's time and value. */
    private fun startFollowingRamp(now: Double) {
        if (head >= count) return
        val kind = kinds[head]
        if (kind != LINEAR && kind != EXPONENTIAL) return
        val t0 = lastTime
        val v0 = lastValue
        val t1 = times[head]
        val v1 = values[head]
        if (t1 <= t0) return // the end event is applied right away
        val elapsed = maxOf(now, t0) - t0
        if (kind == LINEAR) {
            val slope = (v1 - v0) / (t1 - t0)
            current = v0 + slope * elapsed
            delta = slope * dt
            segment = LINEAR_SEGMENT
        } else if (v0 == 0.0 || v1 == 0.0 || (v0 < 0.0) != (v1 < 0.0)) {
            // Spec: with a zero or sign change the value stays at v0 until the end time.
            current = v0
            segment = HOLD_SEGMENT
        } else {
            val rate = ln(v1 / v0) / (t1 - t0)
            current = v0 * exp(rate * elapsed)
            delta = exp(rate * dt)
            segment = EXPONENTIAL_SEGMENT
        }
    }
}
