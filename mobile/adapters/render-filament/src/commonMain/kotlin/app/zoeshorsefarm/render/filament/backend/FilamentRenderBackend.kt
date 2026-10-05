package app.zoeshorsefarm.render.filament.backend

import app.zoeshorsefarm.render.filament.backend.device.FilamentGpuDevice
import app.zoeshorsefarm.render.filament.backend.device.FilamentStage
import app.zoeshorsefarm.render.filament.backend.sync.SyncLog
import app.zoeshorsefarm.render.filament.context.BackendStats
import app.zoeshorsefarm.render.filament.context.FilamentContext
import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.material.MarkingRegions
import app.zoeshorsefarm.render.filament.material.MaterialPackageCache
import app.zoeshorsefarm.scene.render.RenderBackend
import io.github.erkko68.filament.NativeSurface

/**
 * The scene model's [RenderBackend] on Filament: `render(scene, camera)` brings the Filament scene in
 * step with the node graph (see `SceneSync` and `StageSync`) and draws a frame.
 *
 * Lifecycle, all on the render thread:
 *
 * 1. [create] (null if the device has no graphics backend)
 * 2. [attachSurface] when the platform surface exists, [onSurfaceResized] when it changes
 * 3. `compile(...)` the objects of the first screen, then `render(...)` every frame
 * 4. [detachSurface] when the surface goes away (Android `surfaceDestroyed`, before it returns): the
 *    context listeners are told the context was lost and nothing is drawn; the next [attachSurface]
 *    brings it back ("restored") and everything is sent to Filament again. Meshes, textures and
 *    materials survive: they belong to the engine, not to the surface
 * 5. [dispose] at the end
 */
class FilamentRenderBackend private constructor(
    val context: FilamentContext,
    private val device: FilamentGpuDevice,
    private val core: RenderCore,
    private val log: SyncLog,
) : RenderBackend by core {
    /** Multisample antialiasing: 0, 2 or 4 samples, applied by the next frame. */
    var msaaSamples: Int
        get() = core.msaaSamples
        set(value) {
            core.msaaSamples = value
        }

    /** Creates the Filament swap chain for a platform surface of `widthPx` x `heightPx` physical pixels. */
    fun attachSurface(
        surface: NativeSurface,
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) {
        context.attachSurface(surface, widthPx, heightPx, devicePixelRatio)
        core.surfaceChanged(widthPx, heightPx, devicePixelRatio)
        core.restoreContext()
    }

    /** The surface changed its size or density. */
    fun onSurfaceResized(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) = core.surfaceChanged(widthPx, heightPx, devicePixelRatio)

    /** Destroys the swap chain (waits for the GPU) and reports a lost context. */
    fun detachSurface() {
        context.detachSurface()
        core.loseContext()
    }

    /** Frees every device object but keeps the engine (a quality reset from the CPU data). */
    fun resetResources() = core.resetResources()

    /** Numbers of Filament objects, tracked GPU memory and upload fall backs, for the debug box. */
    fun backendStats(): BackendStats = context.stats(core.stats().uploadOverflows)

    /** Shader programs Filament made of the materials it built (not the scene model's program count). */
    val estimatedFilamentPrograms: Int get() = device.estimatedPrograms

    /** Materials built so far. */
    val builtMaterials: Int get() = device.materialCount

    /** Destroys everything in the right order and returns the errors that destroying threw. */
    override fun dispose() {
        core.dispose()
        context.dispose().forEach { log.warn("filament teardown: $it") }
    }

    companion object {
        /**
         * Creates the engine and the backend, or null if no graphics backend can be created. `cache` keeps
         * compiled materials between runs (see `MaterialPackageCache`).
         */
        fun create(
            settings: RenderSettings = RenderSettings(),
            cache: MaterialPackageCache? = null,
            markings: MarkingRegions = MarkingRegions.WEB,
            gpuName: String = "Filament",
            log: SyncLog = SyncLog { println("[filament] $it") },
        ): FilamentRenderBackend? {
            val context = FilamentContext.create(settings) ?: return null
            val device = FilamentGpuDevice(context, cache, markings)
            val core = RenderCore(device, FilamentStage(context), log, gpuName)
            return FilamentRenderBackend(context, device, core, log)
        }
    }
}
