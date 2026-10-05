package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.max

/**
 * Filament's global fog (`View.fogOptions`), set up to be the web scene's `THREE.Fog`.
 *
 * three.js blends from `near` (no fog) to `far` (full fog) with a smoothstep. Materials compiled with
 * `linearFog` (all of ours, see `MaterialSource.linearFog`) use Filament's linear fog equation:
 * no fog up to `distance`, then an opacity that grows by `density` per metre. With the height
 * falloff at 0 `density` is exactly that slope, so `density = 1 / (far - near)` reaches full fog at
 * `far`. The curve is a straight line instead of three.js' S-curve (at most 0.1 apart, the same
 * at near, in the middle and at far), and the distance is Filament's (eye distance, not depth).
 * The fog colour is multiplied by the intensity of the scene's indirect light (see `AmbientLight`).
 */
data class FogParams(
    val distance: Float,
    val density: Float,
    val color: LinearRgb,
    val heightFalloff: Float = 0f,
    val maximumOpacity: Float = 1f,
) {
    companion object {
        private const val MIN_RANGE = 1f

        fun fromLinear(
            near: Float,
            far: Float,
            color: LinearRgb,
        ): FogParams = FogParams(distance = near, density = 1f / max(MIN_RANGE, far - near), color = color)

        fun fromLinearOrNull(
            near: Float?,
            far: Float?,
            color: LinearRgb,
        ): FogParams? = if (near == null || far == null) null else fromLinear(near, far, color)
    }
}
