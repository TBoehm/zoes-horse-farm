package app.zoeshorsefarm.view3d.quality

private const val INITIAL_CAPACITY = 64
private const val WINDOW_EPSILON = 1e-9

/**
 * Moving average over the last `windowS` seconds of frame durations. Frames longer than `slowS`
 * are counted as slow ones (the upgrade governor wants few of them); the default counts none.
 * Allocation-free once it has grown to the number of frames of a window.
 */
class FrameWindow(
    private val windowS: Double,
    private val slowS: Double = Double.POSITIVE_INFINITY,
) {
    // ring buffer of the measured frame durations, oldest at `head`
    private var samples = DoubleArray(INITIAL_CAPACITY)
    private var head = 0
    private var count = 0
    private var sum = 0.0
    private var slow = 0

    fun clear() {
        head = 0
        count = 0
        sum = 0.0
        slow = 0
    }

    fun push(dt: Double) {
        if (count == samples.size) grow()
        samples[(head + count) % samples.size] = dt
        count += 1
        sum += dt
        if (dt > slowS) slow += 1
        // drop old frames while the rest still covers the whole window
        while (count > 1 && sum - samples[head] >= windowS) {
            val old = samples[head]
            sum -= old
            if (old > slowS) slow -= 1
            head = (head + 1) % samples.size
            count -= 1
        }
    }

    /** Does the collected time cover the whole window? */
    val full: Boolean get() = sum >= windowS - WINDOW_EPSILON

    fun averageFps(): Double? = if (count > 0 && sum > 0) count / sum else null

    /** Like [averageFps] without boxing, for per-frame paths: NaN when there is none (comparisons are false). */
    fun fpsOrNaN(): Double = if (count > 0 && sum > 0) count / sum else Double.NaN

    /** Share (0..1) of the frames in the window that were slower than `slowS`. */
    fun slowShare(): Double = if (count > 0) slow.toDouble() / count else 0.0

    private fun grow() {
        val bigger = DoubleArray(samples.size * 2)
        for (i in 0 until count) bigger[i] = samples[(head + i) % samples.size]
        samples = bigger
        head = 0
    }
}
