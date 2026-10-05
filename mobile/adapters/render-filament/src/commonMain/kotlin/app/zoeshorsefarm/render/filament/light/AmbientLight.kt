package app.zoeshorsefarm.render.filament.light

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndirectLight
import io.github.erkko68.filament.Scene

/**
 * The hemisphere light and the environment map of the web scene, as one `IndirectLight` made of
 * spherical harmonics (see [AmbientSh]). Setting a new value builds a new light and destroys the
 * old one; a level change that drops the environment map calls [clear] or sets less light.
 */
class AmbientLight(
    private val engine: Engine,
    private val scene: Scene,
) {
    private var light: IndirectLight? = null

    /** `sh` is [AmbientSh.FLOAT_COUNT] floats, for example `AmbientSh.sum(hemisphere, environment)`. */
    fun set(sh: FloatArray) {
        require(sh.size == AmbientSh.FLOAT_COUNT) { "need ${AmbientSh.FLOAT_COUNT} floats, not ${sh.size}" }
        val fresh =
            IndirectLight
                .Builder()
                .irradiance(AmbientSh.BANDS, sh)
                .intensity(1f)
                .build(engine)
        scene.indirectLight = fresh
        light?.let { engine.destroy(it) }
        light = fresh
    }

    /** No ambient light at all. */
    fun clear() {
        scene.indirectLight = null
        light?.let { engine.destroy(it) }
        light = null
    }

    fun destroy() = clear()
}
