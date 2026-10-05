package app.zoeshorsefarm.render.filament.context

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How big the frame is drawn for a surface and a pixel ratio cap (the web `resizeRenderer`).
 *
 * The web app caps `devicePixelRatio` by the quality level (`pixelRatio` 1 / 1.5 / 2) and sizes the
 * drawing buffer to css size times the capped ratio. A native surface already has its physical
 * size, so the cap becomes a render scale below 1: Filament draws into a smaller target and scales
 * it up (fixed dynamic resolution).
 */
data class PixelPlan(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    /** The ratio actually used: `min(devicePixelRatio, maxPixelRatio)`. */
    val pixelRatio: Float,
    /** Share of the surface size that is rendered, in `MIN_RENDER_SCALE..1`. */
    val renderScale: Float,
    val renderWidth: Int,
    val renderHeight: Int,
) {
    companion object {
        /** Filament does not scale below a quarter and a smaller target would look broken anyway. */
        const val MIN_RENDER_SCALE = 0.25f

        fun compute(
            surfaceWidth: Int,
            surfaceHeight: Int,
            devicePixelRatio: Float,
            maxPixelRatio: Float,
        ): PixelPlan {
            val dpr = positiveOrOne(devicePixelRatio)
            val cap = positiveOrOne(maxPixelRatio)
            val ratio = min(dpr, cap)
            val scale = max(MIN_RENDER_SCALE, min(1f, ratio / dpr))
            val width = max(1, surfaceWidth)
            val height = max(1, surfaceHeight)
            return PixelPlan(
                surfaceWidth = width,
                surfaceHeight = height,
                pixelRatio = ratio,
                renderScale = scale,
                renderWidth = max(1, (width * scale).roundToInt()),
                renderHeight = max(1, (height * scale).roundToInt()),
            )
        }

        private fun positiveOrOne(value: Float): Float = if (value > 0f && value.isFinite()) value else 1f
    }
}
