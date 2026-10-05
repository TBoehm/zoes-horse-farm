package app.zoeshorsefarm.render.filament.light

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndirectLight
import io.github.erkko68.filament.Scene

/**
 * The hemisphere light and the environment map of the web scene, as one `IndirectLight` made of
 * spherical harmonics (see [AmbientSh]). Setting a new value builds a new light and destroys the
 * old one.
 *
 * A scene always has one: Filament multiplies the fog colour by the intensity of the indirect light
 * and, without one, falls back to its default of 30 000, which would turn the fog white. [clear]
 * therefore installs a black light (no ambient light) instead of removing it. The intensity is
 * always 1, so the coefficients carry the brightness.
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

    /** No ambient light: a black one, so that the fog keeps its colour. */
    fun clear() = set(FloatArray(AmbientSh.FLOAT_COUNT))

    /** Takes the light out of the scene and frees it (the scene is being torn down). */
    fun destroy() {
        scene.indirectLight = null
        light?.let { engine.destroy(it) }
        light = null
    }
}
