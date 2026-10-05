package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.MathUtils
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.atan
import kotlin.math.tan

/** Base of the cameras (three.js `Camera`); looks along its local -Z axis. */
abstract class Camera : Node() {
    val matrixWorldInverse: Mat4 = Mat4()
    val projectionMatrix: Mat4 = Mat4()
    val projectionMatrixInverse: Mat4 = Mat4()

    abstract fun updateProjectionMatrix()

    /** World direction the camera looks in (its local -Z axis, unlike other nodes). */
    override fun getWorldDirection(target: Vec3): Vec3 = super.getWorldDirection(target).negate()

    override fun updateMatrixWorld(force: Boolean) {
        super.updateMatrixWorld(force)
        updateInverse()
    }

    override fun updateWorldMatrix(
        updateParents: Boolean,
        updateChildren: Boolean,
        force: Boolean,
    ) {
        super.updateWorldMatrix(updateParents, updateChildren, force)
        updateInverse()
    }

    private fun updateInverse() {
        matrixWorld.decompose(position0, quaternion0, scale0)
        if (scale0.x == 1.0 && scale0.y == 1.0 && scale0.z == 1.0) {
            matrixWorldInverse.copy(matrixWorld).invert()
        } else {
            matrixWorldInverse.compose(position0, quaternion0, scale0.set(1.0, 1.0, 1.0)).invert()
        }
    }

    private companion object {
        val position0 = Vec3()
        val quaternion0 = Quat()
        val scale0 = Vec3()
    }
}

/** Perspective camera (three.js `PerspectiveCamera`, vertical field of view in degrees). */
class PerspectiveCamera(
    fov: Double = 50.0,
    aspect: Double = 1.0,
    near: Double = 0.1,
    far: Double = 2000.0,
) : Camera() {
    var fov: Double = fov
    var zoom: Double = 1.0
    var near: Double = near
    var far: Double = far
    var aspect: Double = aspect

    init {
        updateProjectionMatrix()
    }

    /** Call after changing [fov], [aspect], [near], [far] or [zoom]. */
    override fun updateProjectionMatrix() {
        val top = near * tan(MathUtils.DEG2RAD * 0.5 * fov) / zoom
        val height = 2 * top
        val width = aspect * height
        val left = -0.5 * width
        projectionMatrix.makePerspective(left, left + width, top, top - height, near, far)
        projectionMatrixInverse.copy(projectionMatrix).invert()
    }

    /** Vertical field of view in degrees after [zoom]. */
    fun getEffectiveFOV(): Double = MathUtils.RAD2DEG * 2 * atan(tan(MathUtils.DEG2RAD * 0.5 * fov) / zoom)

    /** Width and height of the visible plane at `distance` in front of the camera. */
    fun getViewSize(
        distance: Double,
        target: Vec2,
    ): Vec2 {
        viewPoint.set(-1.0, -1.0, 0.5).applyMatrix4(projectionMatrixInverse)
        val minX = viewPoint.x * (-distance / viewPoint.z)
        val minY = viewPoint.y * (-distance / viewPoint.z)
        viewPoint.set(1.0, 1.0, 0.5).applyMatrix4(projectionMatrixInverse)
        val maxX = viewPoint.x * (-distance / viewPoint.z)
        val maxY = viewPoint.y * (-distance / viewPoint.z)
        return target.set(maxX - minX, maxY - minY)
    }

    private companion object {
        val viewPoint = Vec3()
    }
}

/** Orthographic camera (three.js `OrthographicCamera`), used for shadow maps. */
class OrthographicCamera(
    left: Double = -1.0,
    right: Double = 1.0,
    top: Double = 1.0,
    bottom: Double = -1.0,
    near: Double = 0.1,
    far: Double = 2000.0,
) : Camera() {
    var left: Double = left
    var right: Double = right
    var top: Double = top
    var bottom: Double = bottom
    var near: Double = near
    var far: Double = far
    var zoom: Double = 1.0

    init {
        updateProjectionMatrix()
    }

    override fun updateProjectionMatrix() {
        val dx = (right - left) / (2 * zoom)
        val dy = (top - bottom) / (2 * zoom)
        val cx = (right + left) / 2
        val cy = (top + bottom) / 2
        projectionMatrix.makeOrthographic(cx - dx, cx + dx, cy + dy, cy - dy, near, far)
        projectionMatrixInverse.copy(projectionMatrix).invert()
    }
}
