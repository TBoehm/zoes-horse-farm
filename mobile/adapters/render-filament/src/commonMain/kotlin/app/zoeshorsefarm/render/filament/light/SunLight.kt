package app.zoeshorsefarm.render.filament.light

import app.zoeshorsefarm.render.filament.context.ShadowSettings
import app.zoeshorsefarm.render.filament.math.LinearRgb
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.LightManager
import io.github.erkko68.filament.Scene

/**
 * The sun: one directional light, with sun shadows that follow a focus point.
 *
 * `directionToSun` is the direction towards the sun like the web `sunDirection` (Filament's light
 * direction is the opposite). Intensity is used like three.js uses it (see `RenderSettings.exposure`).
 * [setShadows] switches the shadow map on or off and sets its size; [setFocus] is called with the
 * horse position every frame: it snaps the focus and moves the shadow distance in steps, and only
 * touches Filament when that distance changes.
 */
class SunLight private constructor(
    private val engine: Engine,
    val entity: Entity,
) {
    private var directionX = 0f
    private var directionY = -1f
    private var directionZ = 0f
    private var shadows: ShadowSettings? = null
    private var focus: ShadowFocus? = null
    private var appliedFar = Float.NaN
    private var destroyed = false

    private val instance get() = engine.lightManager.getInstance(entity)

    /** The snapped focus of the last [setFocus], or null while shadows are off. */
    val focusPoint: ShadowFocus? get() = focus

    fun configure(
        directionToSun: FloatArray,
        color: LinearRgb,
        intensity: Float,
    ) {
        require(directionToSun.size == 3) { "direction needs x, y and z" }
        directionX = -directionToSun[0]
        directionY = -directionToSun[1]
        directionZ = -directionToSun[2]
        val lights = engine.lightManager
        lights.setDirection(instance, directionX, directionY, directionZ)
        lights.setColor(instance, color.r, color.g, color.b)
        lights.setIntensity(instance, intensity)
        // the focus axes depend on the direction
        shadows?.let { setShadows(it) }
    }

    /** Null switches the shadows off. A new map size rebuilds the focus (texel size). */
    fun setShadows(settings: ShadowSettings?) {
        shadows = settings
        val lights = engine.lightManager
        if (settings == null) {
            lights.setShadowCaster(instance, false)
            focus = null
            appliedFar = Float.NaN
            return
        }
        focus =
            ShadowFocus(
                -directionX,
                -directionY,
                -directionZ,
                settings.halfExtent,
                settings.mapSize,
            )
        appliedFar = Float.NaN
        lights.setShadowCaster(instance, true)
        lights.setShadowOptions(instance, optionsFor(settings, far = 0f))
    }

    /**
     * The shadows cover the area around (x, y, z) as seen from the camera at (cameraX, cameraY,
     * cameraZ). Does nothing while shadows are off.
     */
    fun setFocus(
        x: Float,
        y: Float,
        z: Float,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    ) {
        val settings = shadows ?: return
        val focus = focus ?: return
        focus.moveTo(x, y, z)
        val far = focus.shadowFar(cameraX, cameraY, cameraZ, settings.shadowFarStep)
        if (far == appliedFar) return
        appliedFar = far
        engine.lightManager.setShadowOptions(instance, optionsFor(settings, far))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        engine.lightManager.destroy(entity)
        engine.entityManager.destroy(entity)
    }

    private fun optionsFor(
        settings: ShadowSettings,
        far: Float,
    ): LightManager.ShadowOptions =
        LightManager.ShadowOptions().apply {
            mapSize = settings.mapSize
            shadowCascades = 1
            constantBias = settings.constantBias
            normalBias = settings.normalBias
            stable = settings.stable
            lispsm = false
            shadowFar = far
        }

    companion object {
        /** Creates the light and adds it to the scene. */
        fun create(
            engine: Engine,
            scene: Scene,
        ): SunLight {
            val entity = engine.entityManager.create()
            LightManager
                .Builder(LightManager.Type.DIRECTIONAL)
                .color(1f, 1f, 1f)
                .intensity(1f)
                .direction(0f, -1f, 0f)
                .castShadows(false)
                .build(engine, entity)
            scene.addEntity(entity)
            return SunLight(engine, entity)
        }
    }
}
