package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.MeshData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A scene `Sprite` drawn as a quad that faces the camera, the way the three.js sprite shader does it:
 * the node's world matrix gives the position and (the lengths of its x and y axes) the size; the
 * quad is turned to the camera's right/up axes, turned by the material's `rotation`, and moved so
 * that `center` (0..1, default 0.5/0.5) is at the node's position.
 */
object SpriteBillboard {
    /** The unit quad (-0.5..0.5 on x and y) with uvs, drawn by every sprite. */
    fun quad(): MeshData =
        MeshData(
            positions = floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f),
            uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            indices = intArrayOf(0, 1, 2, 0, 2, 3),
        )

    /**
     * Writes the model matrix of the quad into `out` (16 floats, column-major). `nodeWorld` and
     * `cameraWorld` are world matrices as floats. Allocates nothing.
     */
    fun compose(
        nodeWorld: FloatArray,
        cameraWorld: FloatArray,
        centerX: Float,
        centerY: Float,
        rotation: Float,
        out: FloatArray,
    ) {
        val sx = sqrt(nodeWorld[0] * nodeWorld[0] + nodeWorld[1] * nodeWorld[1] + nodeWorld[2] * nodeWorld[2])
        val sy = sqrt(nodeWorld[4] * nodeWorld[4] + nodeWorld[5] * nodeWorld[5] + nodeWorld[6] * nodeWorld[6])
        val c = cos(rotation)
        val s = sin(rotation)
        // the 2d map of the quad corner to camera right/up: rotation times scale
        val a00 = c * sx
        val a01 = -s * sy
        val a10 = s * sx
        val a11 = c * sy
        val pivotX = centerX - HALF
        val pivotY = centerY - HALF
        val offsetRight = -(a00 * pivotX + a01 * pivotY)
        val offsetUp = -(a10 * pivotX + a11 * pivotY)
        for (i in 0..2) {
            val right = cameraWorld[i]
            val up = cameraWorld[4 + i]
            out[i] = right * a00 + up * a10
            out[4 + i] = right * a01 + up * a11
            // the quad is flat: the camera's back axis keeps the matrix invertible
            out[8 + i] = cameraWorld[8 + i]
            out[12 + i] = nodeWorld[12 + i] + right * offsetRight + up * offsetUp
        }
        out[3] = 0f
        out[7] = 0f
        out[11] = 0f
        out[15] = 1f
    }

    private const val HALF = 0.5f
}
