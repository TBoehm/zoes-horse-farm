package app.zoeshorsefarm.render.filament.context

import app.zoeshorsefarm.render.filament.light.AmbientLight
import app.zoeshorsefarm.render.filament.light.SunLight
import app.zoeshorsefarm.render.filament.resources.DisposalStack
import app.zoeshorsefarm.render.filament.resources.FrameSizes
import app.zoeshorsefarm.render.filament.resources.GpuMemoryModel
import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import app.zoeshorsefarm.render.filament.resources.ResourceKind
import io.github.erkko68.filament.AntiAliasing
import io.github.erkko68.filament.Camera
import io.github.erkko68.filament.ColorGrading
import io.github.erkko68.filament.Dithering
import io.github.erkko68.filament.DynamicResolutionOptions
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.FogOptions
import io.github.erkko68.filament.MultiSampleAntiAliasingOptions
import io.github.erkko68.filament.NativeSurface
import io.github.erkko68.filament.QualityLevel
import io.github.erkko68.filament.RenderQuality
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.Scene
import io.github.erkko68.filament.ShadowType
import io.github.erkko68.filament.SwapChain
import io.github.erkko68.filament.ToneMapper
import io.github.erkko68.filament.View
import io.github.erkko68.filament.Viewport

/**
 * The Filament objects of one 3D view: engine, renderer, view, scene, camera, the sun and the
 * ambient light, and the swap chain of the surface it draws to.
 *
 * Lifecycle:
 *
 * 1. [create] (null if the device has no usable graphics backend)
 * 2. [attachSurface] when the platform surface exists (`NativeSurface` is built by the platform
 *    code: on iOS it wraps the `CAMetalLayer`, on Android the `Surface`), [resize] when it changes
 * 3. [applySettings] for the graphics level, [setLens] and [setCameraPose] for the camera, then
 *    [renderFrame] every frame
 * 4. [detachSurface] when the surface goes away (Android `surfaceDestroyed`: before it returns)
 * 5. [dispose] at the end. Everything the app made with [engine] (renderables, buffers, textures,
 *    materials) must be destroyed first, or be registered with [disposal], which runs last to first.
 *
 * All calls come from one thread (the thread that renders).
 */
class FilamentContext private constructor(
    val engine: Engine,
    val tracker: GpuResourceTracker,
) {
    /** Destroys everything in reverse order of creation: add native objects of the app here. */
    val disposal = DisposalStack()

    lateinit var renderer: Renderer
        private set
    lateinit var view: View
        private set
    lateinit var scene: Scene
        private set
    lateinit var camera: Camera
        private set
    lateinit var sun: SunLight
        private set
    lateinit var ambient: AmbientLight
        private set

    private var cameraEntity: Entity = 0
    private var swapChain: SwapChain? = null
    private var colorGrading: ColorGrading? = null
    private var toneMapper: ToneMapper? = null
    private var frameTrackerId = 0
    private var disposed = false

    var settings: RenderSettings = RenderSettings()
        private set

    var plan: PixelPlan = PixelPlan.compute(1, 1, 1f, 1f)
        private set

    private var devicePixelRatio = 1f
    private var fovDegrees = DEFAULT_FOV
    private var near = DEFAULT_NEAR
    private var far = DEFAULT_FAR
    private val windGlobal = FloatArray(MATERIAL_GLOBAL_SIZE)

    /** Frames that were drawn. */
    var framesRendered: Long = 0
        private set

    val hasSurface: Boolean get() = swapChain != null

    // ---- surface -------------------------------------------------------------------------------

    /**
     * Creates the swap chain for a platform surface of `widthPx` x `heightPx` physical pixels and the
     * given device pixel ratio. Replaces an earlier surface.
     */
    fun attachSurface(
        surface: NativeSurface,
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) {
        detachSurface()
        swapChain = disposal.add(engine.createSwapChain(surface)) { engine.destroy(it) }
        setSurfaceSize(widthPx, heightPx, devicePixelRatio)
        applyPlan()
    }

    /** A swap chain without a window, for tests on a machine with a GPU and for screenshots. */
    fun attachOffscreen(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float = 1f,
    ) {
        detachSurface()
        swapChain = disposal.add(engine.createSwapChain(widthPx, heightPx)) { engine.destroy(it) }
        setSurfaceSize(widthPx, heightPx, devicePixelRatio)
        applyPlan()
    }

    /**
     * Destroys the swap chain. Waits until the GPU is done with it, so the platform may free the
     * surface right after this returns. Drawing is skipped until a new surface is attached.
     */
    fun detachSurface() {
        val chain = swapChain ?: return
        swapChain = null
        engine.flushAndWait()
        disposal.dispose(chain)
        releaseFrameTracking()
    }

    /**
     * Fits the drawing size to the surface (the web `resizeRenderer`): the viewport is the surface, the
     * render scale comes from the pixel ratio cap of the settings. Returns true if anything changed.
     */
    fun resize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ): Boolean {
        if (!setSurfaceSize(widthPx, heightPx, devicePixelRatio)) return false
        applyPlan()
        return true
    }

    /** Stores the new size; false if nothing changed. */
    private fun setSurfaceSize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ): Boolean {
        val next = PixelPlan.compute(widthPx, heightPx, devicePixelRatio, settings.maxPixelRatio)
        if (next == plan && devicePixelRatio == this.devicePixelRatio) return false
        this.devicePixelRatio = devicePixelRatio
        plan = next
        return true
    }

    private fun applyPlan() {
        view.viewport = Viewport(0, 0, plan.surfaceWidth, plan.surfaceHeight)
        view.dynamicResolutionOptions =
            DynamicResolutionOptions().apply {
                enabled = plan.renderScale < 1f
                homogeneousScaling = true
                minScale = floatArrayOf(plan.renderScale, plan.renderScale)
                maxScale = floatArrayOf(plan.renderScale, plan.renderScale)
                quality = QualityLevel.MEDIUM
            }
        applyLens()
        trackFrame()
    }

    // ---- settings ------------------------------------------------------------------------------

    /** Applies a graphics level; only what differs from the current settings is touched. */
    fun applySettings(next: RenderSettings) {
        val changes = settings.changesTo(next)
        settings = next
        for (change in changes) {
            when (change) {
                RenderChange.PIXEL_RATIO -> {
                    if (hasSurface) resizeToCurrentSurface()
                }

                RenderChange.ANTI_ALIASING -> {
                    applyAntiAliasing()
                    trackFrame()
                }

                RenderChange.SHADOWS -> {
                    applyShadows()
                }

                RenderChange.FOG -> {
                    applyFog()
                }

                RenderChange.TONE_MAPPING -> {
                    applyToneMapping()
                }

                RenderChange.CLEAR_COLOR -> {
                    applyClearColor()
                }
            }
        }
    }

    private fun resizeToCurrentSurface() {
        plan = PixelPlan.compute(plan.surfaceWidth, plan.surfaceHeight, devicePixelRatio, settings.maxPixelRatio)
        applyPlan()
    }

    private fun applyAntiAliasing() {
        view.multiSampleAntiAliasingOptions =
            MultiSampleAntiAliasingOptions().apply {
                enabled = settings.msaaSamples > 0
                sampleCount = if (settings.msaaSamples > 0) settings.msaaSamples else DEFAULT_MSAA_SAMPLES
            }
    }

    private fun applyShadows() {
        val shadows = settings.shadows
        view.isShadowingEnabled = shadows != null
        sun.setShadows(shadows)
        trackShadowMap()
    }

    private var shadowTrackerId = 0

    private fun trackShadowMap() {
        if (shadowTrackerId != 0) tracker.release(shadowTrackerId)
        shadowTrackerId = 0
        val shadows = settings.shadows ?: return
        shadowTrackerId =
            tracker.register(ResourceKind.SHADOW_MAP, "sun shadow map", GpuMemoryModel.shadowMapBytes(shadows.mapSize))
    }

    private fun applyFog() {
        val fog = settings.fog
        view.fogOptions =
            FogOptions().apply {
                enabled = fog != null
                if (fog != null) {
                    distance = fog.distance
                    density = fog.density
                    heightFalloff = fog.heightFalloff
                    maximumOpacity = fog.maximumOpacity
                    color = floatArrayOf(fog.color.r, fog.color.g, fog.color.b)
                }
            }
    }

    private fun applyToneMapping() {
        val mapper =
            when (settings.toneMapping) {
                ToneMapping.ACES_LEGACY -> ToneMapper.ACESLegacy()
                ToneMapping.ACES -> ToneMapper.ACES()
                ToneMapping.FILMIC -> ToneMapper.Filmic()
                ToneMapping.LINEAR -> ToneMapper.Linear()
            }
        val grading =
            ColorGrading
                .Builder()
                .toneMapper(mapper)
                .build(engine)
        view.colorGrading = grading
        colorGrading?.let { disposal.dispose(it) }
        toneMapper?.let { disposal.dispose(it) }
        colorGrading = disposal.add(grading) { engine.destroy(it) }
        toneMapper = disposal.add(mapper) { it.close() }
        camera.setExposure(settings.exposure)
    }

    private fun applyClearColor() {
        val c = settings.clearColor
        renderer.clearOptions =
            Renderer.ClearOptions().apply {
                clearColor = doubleArrayOf(c.r.toDouble(), c.g.toDouble(), c.b.toDouble(), 1.0)
                clear = true
                discard = true
            }
    }

    // ---- camera --------------------------------------------------------------------------------

    /** The lens (vertical field of view in degrees, near and far plane); the aspect follows the surface. */
    fun setLens(
        fovDegrees: Float,
        near: Float,
        far: Float,
    ) {
        this.fovDegrees = fovDegrees
        this.near = near
        this.far = far
        applyLens()
    }

    private fun applyLens() {
        val aspect = plan.surfaceWidth.toDouble() / plan.surfaceHeight
        camera.setProjection(fovDegrees.toDouble(), aspect, near.toDouble(), far.toDouble(), Camera.Fov.VERTICAL)
    }

    /** The camera's world matrix, column-major (three.js `camera.matrixWorld.elements`). */
    fun setCameraPose(matrixWorld: FloatArray) {
        require(matrixWorld.size >= MATRIX_SIZE) { "a pose is a 4x4 matrix" }
        camera.setModelMatrix(matrixWorld)
    }

    // ---- frame ---------------------------------------------------------------------------------

    /**
     * The wind of the scenery: the materials read it from Filament's material global 0 (see
     * `WindEffect`). `time` in seconds (wrap it at a few hours so floats keep their precision),
     * `strength` 0 (calm) to 1.
     */
    fun setWind(
        time: Float,
        strength: Float,
    ) {
        windGlobal[0] = time
        windGlobal[1] = strength
        windGlobal[2] = 0f
        windGlobal[3] = 1f
        view.setMaterialGlobal(WIND_GLOBAL_INDEX, windGlobal)
    }

    /**
     * Draws one frame. False if nothing was drawn: no surface, or Filament asks to skip this frame
     * (the previous one is still in flight).
     */
    fun renderFrame(frameTimeNanos: Long = 0): Boolean {
        val chain = swapChain ?: return false
        if (!renderer.beginFrame(chain, frameTimeNanos)) return false
        renderer.render(view)
        renderer.endFrame()
        framesRendered++
        return true
    }

    // ---- memory --------------------------------------------------------------------------------

    private fun trackFrame() {
        releaseFrameTracking()
        if (!hasSurface) return
        val sizes = FrameSizes(plan.surfaceWidth, plan.surfaceHeight, plan.renderWidth, plan.renderHeight)
        frameTrackerId =
            tracker.register(
                ResourceKind.FRAME,
                "frame buffers",
                GpuMemoryModel.frameBytes(sizes, settings.msaaSamples),
            )
    }

    private fun releaseFrameTracking() {
        if (frameTrackerId != 0) tracker.release(frameTrackerId)
        frameTrackerId = 0
    }

    /** What the frame buffers and the shadow map take, in the model of [GpuMemoryModel]. */
    val estimatedFrameBytes: Long
        get() =
            GpuMemoryModel.frameBytes(
                FrameSizes(plan.surfaceWidth, plan.surfaceHeight, plan.renderWidth, plan.renderHeight),
                settings.msaaSamples,
            )

    /**
     * Object counts of Filament and the tracked GPU memory. `uploadOverflows` is the sum of the
     * `overflowCount` of the upload rings the caller owns (sprite batches, instance textures).
     */
    fun stats(uploadOverflows: Int = 0): BackendStats =
        BackendStats(
            entities = scene.entityCount,
            renderables = scene.renderableCount,
            visibleRenderables = view.visibleRenderableCount,
            lights = scene.lightCount,
            materials = engine.materialCount,
            textures = engine.textureCount,
            vertexBuffers = engine.vertexBufferCount,
            indexBuffers = engine.indexBufferCount,
            framesRendered = framesRendered,
            trackedMegabytes = tracker.totalMegabytes,
            uploadOverflows = uploadOverflows,
        )

    // ---- teardown ------------------------------------------------------------------------------

    /**
     * Waits for the GPU, then destroys everything in reverse order of creation. Returns the errors that
     * destroying threw (a half torn down engine is logged, not hidden). Safe to call twice.
     */
    fun dispose(): List<Throwable> {
        if (disposed) return emptyList()
        disposed = true
        swapChain = null
        if (engine.isValid) engine.flushAndWait()
        val errors = disposal.disposeAll()
        tracker.clear()
        return errors
    }

    private fun build() {
        // creation order is the reverse of the destruction order (see DisposalStack)
        disposal.add(engine) { Engine.destroy(it) }
        renderer = disposal.add(engine.createRenderer()) { engine.destroy(it) }
        scene = disposal.add(engine.createScene()) { engine.destroy(it) }
        view = disposal.add(engine.createView()) { engine.destroy(it) }
        cameraEntity = engine.entityManager.create()
        camera = engine.createCamera(cameraEntity)
        disposal.add(cameraEntity) {
            engine.destroyCameraComponent(it)
            engine.entityManager.destroy(it)
        }
        view.scene = scene
        view.camera = camera
        view.name = "zhf"
        view.isPostProcessingEnabled = true
        view.dithering = Dithering.NONE
        view.antiAliasing = AntiAliasing.NONE
        view.shadowType = ShadowType.PCF
        // R11G11B10F instead of RGBA16F: half the memory, no alpha is needed
        view.renderQuality = RenderQuality().apply { hdrColorBuffer = QualityLevel.MEDIUM }
        sun = disposal.add(SunLight.create(engine, scene)) { it.destroy() }
        ambient = disposal.add(AmbientLight(engine, scene)) { it.destroy() }
        // fog needs an indirect light (see AmbientLight): start with a black one
        ambient.clear()
        applyToneMapping()
        applyClearColor()
        applyFog()
        applyAntiAliasing()
        applyShadows()
    }

    companion object {
        const val DEFAULT_FOV = 58f
        const val DEFAULT_NEAR = 0.1f
        const val DEFAULT_FAR = 900f

        /** Material global 0 carries the wind: x time, y strength. */
        const val WIND_GLOBAL_INDEX = 0
        private const val MATERIAL_GLOBAL_SIZE = 4
        private const val MATRIX_SIZE = 16
        private const val DEFAULT_MSAA_SAMPLES = 4

        /**
         * Creates the engine and everything that does not need a surface. Null if no graphics
         * backend can be created (the app shows its "3D is not available" screen then).
         */
        fun create(
            settings: RenderSettings = RenderSettings(),
            backend: Engine.Backend = Engine.Backend.DEFAULT,
            tracker: GpuResourceTracker = GpuResourceTracker(),
        ): FilamentContext? {
            // Metal: a frame without a drawable (the app went to the background, the layer has no size) is
            // skipped instead of ending the process; the engine's own "panic" is meant for developers
            val config = Engine.Config().apply { metalDisablePanicOnDrawableFailure = true }
            val engine = Engine.create(backend, config = config) ?: return null
            val context = FilamentContext(engine, tracker)
            context.settings = settings
            context.build()
            return context
        }
    }
}
