package app.zoeshorsefarm.render.filament.light

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The shadow area that follows a focus point (the web `setShadowFocus`): the point is snapped to the
 * texel grid of the shadow map in light space, so the shadows do not shimmer while the focus moves.
 *
 * `sunX/Y/Z` is the direction towards the sun (normalized). The sun's `halfExtent` (24 m) and the map
 * size give the texel size on the ground. The snapped focus and the sun position (focus + direction
 * times [SUN_DISTANCE]) are what three.js set as `sun.target` and `sun.position`.
 *
 * Filament fits the shadow map of a directional light to the camera frustum instead of to a box
 * around a target, so the focus acts through [shadowFar]: the shadowed part of the view ends just
 * behind the far edge of the focus area. Filament's own `stable` option snaps the frustum to its
 * texels; the snapped focus still serves as the point to measure from.
 *
 * Mutable scratch values, no allocation after construction.
 */
class ShadowFocus(
    sunX: Float,
    sunY: Float,
    sunZ: Float,
    val halfExtent: Float = DEFAULT_HALF_EXTENT,
    mapSize: Int,
) {
    private val dirX: Float
    private val dirY: Float
    private val dirZ: Float

    /** The light's right and up axes (`right = forward x worldUp`, `up = right x forward`). */
    val rightX: Float
    val rightY: Float
    val rightZ: Float
    val upX: Float
    val upY: Float
    val upZ: Float

    /** Edge length of a shadow map texel on the ground, in metres. */
    val texelSize: Float

    /** The snapped focus. */
    var x = 0f
        private set
    var y = 0f
        private set
    var z = 0f
        private set

    init {
        require(mapSize > 0) { "mapSize must be positive" }
        val length = sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ)
        require(length > 0f) { "the sun direction must not be zero" }
        dirX = sunX / length
        dirY = sunY / length
        dirZ = sunZ / length
        // forward = -dir (the light travels from the sun to the ground); right = forward x (0, 1, 0)
        val fx = -dirX
        val fy = -dirY
        val fz = -dirZ
        var rx = fy * 0f - fz * 1f
        var ry = fz * 0f - fx * 0f
        var rz = fx * 1f - fy * 0f
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rl
        ry /= rl
        rz /= rl
        var ux = ry * fz - rz * fy
        var uy = rz * fx - rx * fz
        var uz = rx * fy - ry * fx
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul
        uy /= ul
        uz /= ul
        rightX = rx
        rightY = ry
        rightZ = rz
        upX = ux
        upY = uy
        upZ = uz
        texelSize = halfExtent * 2f / mapSize
    }

    val sunX: Float get() = x + dirX * SUN_DISTANCE
    val sunY: Float get() = y + dirY * SUN_DISTANCE
    val sunZ: Float get() = z + dirZ * SUN_DISTANCE

    /** Sets the focus and snaps it to the texel grid along the light's right and up axes. */
    fun moveTo(
        focusX: Float,
        focusY: Float,
        focusZ: Float,
    ) {
        val r = focusX * rightX + focusY * rightY + focusZ * rightZ
        val u = focusX * upX + focusY * upY + focusZ * upZ
        // Math.round of JavaScript: ties go up
        val dr = floor(r / texelSize + 0.5f) * texelSize - r
        val du = floor(u / texelSize + 0.5f) * texelSize - u
        x = focusX + rightX * dr + upX * du
        y = focusY + rightY * dr + upY * du
        z = focusZ + rightZ * dr + upZ * du
    }

    /**
     * Distance from the camera up to which Filament should shadow: the camera's distance to the
     * snapped focus plus the half extent, rounded up to a multiple of `step` (so the light's shadow
     * options only change when the camera moves a few metres, not every frame) and at least
     * [MIN_SHADOW_FAR].
     */
    fun shadowFar(
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
        step: Float,
    ): Float {
        val dx = x - cameraX
        val dy = y - cameraY
        val dz = z - cameraZ
        val far = sqrt(dx * dx + dy * dy + dz * dz) + halfExtent
        val stepped = if (step > 0f) ceil(far / step - EPSILON) * step else far
        return max(MIN_SHADOW_FAR, stepped)
    }

    companion object {
        /** Half extent of the web shadow camera (m). */
        const val DEFAULT_HALF_EXTENT = 24f

        /** Distance of the web sun from its target (m). */
        const val SUN_DISTANCE = 90f
        const val MIN_SHADOW_FAR = 8f
        private const val EPSILON = 1e-4f
    }
}
