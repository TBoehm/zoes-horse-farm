package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.context.FilamentContext
import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.math.LinearRgb

/** The [StagePort] on a [FilamentContext]. */
class FilamentStage(
    private val context: FilamentContext,
) : StagePort {
    override fun applySettings(settings: RenderSettings) = context.applySettings(settings)

    override fun resize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) {
        context.resize(widthPx, heightPx, devicePixelRatio)
    }

    override fun setLens(
        fovDegrees: Float,
        near: Float,
        far: Float,
    ) = context.setLens(fovDegrees, near, far)

    override fun setCameraPose(matrixWorld: FloatArray) = context.setCameraPose(matrixWorld)

    override fun configureSun(
        directionToSun: FloatArray,
        color: LinearRgb,
        intensity: Float,
    ) = context.sun.configure(directionToSun, color, intensity)

    override fun setSunFocus(
        x: Float,
        y: Float,
        z: Float,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    ) = context.sun.setFocus(x, y, z, cameraX, cameraY, cameraZ)

    override fun setAmbient(sh: FloatArray) = context.ambient.set(sh)

    override fun setWind(
        time: Float,
        strength: Float,
    ) = context.setWind(time, strength)

    override fun renderFrame(): Boolean = context.renderFrame()
}
