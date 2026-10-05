package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.math.LinearRgb

/**
 * What the scene's lights, camera and environment drive in Filament: the part of `FilamentContext`
 * that `StageSync` uses. `FilamentStage` implements it; tests record the calls.
 */
interface StagePort {
    /** A new graphics level: only what differs from the current settings is touched. */
    fun applySettings(settings: RenderSettings)

    /** The surface has this physical size and device pixel ratio. */
    fun resize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    )

    fun setLens(
        fovDegrees: Float,
        near: Float,
        far: Float,
    )

    /** The camera's world matrix, column-major. */
    fun setCameraPose(matrixWorld: FloatArray)

    /** `directionToSun` points towards the sun. */
    fun configureSun(
        directionToSun: FloatArray,
        color: LinearRgb,
        intensity: Float,
    )

    /** The shadows cover the area around the focus as seen from the camera position. */
    fun setSunFocus(
        x: Float,
        y: Float,
        z: Float,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    )

    /** Spherical harmonics of the ambient light (see `AmbientSh`). */
    fun setAmbient(sh: FloatArray)

    fun setWind(
        time: Float,
        strength: Float,
    )

    /** Draws the frame; false if nothing was drawn. */
    fun renderFrame(): Boolean
}
