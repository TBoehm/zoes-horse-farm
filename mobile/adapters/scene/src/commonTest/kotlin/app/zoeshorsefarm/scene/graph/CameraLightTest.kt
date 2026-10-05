package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.assertMat
import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertQuat
import app.zoeshorsefarm.scene.assertVec
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.test.Test

class CameraLightTest {
    private fun camera(): PerspectiveCamera {
        val cam = PerspectiveCamera(60.0, 1.5, 0.5, 300.0)
        cam.position.set(0.0, 3.0, 12.0)
        cam.lookAt(0.0, 1.0, 0.0)
        cam.updateMatrixWorld(true)
        return cam
    }

    @Test
    fun `perspective projection`() {
        assertMat(
            doubleArrayOf(
                1.1547005383792517,
                0.0,
                0.0,
                0.0,
                0.0,
                1.7320508075688774,
                0.0,
                0.0,
                0.0,
                0.0,
                -1.003338898163606,
                -1.0,
                0.0,
                0.0,
                -1.001669449081803,
                0.0,
            ),
            camera().projectionMatrix,
        )
    }

    @Test
    fun `camera world inverse and look direction`() {
        val cam = camera()
        assertMat(
            doubleArrayOf(
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.9863939238321437,
                0.1643989873053573,
                0.0,
                0.0,
                -0.1643989873053573,
                0.9863939238321437,
                0.0,
                0.0,
                -0.9863939238321437,
                -12.329924047901796,
                1.0,
            ),
            cam.matrixWorldInverse,
        )
        assertVec(0.0, -0.1643989873053573, -0.9863939238321437, cam.getWorldDirection(Vec3()))
    }

    @Test
    fun `view size at a distance and effective fov`() {
        val cam = camera()
        val size = cam.getViewSize(10.0, Vec2())
        assertNear(17.320508075688767, size.x)
        assertNear(11.547005383792515, size.y)
        assertNear(59.99999999999999, cam.getEffectiveFOV(), 1e-9)
    }

    @Test
    fun `updateProjectionMatrix follows fov aspect and zoom`() {
        val cam = camera()
        cam.fov = 40.0
        cam.aspect = 2.0
        cam.zoom = 1.5
        cam.updateProjectionMatrix()
        assertMat(
            doubleArrayOf(
                2.060608064590967,
                0.0,
                0.0,
                0.0,
                0.0,
                4.121216129181934,
                0.0,
                0.0,
                0.0,
                0.0,
                -1.003338898163606,
                -1.0,
                0.0,
                0.0,
                -1.001669449081803,
                0.0,
            ),
            cam.projectionMatrix,
        )
        assertMat(
            doubleArrayOf(
                0.4852936456882697,
                0.0,
                0.0,
                0.0,
                0.0,
                0.24264682284413486,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                -0.9983333333333333,
                0.0,
                0.0,
                -1.0,
                1.0016666666666665,
            ),
            cam.projectionMatrixInverse,
        )
    }

    @Test
    fun `orthographic shadow camera`() {
        val ortho = OrthographicCamera(-24.0, 24.0, 24.0, -24.0, 10.0, 150.0)
        ortho.position.set(10.0, 50.0, 20.0)
        ortho.lookAt(0.0, 0.0, 0.0)
        ortho.updateMatrixWorld(true)
        assertMat(
            doubleArrayOf(
                0.041666666666666664,
                0.0,
                0.0,
                0.0,
                0.0,
                0.041666666666666664,
                0.0,
                0.0,
                0.0,
                0.0,
                -0.014285714285714285,
                0.0,
                0.0,
                0.0,
                -1.1428571428571428,
                1.0,
            ),
            ortho.projectionMatrix,
        )
        assertMat(
            doubleArrayOf(
                0.8944271909999161,
                -0.4082482904638631,
                0.18257418583505533,
                0.0,
                -2.775557561562892e-17,
                0.40824829046386296,
                0.9128709291752771,
                0.0,
                -0.447213595499958,
                -0.8164965809277263,
                0.3651483716701107,
                0.0,
                1.6316879466126223e-15,
                7.149698361352148e-15,
                -54.77225575051663,
                1.0000000000000002,
            ),
            ortho.matrixWorldInverse,
            1e-12,
        )
        ortho.zoom = 2.0
        ortho.updateProjectionMatrix()
        assertNear(0.08333333333333333, ortho.projectionMatrix.e[0])
    }

    @Test
    fun `a directional light looks at its target like a camera`() {
        val sun = DirectionalLight(0xfff0dc, 2.7)
        sun.position.set(30.0, 60.0, -20.0)
        sun.lookAt(0.0, 0.0, 0.0)
        sun.updateMatrixWorld(true)
        assertQuat(-0.23234421439501343, 0.7673808108718296, 0.43413913578288205, 0.41068974655395224, sun.quaternion)
        assertNear(0.8713671191959567, sun.color.g)
        assertNear(0.7156935005005721, sun.color.b)
    }

    @Test
    fun `shadow settings have the three js defaults`() {
        val sun = DirectionalLight()
        assertNear(512.0, sun.shadow.mapSize.x)
        assertNear(0.0, sun.shadow.bias)
        assertNear(5.0, sun.shadow.camera.right)
        assertVec(0.0, 1.0, 0.0, sun.position)
    }
}
