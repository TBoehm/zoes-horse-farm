package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.math.LinearRgb

/** A [StagePort] that records what it was told. */
class FakeStage : StagePort {
    val settings = ArrayList<RenderSettings>()
    val resizes = ArrayList<Triple<Int, Int, Float>>()
    val lenses = ArrayList<Triple<Float, Float, Float>>()
    val poses = ArrayList<FloatArray>()
    val suns = ArrayList<Triple<List<Float>, LinearRgb, Float>>()
    val focuses = ArrayList<List<Float>>()
    val ambients = ArrayList<FloatArray>()
    val winds = ArrayList<Pair<Float, Float>>()
    var frames = 0
        private set
    var drawResult = true

    override fun applySettings(settings: RenderSettings) {
        this.settings += settings
    }

    override fun resize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) {
        resizes += Triple(widthPx, heightPx, devicePixelRatio)
    }

    override fun setLens(
        fovDegrees: Float,
        near: Float,
        far: Float,
    ) {
        lenses += Triple(fovDegrees, near, far)
    }

    override fun setCameraPose(matrixWorld: FloatArray) {
        poses += matrixWorld.copyOf()
    }

    override fun configureSun(
        directionToSun: FloatArray,
        color: LinearRgb,
        intensity: Float,
    ) {
        suns += Triple(directionToSun.toList(), color, intensity)
    }

    override fun setSunFocus(
        x: Float,
        y: Float,
        z: Float,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    ) {
        focuses += listOf(x, y, z, cameraX, cameraY, cameraZ)
    }

    override fun setAmbient(sh: FloatArray) {
        ambients += sh.copyOf()
    }

    override fun setWind(
        time: Float,
        strength: Float,
    ) {
        winds += time to strength
    }

    override fun renderFrame(): Boolean {
        frames++
        return drawResult
    }

    /** The number of calls that change something (everything but the frame itself and the focus). */
    fun changes(): Int = settings.size + lenses.size + poses.size + suns.size + ambients.size + winds.size
}
