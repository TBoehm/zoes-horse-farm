package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.math.roundToInt

// Frame-rate display (rule 4): the averaging and the text are pure; the ride screen only shows them.

private const val DEFAULT_INTERVAL_S = 0.5
private const val DEFAULT_MAX_FRAME_S = 1.0
private const val EPSILON = 1e-9

/**
 * Averages the frame rate over a fixed interval, so that the number does not flicker. [frame]
 * returns the new rounded fps once per interval, otherwise null. A frame longer than [maxFrameS] is a
 * real interruption (e.g. a suspended app), not a slow game: it starts a new interval instead of
 * dragging the average down.
 */
class FpsMeter(
    private val intervalS: Double = DEFAULT_INTERVAL_S,
    private val maxFrameS: Double = DEFAULT_MAX_FRAME_S,
) {
    private var elapsed = 0.0
    private var frames = 0

    fun frame(dtSeconds: Double): Int? {
        val valid = dtSeconds > 0 && dtSeconds.isFinite()
        // a real interruption starts a new interval; an invalid frame time is ignored
        if (valid && dtSeconds > maxFrameS) reset()
        if (!valid || dtSeconds > maxFrameS) return null
        elapsed += dtSeconds
        frames += 1
        return if (elapsed < intervalS - EPSILON) null else (frames / elapsed).roundToInt().also { reset() }
    }

    fun reset() {
        elapsed = 0.0
        frames = 0
    }
}

/**
 * Text of the display, built entirely from translations (word order, separator and placeholder
 * belong to the language files): "58 fps · Medium", "58 fps · Medium (auto)" for an automatically
 * chosen level, "58 fps" without a level. [t] is the translate function; [fps] null = not measured yet.
 */
fun formatFpsText(
    fps: Int?,
    level: GraphicsLevel?,
    auto: Boolean,
    t: (String, Map<String, Any?>?) -> String,
): String {
    val value: Any = fps ?: t("ride.fpsNone", null)
    if (level == null) return t("ride.fps", mapOf("fps" to value))
    return t(
        if (auto) "ride.fpsLevelAuto" else "ride.fpsLevel",
        mapOf("fps" to value, "level" to t("graphics.${level.id}", null)),
    )
}
