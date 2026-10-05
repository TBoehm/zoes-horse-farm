package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.ln
import kotlin.math.max

/**
 * Filament's global fog (`View.fogOptions`), set up to look like the web scene's `THREE.Fog`.
 *
 * three.js blends linearly from `near` (no fog) to `far` (full fog). Filament only has exponential
 * fog: no fog up to `distance`, then `1 - exp(-density * (d - distance))`. The density is chosen so
 * that the fog reaches [OPACITY_AT_FAR] at the far distance, which is close enough to opaque that
 * the end of the world is hidden like in three.js. Height falloff is off: the fog depends on the
 * distance only (as does the web fog).
 */
data class FogParams(
    val distance: Float,
    val density: Float,
    val color: LinearRgb,
    val heightFalloff: Float = 0f,
    val maximumOpacity: Float = 1f,
) {
    companion object {
        const val OPACITY_AT_FAR = 0.95f
        private const val MIN_RANGE = 1f

        fun fromLinear(
            near: Float,
            far: Float,
            color: LinearRgb,
        ): FogParams {
            val range = max(MIN_RANGE, far - near)
            return FogParams(distance = near, density = -ln(1f - OPACITY_AT_FAR) / range, color = color)
        }

        fun fromLinearOrNull(
            near: Float?,
            far: Float?,
            color: LinearRgb,
        ): FogParams? = if (near == null || far == null) null else fromLinear(near, far, color)
    }
}
