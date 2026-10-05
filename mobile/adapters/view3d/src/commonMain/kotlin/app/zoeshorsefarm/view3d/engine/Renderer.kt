package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Renderer setup (web: `renderer.js`). The WebGL renderer became the scene `RenderBackend`: one
// consistent colour / tone-mapping setup and the pixel-ratio cap of the quality level. What the web
// renderer set at creation (output colour space sRGB, power preference, no stencil) is the backend's
// own business; antialiasing is decided by `chooseAntialias` before the backend is made (see
// StartupPlan.kt).

/** The pixel-ratio cap of a renderer that was never told another one (web: `userData.maxPixelRatio`). */
const val DEFAULT_MAX_PIXEL_RATIO = 2.0

/** Gives [backend] the tone mapping and shadow settings of the game; shadows start switched off. */
fun configureRenderer(backend: RenderBackend) {
    backend.toneMapping = ToneMapping.ACES_FILMIC
    backend.toneMappingExposure = 1.0
    backend.shadowsEnabled = false
    backend.shadowType = ShadowType.PCF
    backend.shadowAutoUpdate = true
}

/** The size of the surface the picture is drawn on: logical size (dp) and the screen's pixel density. */
class ViewMetrics(
    var cssWidth: Double = 1.0,
    var cssHeight: Double = 1.0,
    var devicePixelRatio: Double = 1.0,
) {
    fun set(
        cssWidth: Double,
        cssHeight: Double,
        devicePixelRatio: Double,
    ) {
        this.cssWidth = cssWidth
        this.cssHeight = cssHeight
        this.devicePixelRatio = devicePixelRatio
    }
}

/**
 * Pixel ratio and size of the drawing surface (web: `setMaxPixelRatio`, `resizeRenderer`). The
 * resize check runs every frame, so it reuses a scratch vector and does not allocate.
 */
class RenderSizing(
    private val backend: RenderBackend,
    val view: ViewMetrics = ViewMetrics(),
) {
    /** The cap of the pixel ratio (quality level). */
    var maxPixelRatio: Double = DEFAULT_MAX_PIXEL_RATIO
        private set

    private val size = Vec2()

    /** Sets the pixel-ratio cap (quality level) and applies it right away; returns the ratio in use. */
    fun setMaxPixelRatio(max: Double): Double {
        maxPixelRatio = max
        val ratio = min(view.devicePixelRatio, max)
        if (backend.pixelRatio != ratio) backend.setPixelRatio(ratio)
        return ratio
    }

    /**
     * Fits the drawing surface and the [camera] to the size of the view; returns true if anything
     * changed.
     */
    fun resize(camera: PerspectiveCamera?): Boolean {
        val w = max(1.0, floor(view.cssWidth))
        val h = max(1.0, floor(view.cssHeight))
        val ratio = min(view.devicePixelRatio, maxPixelRatio)
        backend.getSize(size)
        val changed = size.x != w || size.y != h || backend.pixelRatio != ratio
        if (!changed) return false
        backend.setPixelRatio(ratio)
        backend.setSize(w.toInt(), h.toInt())
        if (camera != null) {
            camera.aspect = w / h
            camera.updateProjectionMatrix()
        }
        return true
    }
}
