package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.light.ShadowFocus
import app.zoeshorsefarm.render.filament.math.LinearRgb

/** The curve that maps the HDR frame to the display. */
enum class ToneMapping {
    /**
     * `ACESFilmicToneMapping` of three.js (the Hill/Narkowicz fit with the 1/0.6 pre-exposure).
     * Filament's `ACESLegacy` mapper is the same curve.
     */
    ACES_LEGACY,

    /** Filament's ACES with reference gamut compression: less saturated highlights. */
    ACES,
    FILMIC,
    LINEAR,
}

/**
 * Sun shadows. `halfExtent` is the half width of the area around the focus that gets shadows (the
 * web shadow camera), `shadowFarStep` the granularity of the shadow distance (see [ShadowFocus]).
 * The biases are technical values of Filament's shadow mapping, not game values.
 */
data class ShadowSettings(
    val mapSize: Int,
    val constantBias: Float = DEFAULT_CONSTANT_BIAS,
    val normalBias: Float = DEFAULT_NORMAL_BIAS,
    val stable: Boolean = true,
    val halfExtent: Float = ShadowFocus.DEFAULT_HALF_EXTENT,
    val shadowFarStep: Float = DEFAULT_FAR_STEP,
) {
    init {
        require(mapSize >= MIN_MAP_SIZE && (mapSize and (mapSize - 1)) == 0) {
            "shadow map size must be a power of two of at least $MIN_MAP_SIZE, not $mapSize"
        }
    }

    companion object {
        const val MIN_MAP_SIZE = 8
        const val DEFAULT_CONSTANT_BIAS = 0.005f
        const val DEFAULT_NORMAL_BIAS = 1f
        const val DEFAULT_FAR_STEP = 4f
    }
}

/**
 * Everything of a frame that a graphics level decides. Defaults are the `low` level of the web
 * app: pixel ratio 1, no antialiasing, no shadows, no fog, ACES tone mapping at exposure 1.
 *
 * `exposure` is Filament's linear camera exposure (not photometric): with it at 1 a light's
 * intensity is used as three.js uses it, so the web light values carry over unchanged.
 */
data class RenderSettings(
    val maxPixelRatio: Float = 1f,
    val msaaSamples: Int = 0,
    val shadows: ShadowSettings? = null,
    val fog: FogParams? = null,
    val toneMapping: ToneMapping = ToneMapping.ACES_LEGACY,
    val exposure: Float = 1f,
    val clearColor: LinearRgb = LinearRgb(0f, 0f, 0f),
) {
    init {
        require(maxPixelRatio > 0f) { "maxPixelRatio must be positive" }
        require(msaaSamples == 0 || msaaSamples == 2 || msaaSamples == 4) { "msaaSamples must be 0, 2 or 4" }
        require(exposure > 0f) { "exposure must be positive" }
    }

    /** What differs between this and `other`; the context only touches what changed. */
    fun changesTo(other: RenderSettings): Set<RenderChange> {
        val changes = mutableSetOf<RenderChange>()
        if (maxPixelRatio != other.maxPixelRatio) changes += RenderChange.PIXEL_RATIO
        if (msaaSamples != other.msaaSamples) changes += RenderChange.ANTI_ALIASING
        if (shadows != other.shadows) changes += RenderChange.SHADOWS
        if (fog != other.fog) changes += RenderChange.FOG
        if (toneMapping != other.toneMapping || exposure != other.exposure) changes += RenderChange.TONE_MAPPING
        if (clearColor != other.clearColor) changes += RenderChange.CLEAR_COLOR
        return changes
    }
}

enum class RenderChange { PIXEL_RATIO, ANTI_ALIASING, SHADOWS, FOG, TONE_MAPPING, CLEAR_COLOR }
