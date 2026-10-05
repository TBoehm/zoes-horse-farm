package app.zoeshorsefarm.render.filament.backend

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.StagePort
import app.zoeshorsefarm.render.filament.backend.sync.SceneSync
import app.zoeshorsefarm.render.filament.backend.sync.StageSettings
import app.zoeshorsefarm.render.filament.backend.sync.StageSync
import app.zoeshorsefarm.render.filament.backend.sync.SyncLog
import app.zoeshorsefarm.render.filament.backend.sync.SyncStats
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.ContextListener
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.render.RenderCapabilities
import app.zoeshorsefarm.scene.render.RenderInfo
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The scene's [RenderBackend] on top of a [GpuDevice] and a [StagePort]: everything of the port that
 * is not a native call. `FilamentRenderBackend` wires it to a `FilamentContext`; the tests wire it to
 * fakes.
 *
 * A frame: update the matrices (scene, and the camera if it is not in the scene), [SceneSync.sync]
 * (entities), [StageSync.sync] (lights, camera, fog, settings), draw.
 *
 * Differences from three.js on purpose: `shadowType` and `shadowAutoUpdate` are accepted and ignored
 * (Filament always renders PCF shadows every frame); `setPixelRatio(r)` is a cap on the physical
 * surface: the backing store is `size * min(r, devicePixelRatio)`, rendered at a fixed scale below the
 * surface size.
 */
class RenderCore(
    private val device: GpuDevice,
    private val stage: StagePort,
    log: SyncLog = SyncLog.SILENT,
    private val gpuName: String = "Filament",
) : RenderBackend {
    private val settings = StageSettings()
    private val sync = SceneSync(device, log)
    private val stageSync = StageSync(stage, settings, log)
    private val listeners = ArrayList<ContextListener>()

    private var logicalWidth = 0
    private var logicalHeight = 0
    private var deviceRatio = 1.0
    private var requestedRatio = 1.0
    private var lost = false
    private var disposed = false

    override val info: RenderInfo = RenderInfo()
    override val capabilities: RenderCapabilities = RenderCapabilities(device.maxTextureSize, device.maxAnisotropy)
    override val gpuDescription: String get() = if (lost || disposed) "" else gpuName
    override val pixelRatio: Double get() = min(requestedRatio, deviceRatio)

    override var toneMapping: ToneMapping
        get() = settings.toneMapping
        set(value) {
            settings.toneMapping = value
        }
    override var toneMappingExposure: Double
        get() = settings.exposure
        set(value) {
            settings.exposure = value
        }
    override var shadowsEnabled: Boolean
        get() = settings.shadowsEnabled
        set(value) {
            settings.shadowsEnabled = value
        }
    override var shadowType: ShadowType = ShadowType.PCF
    override var shadowAutoUpdate: Boolean = true

    /** Multisample antialiasing of the frame: 0, 2 or 4 samples. */
    var msaaSamples: Int
        get() = settings.msaaSamples
        set(value) {
            settings.msaaSamples = value
        }

    /** True between [loseContext] and [restoreContext]: nothing is drawn. */
    val contextLost: Boolean get() = lost

    /** Frames that were handed to Filament. */
    var framesRendered: Long = 0
        private set

    /** Numbers of the device objects, for the debug box. */
    fun stats(): SyncStats = sync.stats()

    override fun setPixelRatio(ratio: Double) {
        requestedRatio = max(MIN_RATIO, ratio)
        settings.maxPixelRatio = requestedRatio
    }

    override fun setSize(
        width: Int,
        height: Int,
    ) {
        logicalWidth = width
        logicalHeight = height
        if (width > 0 && height > 0) {
            stage.resize((width * deviceRatio).roundToInt(), (height * deviceRatio).roundToInt(), deviceRatio.toFloat())
        }
    }

    /** The platform surface has this physical size and device pixel ratio (on attach and on every resize). */
    fun surfaceChanged(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) {
        deviceRatio = if (devicePixelRatio > 0f) devicePixelRatio.toDouble() else 1.0
        logicalWidth = (widthPx / deviceRatio).roundToInt()
        logicalHeight = (heightPx / deviceRatio).roundToInt()
        stage.resize(widthPx, heightPx, deviceRatio.toFloat())
    }

    override fun getSize(target: Vec2): Vec2 = target.set(logicalWidth.toDouble(), logicalHeight.toDouble())

    override fun getDrawingBufferSize(target: Vec2): Vec2 =
        target.set(floor(logicalWidth * pixelRatio), floor(logicalHeight * pixelRatio))

    override fun render(
        scene: Scene,
        camera: Camera,
    ) {
        if (lost || disposed) return
        scene.updateMatrixWorld()
        if (camera.parent == null) camera.updateMatrixWorld()
        sync.sync(this, scene, camera, settings.shadowsEnabled)
        stageSync.sync(scene, camera, sync.lights, sync.wind)
        if (stage.renderFrame()) framesRendered++
        publishInfo()
    }

    override fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit,
    ) {
        if (!lost && !disposed) {
            sync.compile(root, scene, settings.shadowsEnabled)
            publishInfo()
        }
        onComplete()
    }

    override fun addContextListener(listener: ContextListener) {
        listeners.add(listener)
    }

    override fun removeContextListener(listener: ContextListener) {
        listeners.remove(listener)
    }

    /** The surface or the device is gone (the app went to the background): drawing stops, listeners are told. */
    fun loseContext() {
        if (lost || disposed) return
        lost = true
        for (listener in listeners.toList()) listener.onContextLost()
    }

    /** The surface is back: the device objects survived, so everything is simply sent again by the next frame. */
    fun restoreContext() {
        if (!lost || disposed) return
        lost = false
        stageSync.invalidate()
        for (listener in listeners.toList()) listener.onContextRestored()
    }

    /** Frees every device object but keeps the backend usable (the engine is rebuilt behind it). */
    fun resetResources() {
        sync.clear()
        publishInfo()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        sync.clear()
        device.dispose()
        listeners.clear()
        publishInfo()
    }

    private fun publishInfo() {
        val shadowPass = stageSync.shadowsActive
        info.drawCalls = sync.drawCalls + if (shadowPass) sync.shadowCalls else 0
        info.triangles = sync.triangles + if (shadowPass) sync.shadowTriangles else 0
        info.programs = sync.programCount
        info.textures = sync.textureCount
        info.geometries = sync.geometryCount
    }

    private companion object {
        const val MIN_RATIO = 0.25
    }
}
