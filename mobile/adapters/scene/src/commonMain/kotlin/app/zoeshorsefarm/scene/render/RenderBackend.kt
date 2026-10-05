package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.math.Vec2

/** Tone mapping of the final image (three.js `NoToneMapping`, `ACESFilmicToneMapping`). */
enum class ToneMapping { NONE, ACES_FILMIC }

/** Shadow filtering (three.js `PCFShadowMap` ...); a backend may ignore what it cannot do. */
enum class ShadowType { BASIC, PCF, PCF_SOFT, VSM }

/** What the renderer did and holds, like three.js `renderer.info` (render counters are of the last frame). */
class RenderInfo {
    var drawCalls: Int = 0
    var triangles: Int = 0

    /** Compiled shader programs alive. */
    var programs: Int = 0

    /** Textures alive on the GPU. */
    var textures: Int = 0

    /** Geometries alive on the GPU. */
    var geometries: Int = 0
}

/** Limits of the device (three.js `renderer.capabilities`). */
class RenderCapabilities(
    val maxTextureSize: Int = 4096,
    val maxAnisotropy: Int = 4,
)

/** Notified when the graphics device is lost (WebGL context lost, app in the background) and when it is back. */
interface ContextListener {
    fun onContextLost()

    fun onContextRestored()
}

/**
 * The port the view code renders through. The Filament backend (`:adapters:render-filament`)
 * implements it for the device; tests use [FakeRenderBackend]. It mirrors the parts of the three.js
 * `WebGLRenderer` the web view uses: settings, `render`, `compile`, `info` and context loss.
 *
 * Resources are uploaded when they are first drawn or compiled. Disposing a geometry, material,
 * texture or instanced mesh (`dispose()`) frees its device copy; using it again uploads it again.
 */
interface RenderBackend {
    val info: RenderInfo
    val capabilities: RenderCapabilities

    /** Description of the graphics device for diagnostics (`""` while the device is lost). */
    val gpuDescription: String

    /** Backing-store pixels per logical pixel. */
    val pixelRatio: Double

    var toneMapping: ToneMapping
    var toneMappingExposure: Double
    var shadowsEnabled: Boolean
    var shadowType: ShadowType

    /** Renders the shadow maps every frame (true) or only when asked by the backend. */
    var shadowAutoUpdate: Boolean

    fun setPixelRatio(ratio: Double)

    /** Sets the logical size of the drawing surface; the drawing buffer is `size * pixelRatio`. */
    fun setSize(
        width: Int,
        height: Int,
    )

    /** Logical size of the surface. */
    fun getSize(target: Vec2): Vec2

    /** Size of the drawing buffer in pixels. */
    fun getDrawingBufferSize(target: Vec2): Vec2

    /** Draws one frame: updates the matrices of the scene and the camera, then submits the visible objects. */
    fun render(
        scene: Scene,
        camera: Camera,
    )

    /**
     * Builds everything `root` yields (the shader variants, buffers and textures it needs) so that
     * the first frame does not stall. `onComplete` is called when the objects are ready, possibly
     * after the call returned.
     */
    fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit = {},
    )

    fun addContextListener(listener: ContextListener)

    fun removeContextListener(listener: ContextListener)

    /** Frees every device resource of the backend. */
    fun dispose()
}
