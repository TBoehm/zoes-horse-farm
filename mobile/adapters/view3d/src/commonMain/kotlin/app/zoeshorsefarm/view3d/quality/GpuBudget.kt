package app.zoeshorsefarm.view3d.quality

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// GPU memory budget (rule 4). The platform does not tell how much GPU memory the app may use, so
// the memory a level needs is estimated before the level is applied and compared with a
// conservative budget per device; if it does not fit, the level keeps its look and gets a lower
// resolution (and, as a last resort, smaller shadow map and less scenery). Technical values,
// grouped here and not in Tuning: they model the hardware, not game play. They are deliberately on
// the safe side; the debug box shows estimate and budget so that they can be tuned on a real device.

private const val MIB = 1024.0 * 1024.0

/** Device facts the platform adapter fills in; the budget and the level choice read them. */
data class DeviceInfo(
    /** Total memory in GiB, null if the platform does not tell. */
    val totalMemoryGiB: Double? = null,
    /** Logical CPU cores, null if unknown (informational, not part of the budget). */
    val cores: Int? = null,
    /** Phone or tablet (touch first): such devices share memory with the system. */
    val isTouch: Boolean = false,
    /** GPU / renderer name (e.g. "Mali-G52", "Apple GPU"), null if unknown. */
    val rendererName: String? = null,
    /** Screen size in physical pixels, null if unknown (informational). */
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
)

/** A texture of the world for the memory estimate. */
data class TextureInfo(
    val width: Int,
    val height: Int,
    val normal: Boolean,
)

/**
 * Sizes the memory estimate depends on.
 * @property devicePixelRatio the screen's ratio of pixels to CSS (dp) pixels
 * @property pixelRatio cap of the render resolution, default: the preset's
 * @property antialias antialiasing of the real context, default: the preset's
 */
data class GpuMemoryContext(
    val cssWidth: Double,
    val cssHeight: Double,
    val devicePixelRatio: Double = 1.0,
    val pixelRatio: Double? = null,
    val antialias: Boolean? = null,
    val textures: List<TextureInfo> = DEFAULT_TEXTURES,
)

/** Textures of the world today (grass colour, sand colour, sand normal map), 512^2 each. */
val DEFAULT_TEXTURES: List<TextureInfo> =
    listOf(
        TextureInfo(512, 512, normal = false),
        TextureInfo(512, 512, normal = false),
        TextureInfo(512, 512, normal = true),
    )

internal object GpuMemoryModel {
    // Default framebuffer: RGBA8 colour and 24-bit depth (stencil is off, so 4 bytes per pixel with
    // padding). The system keeps two colour buffers (the one that is shown and the one that is
    // drawn). With antialiasing on, multisampled colour and depth renderbuffers are allocated as
    // well (MSAA, 4 samples is what mobile GPUs offer: MAX_SAMPLES is 4 on most of them) and
    // resolved into the colour buffer.
    const val BYTES_COLOR = 4
    const val BYTES_DEPTH = 4
    const val SWAP_BUFFERS = 2
    const val MSAA_SAMPLES = 4

    // PCF shadow map: an RGBA8 colour target plus a 32-bit depth texture
    const val SHADOW_BYTES_PER_TEXEL = 8

    // PMREM target of the environment map (256 px cube): cubeUV layout of 3 x 256 by 4 x 256
    // texels in half float RGBA (8 bytes)
    const val ENV_MAP_MB = (3 * 256 * 4 * 256 * 8) / MIB

    // an RGBA8 texture with a full mipmap chain takes 4/3 of its base level
    const val TEXTURE_BYTES_PER_TEXEL = 4
    const val TEXTURE_MIP_FACTOR = 4.0 / 3.0

    // geometry and instance buffers, shader programs, the skinned horse, the compositor's share
    const val BASELINE_MB = 16.0

    // instance matrices/colours and LOD geometry of the scenery at full density, of the 11 000 grass
    // tufts (76 bytes each, with a safety factor) and of the 3 600 meadow flowers
    const val SCENERY_FULL_MB = 2.0
    const val TUFT_MB = 2.0
    const val FLOWER_MB = 0.5

    // bunting, flower pots, paddock fence and props, flower boxes at the jumps (at full decor)
    const val DECOR_MB = 0.5

    // birds and butterflies: a few dozen instances
    const val WILDLIFE_MB = 0.1

    // one grazing horse (skinned body, no rider or tack) per character detail of the preset: the low
    // model (about 167 KB) on low and medium, the medium model (about 430 KB) on high
    val grazingHorseMB: Map<Detail, Double> =
        mapOf(Detail.LOW to 167 / 1024.0, Detail.MEDIUM to 167 / 1024.0, Detail.HIGH to 430 / 1024.0)

    // hoof dust: a pool of points, a few KB (none on low)
    const val DUST_MB = 0.01

    // vertices of rider (+16 / +80 / +150 KB) and horse (+0 / +36 / +80 KB) beyond what the
    // baseline holds, per character detail
    val characterMB: Map<Detail, Double> =
        mapOf(
            Detail.LOW to (16 + 0) / 1024.0,
            Detail.MEDIUM to (80 + 36) / 1024.0,
            Detail.HIGH to (150 + 80) / 1024.0,
        )

    // the pixel ratio is lowered in these steps; the shadow map and the scenery have floors
    const val RATIO_STEP = 0.05
    const val SHADOW_MAP_FLOOR = 1024
    const val ENV_DENSITY_FLOOR = 0.55
}

// Budget per device class (MiB). Phones and tablets share their memory with the system and get a
// small share (the GPU process is killed or the context lost when it grows too far); a weak GPU
// gets less again. A desktop-class device (no touch) gets the larger one.
private data class BudgetClass(
    val perGiB: Double,
    val min: Double,
    val max: Double,
    val unknown: Double,
)

private val TOUCH_BUDGET = BudgetClass(perGiB = 40.0, min = 96.0, max = 320.0, unknown = 160.0)
private val DESKTOP_BUDGET = BudgetClass(perGiB = 64.0, min = 256.0, max = 1024.0, unknown = 512.0)
private const val WEAK_GPU_FACTOR = 0.75

private val WEAK_GPU =
    Regex(
        "intel.*(hd|uhd)\\s*graphics|mali-[gt]?[0-7]\\d\\b|adreno.*\\b[1-5]\\d\\d\\b|powervr|videocore",
        RegexOption.IGNORE_CASE,
    )

/** JS `Math.round` (halves go up, unlike [kotlin.math.round]). */
private fun roundHalfUp(value: Double): Double = floor(value + 0.5)

/**
 * GPU memory (MiB) a level needs, estimated from the drawing buffer, shadow map, environment map,
 * textures and scenery (trees, grass tufts, flowers, decoration and animals scale with the preset's
 * `envDensity`, `grassTufts`, `flowers`, `decor`, `birds` and `butterflies`; the grazing horses
 * with `grazingHorses`) and the vertices of horse and rider (`characterDetail`).
 */
fun estimateGpuMemoryMB(
    preset: QualityPreset,
    ctx: GpuMemoryContext,
): Double {
    val m = GpuMemoryModel
    val ratio = min(ctx.devicePixelRatio, ctx.pixelRatio ?: preset.pixelRatio)
    val pixels = max(1.0, roundHalfUp(ctx.cssWidth * ratio)) * max(1.0, roundHalfUp(ctx.cssHeight * ratio))
    val antialias = ctx.antialias ?: preset.antialias
    val bytesPerPixel =
        if (antialias) {
            m.MSAA_SAMPLES * (m.BYTES_COLOR + m.BYTES_DEPTH) + m.BYTES_COLOR * m.SWAP_BUFFERS
        } else {
            m.BYTES_COLOR * m.SWAP_BUFFERS + m.BYTES_DEPTH
        }
    var bytes = pixels * bytesPerPixel
    if (preset.shadows) bytes += preset.shadowMapSize.toDouble() * preset.shadowMapSize * m.SHADOW_BYTES_PER_TEXEL
    if (preset.envMap) bytes += m.ENV_MAP_MB * MIB
    for (t in ctx.textures) {
        if (t.normal && !preset.normalMaps) continue // only uploaded when a material uses it
        bytes += t.width.toDouble() * t.height * m.TEXTURE_BYTES_PER_TEXEL * m.TEXTURE_MIP_FACTOR
    }
    return bytes / MIB + m.BASELINE_MB + sceneContentMB(preset)
}

/**
 * Instance buffers and extra geometry of everything the preset adds to the base world (MiB): the
 * scenery and its details, the grazing horses, the hoof dust and the vertices of horse and rider.
 */
private fun sceneContentMB(preset: QualityPreset): Double {
    val m = GpuMemoryModel
    val wildlife = max(preset.birds, preset.butterflies)
    val detail = preset.characterDetail
    val grazing = preset.grazingHorses * (m.grazingHorseMB[detail] ?: m.grazingHorseMB.getValue(Detail.LOW))
    return grazing +
        (if (preset.hoofDust) m.DUST_MB else 0.0) +
        (m.characterMB[detail] ?: 0.0) +
        preset.envDensity * m.SCENERY_FULL_MB +
        preset.grassTufts * m.TUFT_MB +
        preset.flowers * m.FLOWER_MB +
        preset.decor * m.DECOR_MB +
        wildlife * m.WILDLIFE_MB
}

/** Conservative GPU memory budget (MiB) of a device. */
fun gpuBudgetMB(info: DeviceInfo = DeviceInfo()): Int {
    val model = if (info.isTouch) TOUCH_BUDGET else DESKTOP_BUDGET
    val memory = info.totalMemoryGiB?.takeIf { it > 0 }
    var budget = if (memory == null) model.unknown else min(model.max, max(model.min, memory * model.perGiB))
    if (info.rendererName?.let { WEAK_GPU.containsMatchIn(it) } == true) budget *= WEAK_GPU_FACTOR
    return roundHalfUp(budget).toInt()
}

/**
 * A lever of the "grass and surroundings" group of [fitPresetToBudget]: returns the preset with
 * something taken away, or null if there was nothing left to take.
 */
private fun interface SceneryStep {
    fun drop(preset: QualityPreset): QualityPreset?
}

/** The levers of the "grass and surroundings" group, in the order they are used. */
private val SCENERY_STEPS: List<SceneryStep> =
    listOf(
        SceneryStep { p -> if (p.grassTufts > 0) p.copy(grassTufts = 0.0) else null },
        SceneryStep { p -> if (p.flowers > 0 || p.butterflies > 0) p.copy(flowers = 0.0, butterflies = 0.0) else null },
        SceneryStep { p -> if (p.grazingHorses > 0) p.copy(grazingHorses = 0) else null },
        SceneryStep { p -> if (p.birds > 0) p.copy(birds = 0.0) else null },
        SceneryStep { p -> if (p.decor > 0 || p.planters) p.copy(decor = 0.0, planters = false) else null },
        SceneryStep { p ->
            if (p.envDensity >
                GpuMemoryModel.ENV_DENSITY_FLOOR
            ) {
                p.copy(envDensity = GpuMemoryModel.ENV_DENSITY_FLOOR)
            } else {
                null
            }
        },
    )

/** The pixel ratio lowered by fitPresetToBudget: from the wanted one to the one that fits. */
data class PixelRatioCap(
    val from: Double,
    val to: Double,
)

/** The shadow map size halved by fitPresetToBudget. */
data class ShadowMapCap(
    val from: Int,
    val to: Int,
)

/** What fitPresetToBudget had to take away (null / false = untouched). */
data class CappedBy(
    val pixelRatio: PixelRatioCap?,
    val shadowMapSize: ShadowMapCap?,
    val scenery: Boolean,
)

/**
 * Result of [fitPresetToBudget]. [preset] is the same object when nothing changed, otherwise a copy
 * that keeps its level.
 */
data class BudgetFit(
    val preset: QualityPreset,
    val estimateMB: Double,
    val requestedMB: Double,
    val budgetMB: Double,
    val fits: Boolean,
    val capped: CappedBy,
)

/** Rounds down to a multiple of [step], with the two decimals the ratios are stored in. */
private fun roundDownTo(
    value: Double,
    step: Double,
): Double = floor(floor(value / step + 1e-9) * step * 100 + 0.5) / 100

private const val RATIO_BISECTION_STEPS = 24

/**
 * The biggest pixel ratio (a multiple of the ratio step) at which [preset] still fits, but at least
 * [floorRatio]; [floorRatio] itself when even that does not fit.
 */
private fun biggestFittingRatio(
    preset: QualityPreset,
    floorRatio: Double,
    wanted: Double,
    estimate: (QualityPreset) -> Double,
    budgetMB: Double,
): Double {
    if (estimate(preset.copy(pixelRatio = floorRatio)) > budgetMB) return floorRatio
    var lo = floorRatio // fits
    var hi = wanted // does not fit
    repeat(RATIO_BISECTION_STEPS) {
        val mid = (lo + hi) / 2
        if (estimate(preset.copy(pixelRatio = mid)) <= budgetMB) lo = mid else hi = mid
    }
    return max(floorRatio, roundDownTo(lo, GpuMemoryModel.RATIO_STEP))
}

/**
 * Fits a preset into the budget. When the estimate is too high, the levers are used in this order
 * until it fits: pixel ratio (down to 1), shadow map size (down to 1024), then the grass and
 * surroundings: grass tufts, flowers (with the butterflies over them), grazing horses, birds,
 * decoration and the density of trees and bushes. (Rule 4 puts the resolution first, so all of
 * these are the last group.) The level's look otherwise stays ("high" keeps its effects at a lower
 * resolution).
 */
fun fitPresetToBudget(
    preset: QualityPreset,
    ctx: GpuMemoryContext,
    budgetMB: Double,
): BudgetFit {
    val m = GpuMemoryModel
    val estimate = { p: QualityPreset -> estimateGpuMemoryMB(p, ctx) }
    val requestedMB = estimate(preset)
    if (requestedMB <= budgetMB) {
        return BudgetFit(preset, requestedMB, requestedMB, budgetMB, true, CappedBy(null, null, scenery = false))
    }
    var fitted = preset

    // 1. resolution: the biggest lever, no visible loss of effects
    var pixelRatioCap: PixelRatioCap? = null
    val wanted = min(ctx.devicePixelRatio, preset.pixelRatio)
    val floorRatio = min(1.0, ctx.devicePixelRatio)
    if (wanted > floorRatio) {
        val ratio = biggestFittingRatio(fitted, floorRatio, wanted, estimate, budgetMB)
        fitted = fitted.copy(pixelRatio = ratio)
        pixelRatioCap = PixelRatioCap(from = wanted, to = ratio)
    }

    // 2. shadow map size
    var shadowMapCap: ShadowMapCap? = null
    if (estimate(fitted) > budgetMB && fitted.shadows && fitted.shadowMapSize > m.SHADOW_MAP_FLOOR) {
        shadowMapCap = ShadowMapCap(from = fitted.shadowMapSize, to = m.SHADOW_MAP_FLOOR)
        fitted = fitted.copy(shadowMapSize = m.SHADOW_MAP_FLOOR)
    }

    // 3. grass and surroundings (rule 4), the cheapest loss to the picture first: tufts, flowers
    // (the butterflies hover over them, so they go with them), grazing horses, birds, the
    // decoration, then the density of trees and bushes
    var scenery = false
    for (step in SCENERY_STEPS) {
        if (estimate(fitted) <= budgetMB) break
        val dropped = step.drop(fitted)
        if (dropped != null) {
            fitted = dropped
            scenery = true
        }
    }

    val estimateMB = estimate(fitted)
    return BudgetFit(
        preset = fitted,
        estimateMB = estimateMB,
        requestedMB = requestedMB,
        budgetMB = budgetMB,
        fits = estimateMB <= budgetMB,
        capped = CappedBy(pixelRatioCap, shadowMapCap, scenery),
    )
}

/**
 * Antialiasing is a context attribute and cannot change later, so it is decided when the renderer
 * is created: only if the level wants it and the budget can carry the multisampled buffers even
 * with every other lever used up (a context without antialiasing is the last resort).
 * [ctx]: like fitPresetToBudget, its `antialias` is ignored.
 */
fun chooseAntialias(
    preset: QualityPreset,
    ctx: GpuMemoryContext,
    budgetMB: Double,
): Boolean = preset.antialias && fitPresetToBudget(preset, ctx.copy(antialias = true), budgetMB).fits
