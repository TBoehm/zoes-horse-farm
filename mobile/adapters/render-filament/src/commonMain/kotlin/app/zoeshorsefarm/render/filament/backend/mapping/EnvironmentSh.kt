package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.light.AmbientSh
import app.zoeshorsefarm.scene.graph.EnvironmentLight
import app.zoeshorsefarm.scene.math.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The diffuse light of an [EnvironmentLight] as the spherical harmonics of [AmbientSh]: the gradient
 * dome of the web's sky shader (horizon to zenith, horizon to ground, the sun glow and disc) over a
 * flat floor, which is what the web renders into its PMREM environment map.
 *
 * The radiance of the dome is sampled over the sphere (equal solid angle cells) and projected onto
 * the first two SH bands; the diffuse irradiance divided by PI is `L0 + 2/3 * L1 . n`, so the
 * coefficients are `L0` and `2/3 * L1`. The sun disc (a few thousandths of a steradian, too small for
 * the grid) is added analytically. The specular part of the web's environment map has no
 * counterpart: there are no reflections.
 */
object EnvironmentSh {
    private const val SLICES = 96
    private const val SECTORS = 48
    private const val CELLS = SLICES * SECTORS
    private const val HEMISPHERE_FALLOFF = 0.55
    private const val GROUND_BLEND = 6.0
    private const val SUN_GLOW_WIDE = 0.18
    private const val SUN_GLOW_NARROW = 0.35
    private const val SUN_DISC = 6.0
    private const val DISC_FROM = 0.9993
    private const val DISC_TO = 0.9997

    // L1 = 3 * mean(L * d) and the cosine lobe keeps 2/3 of it: together 2 * mean(L * d)
    private const val BAND_ONE_SCALE = 2.0

    /** The floor of the web environment scene is a disc of this radius. */
    private const val FLOOR_RADIUS = 300.0

    /** The [AmbientSh.FLOAT_COUNT] coefficients of `light`, scaled by `intensity` (`scene.environmentIntensity`). */
    fun compute(
        light: EnvironmentLight,
        intensity: Double,
    ): FloatArray {
        val sum = DoubleArray(SUM_SIZE)
        val dir = DoubleArray(DIRECTION_SIZE)
        val sun = unitSunDirection(light)
        val floorY = floorElevation(light)
        for (slice in 0 until SLICES) {
            val y = 1.0 - (slice + 0.5) * 2.0 / SLICES
            val ring = sqrt(max(0.0, 1.0 - y * y))
            for (sector in 0 until SECTORS) {
                val angle = (sector + 0.5) * 2.0 * PI / SECTORS
                dir[0] = ring * cos(angle)
                dir[1] = y
                dir[2] = ring * sin(angle)
                radiance(light, dir, sun, floorY, sum)
                accumulate(sum, dir)
            }
        }
        addSunDisc(light, sun, sum)
        return coefficients(sum, intensity)
    }

    /** `sum` holds L (3), then L * direction as x, y, z blocks of three. */
    private fun accumulate(
        sum: DoubleArray,
        dir: DoubleArray,
    ) {
        for (c in 0 until CHANNELS) {
            val l = sum[RADIANCE + c]
            sum[L0 + c] += l
            sum[LX + c] += l * dir[0]
            sum[LY + c] += l * dir[1]
            sum[LZ + c] += l * dir[2]
        }
    }

    private fun radiance(
        light: EnvironmentLight,
        d: DoubleArray,
        sun: DoubleArray,
        floorY: Double,
        out: DoubleArray,
    ) {
        val y = d[1]
        val floor = light.floorColor
        if (floor != null && y < floorY) {
            out[RADIANCE] = floor.r
            out[RADIANCE + 1] = floor.g
            out[RADIANCE + 2] = floor.b
            return
        }
        // the web sky shader: horizon to zenith above, horizon to ground below
        val target = if (y > 0.0) light.zenith else light.ground
        val t = if (y > 0.0) min(1.0, y).pow(HEMISPHERE_FALLOFF) else min(1.0, max(0.0, -y * GROUND_BLEND))
        val from = light.horizon
        val s = max(0.0, d[0] * sun[0] + d[1] * sun[1] + d[2] * sun[2])
        val glow = s.pow(6) * SUN_GLOW_WIDE + s.pow(64) * SUN_GLOW_NARROW
        out[RADIANCE] = from.r + (target.r - from.r) * t + light.sunColor.r * glow
        out[RADIANCE + 1] = from.g + (target.g - from.g) * t + light.sunColor.g * glow
        out[RADIANCE + 2] = from.b + (target.b - from.b) * t + light.sunColor.b * glow
    }

    /** The sun disc of the sky shader: `sunColor * smoothstep(0.9993, 0.9997, s) * 6`, integrated exactly. */
    private fun addSunDisc(
        light: EnvironmentLight,
        sun: DoubleArray,
        sum: DoubleArray,
    ) {
        // integral of smoothstep(a, b, cos) over the sphere: 2 pi ((b - a) / 2 + (1 - b))
        val solidAngle = 2.0 * PI * ((DISC_TO - DISC_FROM) / 2.0 + (1.0 - DISC_TO))
        val weight = SUN_DISC * solidAngle * CELLS / (4.0 * PI)
        val color = light.sunColor
        val rgb = doubleArrayOf(color.r, color.g, color.b)
        for (c in 0 until CHANNELS) {
            val l = rgb[c] * weight
            sum[L0 + c] += l
            sum[LX + c] += l * sun[0]
            sum[LY + c] += l * sun[1]
            sum[LZ + c] += l * sun[2]
        }
    }

    private fun coefficients(
        sum: DoubleArray,
        intensity: Double,
    ): FloatArray {
        val out = FloatArray(AmbientSh.FLOAT_COUNT)
        val mean = intensity / CELLS
        for (c in 0 until CHANNELS) {
            out[c] = (sum[L0 + c] * mean).toFloat()
            out[CHANNELS + c] = (sum[LY + c] * mean * BAND_ONE_SCALE).toFloat()
            out[2 * CHANNELS + c] = (sum[LZ + c] * mean * BAND_ONE_SCALE).toFloat()
            out[3 * CHANNELS + c] = (sum[LX + c] * mean * BAND_ONE_SCALE).toFloat()
        }
        return out
    }

    private fun unitSunDirection(light: EnvironmentLight): DoubleArray {
        val v = light.sunDirection
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return if (length >
            0.0
        ) {
            doubleArrayOf(v.x / length, v.y / length, v.z / length)
        } else {
            doubleArrayOf(0.0, 1.0, 0.0)
        }
    }

    /** Elevation (y of the direction) below which the floor hides the dome. */
    private fun floorElevation(light: EnvironmentLight): Double {
        val height = abs(light.floorHeight)
        return -height / sqrt(FLOOR_RADIUS * FLOOR_RADIUS + height * height)
    }

    private const val CHANNELS = 3
    private const val RADIANCE = 0
    private const val L0 = 3
    private const val LX = 6
    private const val LY = 9
    private const val LZ = 12
    private const val SUM_SIZE = 15
    private const val DIRECTION_SIZE = 3
}
