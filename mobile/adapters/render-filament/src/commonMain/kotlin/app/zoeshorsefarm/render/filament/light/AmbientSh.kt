package app.zoeshorsefarm.render.filament.light

import app.zoeshorsefarm.render.filament.math.LinearRgb
import kotlin.math.PI

/**
 * Ambient light as spherical harmonics for Filament's `IndirectLight.Builder.irradiance(bands, sh)`.
 *
 * Filament's shader reads diffuse image based light as a plain linear combination of the first
 * bands (`sh[0] + sh[1] * n.y + sh[2] * n.z + sh[3] * n.x` for two bands, each a float3) and the
 * result is multiplied by the albedo, so the coefficients here are already what the shader adds
 * up: they are not raw radiance projections. Two bands (4 coefficients, 12 floats) are exact for
 * everything that depends linearly on the normal's up component, which is all a sky/ground
 * gradient needs. Order is Filament's: constant, y, z, x.
 *
 * Two kinds of terms mirror the two things three.js lights the web scene with:
 *
 *  - [hemisphere]: a `HemisphereLight`. three.js (r155 and later, no legacy lights) adds
 *    `mix(ground, sky, 0.5 + 0.5 * n.y) * intensity` as irradiance and multiplies it by
 *    `albedo / PI`. With the exposure fixed at 1 the same result needs coefficients divided by PI.
 *  - [environment]: the PMREM environment map (`scene.environment`). Its diffuse part is the
 *    cosine convolved radiance, which for a gradient `a + b * n.y` is `a + (2/3) * b * n.y`, times
 *    albedo, with no PI.
 *
 * The specular part of an environment map (reflections) needs a prefiltered cubemap and is not
 * covered here.
 */
object AmbientSh {
    const val BANDS = 2
    const val FLOAT_COUNT = 12

    private const val COSINE_LOBE_BAND_1 = 2f / 3f
    private val INV_PI = (1.0 / PI).toFloat()

    /** Coefficients of a three.js style hemisphere light. */
    fun hemisphere(
        sky: LinearRgb,
        ground: LinearRgb,
        intensity: Float,
    ): FloatArray = gradient(sky, ground, intensity * INV_PI, 1f)

    /** Coefficients of the diffuse light of an environment with a sky (up) and a ground (down) radiance. */
    fun environment(
        sky: LinearRgb,
        ground: LinearRgb,
        intensity: Float,
    ): FloatArray = gradient(sky, ground, intensity, COSINE_LOBE_BAND_1)

    fun sum(vararg terms: FloatArray): FloatArray {
        val out = FloatArray(FLOAT_COUNT)
        for (term in terms) {
            for (i in 0 until FLOAT_COUNT) out[i] += term[i]
        }
        return out
    }

    /** What Filament's shader adds up for a normal: the diffuse light before it is multiplied by the albedo. */
    fun diffuseAt(
        sh: FloatArray,
        nx: Float,
        ny: Float,
        nz: Float,
    ): LinearRgb =
        LinearRgb(
            sh[0] + sh[3] * ny + sh[6] * nz + sh[9] * nx,
            sh[1] + sh[4] * ny + sh[7] * nz + sh[10] * nx,
            sh[2] + sh[5] * ny + sh[8] * nz + sh[11] * nx,
        )

    private fun gradient(
        sky: LinearRgb,
        ground: LinearRgb,
        scale: Float,
        bandOneFactor: Float,
    ): FloatArray {
        val out = FloatArray(FLOAT_COUNT)
        // mix(ground, sky, 0.5 + 0.5 y) = (sky + ground) / 2 + (sky - ground) / 2 * y
        out[0] = (sky.r + ground.r) * 0.5f * scale
        out[1] = (sky.g + ground.g) * 0.5f * scale
        out[2] = (sky.b + ground.b) * 0.5f * scale
        out[3] = (sky.r - ground.r) * 0.5f * scale * bandOneFactor
        out[4] = (sky.g - ground.g) * 0.5f * scale * bandOneFactor
        out[5] = (sky.b - ground.b) * 0.5f * scale * bandOneFactor
        return out
    }
}
