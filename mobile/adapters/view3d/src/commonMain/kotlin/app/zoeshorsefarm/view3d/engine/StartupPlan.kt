package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.view3d.quality.BudgetFit
import app.zoeshorsefarm.view3d.quality.DEFAULT_TEXTURES
import app.zoeshorsefarm.view3d.quality.DeviceInfo
import app.zoeshorsefarm.view3d.quality.GpuMemoryContext
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.quality.TextureInfo
import app.zoeshorsefarm.view3d.quality.chooseAntialias
import app.zoeshorsefarm.view3d.quality.fitPresetToBudget
import app.zoeshorsefarm.view3d.quality.gpuBudgetMB
import app.zoeshorsefarm.view3d.quality.presetFor

// What is decided before the world exists (web: the first part of `createEngine`): the memory
// budget of the device, whether the context gets antialiasing and the preset of the saved level,
// fitted to the budget. Pure, so the host can ask for [StartupPlan.antialiasChosen] before it
// creates the render backend (antialiasing is a context attribute and cannot change later).

/** The memory context of [view] for the estimate; [textures] null = the textures of today. */
internal fun memoryContext(
    view: ViewMetrics,
    antialias: Boolean?,
    textures: List<TextureInfo>?,
): GpuMemoryContext =
    GpuMemoryContext(
        cssWidth = view.cssWidth,
        cssHeight = view.cssHeight,
        devicePixelRatio = view.devicePixelRatio,
        antialias = antialias,
        textures = textures ?: DEFAULT_TEXTURES,
    )

/**
 * The start of the engine for the saved [level]. [antialiasWanted]: the level asks for it;
 * [antialiasChosen]: the budget carries the multisampled buffers; [contextAntialias]: what the real
 * context has (the host's word if it passed one, else the choice). [firstPreset]: the level's preset
 * fitted to the budget, with the way it was fitted in [fit].
 */
class StartupPlan internal constructor(
    val level: GraphicsLevel,
    val budgetMB: Int,
    val antialiasWanted: Boolean,
    val antialiasChosen: Boolean,
    val contextAntialias: Boolean,
    val fit: BudgetFit,
) {
    val firstPreset: QualityPreset get() = fit.preset
}

/**
 * Plans the start: budget (the host's override, e.g. for tests, or the estimate of [device]),
 * antialiasing and the first preset. [contextAntialias] is what the backend really has, if the host
 * knows (null: what the budget allows).
 */
fun planStartup(
    level: GraphicsLevel,
    view: ViewSize,
    device: DeviceInfo = DeviceInfo(),
    gpuBudgetOverrideMB: Int? = null,
    contextAntialias: Boolean? = null,
): StartupPlan {
    val budget = gpuBudgetOverrideMB ?: gpuBudgetMB(device)
    val metrics = ViewMetrics(view.cssWidth, view.cssHeight, view.devicePixelRatio)
    val preset = presetFor(level)
    val chosen = chooseAntialias(preset, memoryContext(metrics, null, null), budget.toDouble())
    val real = contextAntialias ?: chosen
    val fit = fitPresetToBudget(preset, memoryContext(metrics, real, null), budget.toDouble())
    return StartupPlan(level, budget, preset.antialias, chosen, real, fit)
}
