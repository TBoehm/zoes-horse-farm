package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.StagePort
import app.zoeshorsefarm.render.filament.backend.mapping.AcesInverse
import app.zoeshorsefarm.render.filament.backend.mapping.EnvironmentSh
import app.zoeshorsefarm.render.filament.context.FogParams
import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.context.ShadowSettings
import app.zoeshorsefarm.render.filament.context.ToneMapping
import app.zoeshorsefarm.render.filament.light.AmbientSh
import app.zoeshorsefarm.render.filament.math.LinearRgb
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.EnvironmentLight
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import app.zoeshorsefarm.scene.render.ToneMapping as SceneToneMapping

/** The settings of the backend that the stage turns into Filament settings. */
class StageSettings {
    var maxPixelRatio: Double = 1.0
    var msaaSamples: Int = 0
    var toneMapping: SceneToneMapping = SceneToneMapping.NONE
    var exposure: Double = 1.0
    var shadowsEnabled: Boolean = false
}

/** The "shadow map" object a [DirectionalLight] gets while its shadows are on: dispose it to release the map. */
internal class ShadowMapResource : GpuResource()

/**
 * Keeps the lights, camera, fog, tone mapping and shadows of Filament in step with the scene:
 *
 * - the first visible `DirectionalLight` is the sun (colour, intensity, direction from its position
 *   to its target); its shadows follow the target node like the web `setShadowFocus`
 * - `HemisphereLight`s and the scene's `EnvironmentLight` become one ambient spherical harmonics
 *   light (the web PMREM environment map has no specular counterpart here)
 * - `scene.fog` and `scene.background` become Filament's fog and clear colour, the backend's
 *   tone mapping, exposure, pixel ratio, antialiasing and shadow switch become `RenderSettings`
 * - the camera's pose and lens go to the Filament camera
 *
 * Every value is compared with what was sent last, so a steady frame makes no call except the shadow
 * focus and a moving camera.
 */
class StageSync(
    private val port: StagePort,
    private val settings: StageSettings,
    private val log: SyncLog = SyncLog.SILENT,
) {
    private val cameraPose = TransformCache()
    private val inputs = SettingsInputs()
    private val applied = SettingsInputs()
    private var appliedValid = false
    private val lens = FloatArray(LENS_SIZE) { Float.NaN }
    private val sun = FloatArray(SUN_SIZE) { Float.NaN }
    private val sunCandidate = FloatArray(SUN_SIZE)
    private val ambientKey = DoubleArray(AMBIENT_KEY_SIZE)
    private val lastAmbientKey = DoubleArray(AMBIENT_KEY_SIZE) { Double.NaN }
    private val wind = FloatArray(2) { Float.NaN }
    private var warnedLens = false
    private var shadowMap: ShadowMapResource? = null
    private var shadowOwner: DirectionalLight? = null

    /** Told when the shadow map or the environment light is disposed. */
    private val disposeListener = DisposeListener { onDisposed(it) }
    private var watchedEnvironment: EnvironmentLight? = null

    /** Set when the environment light was disposed: the ambient light is computed again. */
    private var ambientDirty = true

    /** Sends everything again at the next [sync] (after the surface or the device was lost and came back). */
    fun invalidate() {
        appliedValid = false
        cameraPose.invalidate()
        lens.fill(Float.NaN)
        sun.fill(Float.NaN)
        lastAmbientKey.fill(Double.NaN)
        wind.fill(Float.NaN)
    }

    /** A scene object was disposed: the stage forgets the shadow map or recomputes the ambient light. */
    fun onDisposed(resource: GpuObject) {
        if (resource === shadowMap) {
            shadowOwner?.shadow?.map = null
            shadowMap = null
            shadowOwner = null
        }
        ambientDirty = true
    }

    fun sync(
        scene: Scene,
        camera: Camera,
        lights: SceneLights,
        windSource: Wind?,
    ) {
        val sunLight = lights.sun
        syncSettings(scene, sunLight)
        syncCamera(camera)
        syncSun(sunLight, camera)
        syncAmbient(scene, lights)
        syncWind(windSource)
    }

    /** True while the sun casts shadows (the shadow pass is drawn). */
    val shadowsActive: Boolean get() = applied.shadowMapSize > 0

    // ---- settings ------------------------------------------------------------------------------

    private fun syncSettings(
        scene: Scene,
        sunLight: DirectionalLight?,
    ) {
        val next = inputs
        next.maxPixelRatio = settings.maxPixelRatio.toFloat()
        next.msaa = settings.msaaSamples
        next.toneMapping = settings.toneMapping
        next.exposure = settings.exposure.toFloat()
        val shadowLight = if (settings.shadowsEnabled && sunLight != null && sunLight.castShadow) sunLight else null
        next.shadowMapSize = if (shadowLight != null) shadowMapSizeOf(shadowLight) else 0
        next.shadowHalfExtent = if (shadowLight != null) halfExtentOf(shadowLight) else 0f
        val fog = scene.fog
        next.fogNear = fog?.near?.toFloat() ?: Float.NaN
        next.fogFar = fog?.far?.toFloat() ?: Float.NaN
        copyColor(fog?.color, next.fogColor)
        copyColor(scene.background, next.clear)
        if (appliedValid && next.sameAs(applied)) return
        applied.copyFrom(next)
        appliedValid = true
        port.applySettings(toRenderSettings(next))
    }

    private fun toRenderSettings(next: SettingsInputs): RenderSettings {
        val fog =
            if (next.fogNear.isNaN()) null else FogParams.fromLinear(next.fogNear, next.fogFar, fogColorOf(next))
        val shadows =
            if (next.shadowMapSize == 0) {
                null
            } else {
                ShadowSettings(mapSize = next.shadowMapSize, halfExtent = next.shadowHalfExtent)
            }
        return RenderSettings(
            maxPixelRatio = max(MIN_PIXEL_RATIO, next.maxPixelRatio),
            msaaSamples = next.msaa,
            shadows = shadows,
            fog = fog,
            toneMapping =
                if (next.toneMapping ==
                    SceneToneMapping.NONE
                ) {
                    ToneMapping.LINEAR
                } else {
                    ToneMapping.ACES_LEGACY
                },
            exposure = max(MIN_EXPOSURE, next.exposure),
            clearColor = LinearRgb(next.clear[0], next.clear[1], next.clear[2]),
        )
    }

    /**
     * three.js fogs in output space (after tone mapping), Filament in HDR before it: with ACES the fog colour
     * is passed through the inverse curve, so that a fully fogged pixel comes out as the fog colour.
     */
    private fun fogColorOf(next: SettingsInputs): LinearRgb {
        val rgb = next.fogColor
        if (next.toneMapping == SceneToneMapping.NONE) return LinearRgb(rgb[0], rgb[1], rgb[2])
        val hdr = AcesInverse.inverse(rgb, max(MIN_EXPOSURE, next.exposure).toDouble())
        return LinearRgb(hdr[0], hdr[1], hdr[2])
    }

    private fun shadowMapSizeOf(light: DirectionalLight): Int {
        val wanted =
            max(
                ShadowSettings.MIN_MAP_SIZE,
                min(
                    MAX_SHADOW_MAP,
                    light.shadow.mapSize.x
                        .toInt(),
                ),
            )
        var size = ShadowSettings.MIN_MAP_SIZE
        while (size < wanted) size = size shl 1
        return size
    }

    private fun halfExtentOf(light: DirectionalLight): Float {
        val camera = light.shadow.camera
        // three.js refits the shadow projection every frame; the view code may have changed the box since
        camera.updateProjectionMatrix()
        val half = ((camera.right - camera.left) / (2.0 * camera.zoom)).toFloat()
        return if (half > 0f) half else DEFAULT_HALF_EXTENT
    }

    private fun copyColor(
        color: Color?,
        out: FloatArray,
    ) {
        out[0] = color?.r?.toFloat() ?: 0f
        out[1] = color?.g?.toFloat() ?: 0f
        out[2] = color?.b?.toFloat() ?: 0f
    }

    // ---- camera --------------------------------------------------------------------------------

    private fun syncCamera(camera: Camera) {
        if (camera is PerspectiveCamera) {
            val fov = camera.getEffectiveFOV().toFloat()
            val near = camera.near.toFloat()
            val far = camera.far.toFloat()
            if (fov != lens[0] || near != lens[1] || far != lens[2]) {
                lens[0] = fov
                lens[1] = near
                lens[2] = far
                port.setLens(fov, near, far)
            }
        } else if (!warnedLens) {
            warnedLens = true
            log.warn("the main camera is not a PerspectiveCamera: its lens is ignored")
        }
        if (cameraPose.refresh(camera.matrixWorld)) port.setCameraPose(cameraPose.current)
    }

    // ---- sun -----------------------------------------------------------------------------------

    private fun syncSun(
        light: DirectionalLight?,
        camera: Camera,
    ) {
        if (light == null) {
            configureSun(0f, 1f, 0f, 0f, 0f, 0f, 0f)
            releaseShadowMap()
            return
        }
        light.target.updateWorldMatrix(updateParents = true, updateChildren = false)
        val position = light.matrixWorld.e
        val target = light.target.matrixWorld.e
        var dx = (position[E_X] - target[E_X])
        var dy = (position[E_Y] - target[E_Y])
        var dz = (position[E_Z] - target[E_Z])
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length > 0.0) {
            dx /= length
            dy /= length
            dz /= length
        } else {
            dx = 0.0
            dy = 1.0
            dz = 0.0
        }
        val color = light.color
        configureSun(
            dx.toFloat(),
            dy.toFloat(),
            dz.toFloat(),
            color.r.toFloat(),
            color.g.toFloat(),
            color.b.toFloat(),
            light.intensity.toFloat(),
        )
        if (applied.shadowMapSize > 0) {
            acquireShadowMap(light)
            val cam = camera.matrixWorld.e
            port.setSunFocus(
                target[E_X].toFloat(),
                target[E_Y].toFloat(),
                target[E_Z].toFloat(),
                cam[E_X].toFloat(),
                cam[E_Y].toFloat(),
                cam[E_Z].toFloat(),
            )
        } else {
            releaseShadowMap()
        }
    }

    @Suppress("LongParameterList") // the seven numbers of the sun, compared and sent together
    private fun configureSun(
        dx: Float,
        dy: Float,
        dz: Float,
        r: Float,
        g: Float,
        b: Float,
        intensity: Float,
    ) {
        val next = sunCandidate
        next[0] = dx
        next[1] = dy
        next[2] = dz
        next[3] = r
        next[4] = g
        next[5] = b
        next[6] = intensity
        if (next.contentEquals(sun)) return
        next.copyInto(sun)
        port.configureSun(floatArrayOf(dx, dy, dz), LinearRgb(r, g, b), intensity)
    }

    private fun acquireShadowMap(light: DirectionalLight) {
        if (shadowMap != null && shadowOwner === light) return
        releaseShadowMap()
        val map = ShadowMapResource()
        map.addDisposeListener(disposeListener)
        light.shadow.map = map
        shadowMap = map
        shadowOwner = light
    }

    private fun releaseShadowMap() {
        val map = shadowMap ?: return
        map.removeDisposeListener(disposeListener)
        if (shadowOwner?.shadow?.map === map) shadowOwner?.shadow?.map = null
        shadowMap = null
        shadowOwner = null
    }

    // ---- ambient light -------------------------------------------------------------------------

    private fun syncAmbient(
        scene: Scene,
        lights: SceneLights,
    ) {
        watchEnvironment(scene.environment)
        fillAmbientKey(scene, lights)
        if (!ambientDirty && ambientKey.contentEquals(lastAmbientKey)) return
        ambientDirty = false
        ambientKey.copyInto(lastAmbientKey)
        val terms = ArrayList<FloatArray>(MAX_HEMISPHERES + 1)
        val count = min(lights.hemispheres.size, MAX_HEMISPHERES)
        for (i in 0 until count) {
            val light = lights.hemispheres[i]
            terms += AmbientSh.hemisphere(toLinear(light.color), toLinear(light.groundColor), light.intensity.toFloat())
        }
        scene.environment?.let { terms += EnvironmentSh.compute(it, scene.environmentIntensity) }
        port.setAmbient(
            if (terms.isEmpty()) FloatArray(AmbientSh.FLOAT_COUNT) else AmbientSh.sum(*terms.toTypedArray()),
        )
    }

    private fun watchEnvironment(environment: EnvironmentLight?) {
        if (environment === watchedEnvironment) return
        watchedEnvironment?.removeDisposeListener(disposeListener)
        environment?.addDisposeListener(disposeListener)
        watchedEnvironment = environment
    }

    private fun toLinear(color: Color) = LinearRgb(color.r.toFloat(), color.g.toFloat(), color.b.toFloat())

    private fun fillAmbientKey(
        scene: Scene,
        lights: SceneLights,
    ) {
        var n = 0
        val environment = scene.environment
        ambientKey[n++] = if (environment != null) 1.0 else 0.0
        ambientKey[n++] = scene.environmentIntensity
        if (environment != null) {
            n = putColor(environment.zenith, n)
            n = putColor(environment.horizon, n)
            n = putColor(environment.ground, n)
            n = putColor(environment.sunColor, n)
            ambientKey[n++] = environment.sunDirection.x
            ambientKey[n++] = environment.sunDirection.y
            ambientKey[n++] = environment.sunDirection.z
            val floor = environment.floorColor
            ambientKey[n++] = if (floor != null) 1.0 else 0.0
            if (floor != null) n = putColor(floor, n)
            ambientKey[n++] = environment.floorHeight
        }
        val count = min(lights.hemispheres.size, MAX_HEMISPHERES)
        ambientKey[n++] = count.toDouble()
        for (i in 0 until count) {
            val light = lights.hemispheres[i]
            n = putColor(light.color, n)
            n = putColor(light.groundColor, n)
            ambientKey[n++] = light.intensity
        }
        ambientKey.fill(0.0, n, ambientKey.size)
    }

    private fun putColor(
        color: Color,
        at: Int,
    ): Int {
        ambientKey[at] = color.r
        ambientKey[at + 1] = color.g
        ambientKey[at + 2] = color.b
        return at + COLOR_SIZE
    }

    // ---- wind ----------------------------------------------------------------------------------

    private fun syncWind(source: Wind?) {
        if (source == null) return
        val time = source.time.toFloat()
        val strength = source.strength.toFloat()
        if (time == wind[0] && strength == wind[1]) return
        wind[0] = time
        wind[1] = strength
        port.setWind(time, strength)
    }

    /** The values of the settings that are compared frame by frame. */
    private class SettingsInputs {
        var maxPixelRatio = Float.NaN
        var msaa = -1
        var toneMapping = SceneToneMapping.NONE
        var exposure = Float.NaN
        var shadowMapSize = -1
        var shadowHalfExtent = Float.NaN
        var fogNear = Float.NaN
        var fogFar = Float.NaN
        val fogColor = FloatArray(COLOR_SIZE)
        val clear = FloatArray(COLOR_SIZE)

        fun sameAs(other: SettingsInputs): Boolean =
            maxPixelRatio == other.maxPixelRatio && msaa == other.msaa && toneMapping == other.toneMapping &&
                exposure == other.exposure && shadowMapSize == other.shadowMapSize &&
                shadowHalfExtent == other.shadowHalfExtent && sameFog(other) && clear.contentEquals(other.clear)

        private fun sameFog(other: SettingsInputs): Boolean =
            fogNear.isNaN() == other.fogNear.isNaN() &&
                (
                    fogNear.isNaN() ||
                        (fogNear == other.fogNear && fogFar == other.fogFar && fogColor.contentEquals(other.fogColor))
                )

        fun copyFrom(other: SettingsInputs) {
            maxPixelRatio = other.maxPixelRatio
            msaa = other.msaa
            toneMapping = other.toneMapping
            exposure = other.exposure
            shadowMapSize = other.shadowMapSize
            shadowHalfExtent = other.shadowHalfExtent
            fogNear = other.fogNear
            fogFar = other.fogFar
            other.fogColor.copyInto(fogColor)
            other.clear.copyInto(clear)
        }
    }

    private companion object {
        const val LENS_SIZE = 3
        const val SUN_SIZE = 7
        const val COLOR_SIZE = 3
        const val MAX_HEMISPHERES = 4

        // environment: flag, intensity, 4 colours, sun direction, floor flag and colour, floor height;
        // then the hemispheres
        const val AMBIENT_KEY_SIZE = 2 + 4 * COLOR_SIZE + 3 + 1 + COLOR_SIZE + 1 + 1 + MAX_HEMISPHERES * 7
        const val MIN_PIXEL_RATIO = 0.25f
        const val MIN_EXPOSURE = 0.01f
        const val MAX_SHADOW_MAP = 4096
        const val DEFAULT_HALF_EXTENT = 24f
        const val E_X = 12
        const val E_Y = 13
        const val E_Z = 14
    }
}
