package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel

// Graphics quality levels (concept rules 3, 4): presets of the three levels. The memory budget is
// in GpuBudget.kt, the governors in QualityGovernor.kt / QualityUpgrade.kt, the staged change in
// QualityStages.kt. Pure, no scene model, no renderer.

/** Material type of world, horse and rider. */
enum class MaterialKind(
    val id: String,
) {
    LAMBERT("lambert"),
    STANDARD("standard"),
}

/** Which objects cast shadows (only meaningful with shadows on). */
enum class ShadowCasters(
    val id: String,
) {
    /** Horse and obstacles only. */
    OBSTACLES("obstacles"),
    ALL("all"),
}

/** Geometry detail of the environment instances and of horse and rider. */
enum class Detail(
    val id: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** Fog distances of a level (null fog = none). */
data class FogRange(
    val near: Double,
    val far: Double,
)

/**
 * The look of one graphics level. Immutable: a preset fitted to the GPU budget is a copy that keeps
 * its [level] (see `fitPresetToBudget`).
 *
 * Shares (`envDensity`, `grassTufts`, `flowers`, `decor`, `birds`, `butterflies`) are 0..1.
 */
data class QualityPreset(
    val level: GraphicsLevel,
    val pixelRatio: Double,
    /** Context attribute: only fixed when the renderer is created. */
    val antialias: Boolean,
    val shadows: Boolean,
    val shadowMapSize: Int,
    val shadowCasters: ShadowCasters?,
    val material: MaterialKind,
    val fog: FogRange?,
    val envMap: Boolean,
    /** Share of environment instances (trees, bushes, distant forest). */
    val envDensity: Double,
    val envDetail: Detail,
    /** Geometry detail and material type of horse and rider. */
    val characterDetail: Detail,
    /** Share of the grass tufts. */
    val grassTufts: Double,
    /** Share of the meadow flowers. */
    val flowers: Double,
    /** Share of the bunting (and paddock decoration). */
    val decor: Double,
    /** Flower boxes at the stands (their own shader program: only high). */
    val planters: Boolean,
    /** Grazing horses in the paddock (0 = none). */
    val grazingHorses: Int,
    /** Hoof dust on the sand. */
    val hoofDust: Boolean,
    /** Share of the birds in the sky. */
    val birds: Double,
    /** Share of the butterflies over the flowers. */
    val butterflies: Double,
    val anisotropy: Int,
    val normalMaps: Boolean,
    /** Trees, bushes, grass and flowers sway in the wind (vertex shader). */
    val wind: Boolean,
)

// Details of SRT-011 (rules 3 and 4). Low gets none of them: it keeps the draw calls and triangles
// it had before. Medium gets only what is cheap and adds no shader program of its own beyond the
// static bunting: the paddock fence and props and every second pennant, no wind, no flowers,
// animals or dust (SRT-013: the tablet lost its context with the full set). The tufts are the
// biggest triangle cost of all details (11 000 instances), so only high has them.

val LOW_PRESET =
    QualityPreset(
        level = GraphicsLevel.LOW,
        pixelRatio = 1.0,
        antialias = false,
        shadows = false,
        shadowMapSize = 0,
        shadowCasters = null,
        material = MaterialKind.LAMBERT,
        fog = null,
        envMap = false,
        envDensity = 0.2,
        envDetail = Detail.LOW,
        characterDetail = Detail.LOW,
        grassTufts = 0.0,
        flowers = 0.0,
        decor = 0.0,
        planters = false,
        grazingHorses = 0,
        hoofDust = false,
        birds = 0.0,
        butterflies = 0.0,
        anisotropy = 1,
        normalMaps = false,
        wind = false,
    )

val MEDIUM_PRESET =
    QualityPreset(
        level = GraphicsLevel.MEDIUM,
        pixelRatio = 1.5,
        antialias = true,
        shadows = true,
        shadowMapSize = 1024,
        shadowCasters = ShadowCasters.OBSTACLES,
        material = MaterialKind.STANDARD,
        fog = FogRange(near = 120.0, far = 520.0),
        envMap = true,
        envDensity = 0.55,
        envDetail = Detail.HIGH,
        characterDetail = Detail.MEDIUM,
        grassTufts = 0.0,
        flowers = 0.0,
        decor = 0.5,
        planters = false,
        grazingHorses = 0,
        hoofDust = false,
        birds = 0.0,
        butterflies = 0.0,
        anisotropy = 4,
        normalMaps = true,
        wind = false,
    )

val HIGH_PRESET =
    QualityPreset(
        level = GraphicsLevel.HIGH,
        pixelRatio = 2.0,
        antialias = true,
        shadows = true,
        shadowMapSize = 2048,
        shadowCasters = ShadowCasters.ALL,
        material = MaterialKind.STANDARD,
        fog = FogRange(near = 90.0, far = 480.0),
        envMap = true,
        envDensity = 1.0,
        envDetail = Detail.HIGH,
        characterDetail = Detail.HIGH,
        grassTufts = 1.0,
        flowers = 1.0,
        decor = 1.0,
        planters = true,
        grazingHorses = 2,
        hoofDust = true,
        birds = 1.0,
        butterflies = 1.0,
        anisotropy = 8,
        normalMaps = true,
        wind = true,
    )

/** All presets in level order (low, medium, high). */
val QUALITY_PRESETS: Map<GraphicsLevel, QualityPreset> =
    linkedMapOf(
        GraphicsLevel.LOW to LOW_PRESET,
        GraphicsLevel.MEDIUM to MEDIUM_PRESET,
        GraphicsLevel.HIGH to HIGH_PRESET,
    )

/** The preset of a level. */
fun presetFor(level: GraphicsLevel): QualityPreset =
    when (level) {
        GraphicsLevel.LOW -> LOW_PRESET
        GraphicsLevel.MEDIUM -> MEDIUM_PRESET
        GraphicsLevel.HIGH -> HIGH_PRESET
    }

/** Next lower level (never below low). */
fun lowerLevel(level: GraphicsLevel): GraphicsLevel = GRAPHICS_LEVELS[maxOf(0, GRAPHICS_LEVELS.indexOf(level) - 1)]
