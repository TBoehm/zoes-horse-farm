package app.zoeshorsefarm.scene.math

import app.zoeshorsefarm.scene.assertMat
import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertQuat
import kotlin.test.Test

class RotationTest {
    private class Case(
        val order: EulerOrder,
        val q: DoubleArray,
        val m: DoubleArray,
    )

    private val cases =
        listOf(
            Case(
                EulerOrder.XYZ,
                doubleArrayOf(-0.043486126630262606, -0.4210157594090833, 0.4058743645066381, 0.8100127698723166),
                doubleArrayOf(
                    0.31602346113105123,
                    0.6941435256823754,
                    0.6467564748558707,
                    0.0,
                    -0.6209101471743771,
                    0.666749914054059,
                    -0.4122076433604772,
                    0.0,
                    -0.7173560908995228,
                    -0.2713103718292879,
                    0.6417093742397794,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
            Case(
                EulerOrder.YXZ,
                doubleArrayOf(-0.043486126630262606, -0.4210157594090833, 0.5377863047276763, 0.7291368716278451),
                doubleArrayOf(
                    0.06706324155308782,
                    0.8208563369208728,
                    0.5671837407483036,
                    0.0,
                    -0.7476229584128745,
                    0.4177896944760956,
                    -0.5162476956302586,
                    0.0,
                    -0.6607287141379384,
                    -0.3894183423086505,
                    0.6417093742397794,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
            Case(
                EulerOrder.ZXY,
                doubleArrayOf(0.3554872051119209, -0.2297262716404578, 0.4058743645066381, 0.8100127698723166),
                doubleArrayOf(
                    0.5649836807090146,
                    0.49419733593587967,
                    0.6607287141379384,
                    0.0,
                    -0.8208563369208728,
                    0.4177896944760956,
                    0.3894183423086505,
                    0.0,
                    -0.08359614027777129,
                    -0.762378360358851,
                    0.6417093742397794,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
            Case(
                EulerOrder.ZYX,
                doubleArrayOf(0.3554872051119209, -0.2297262716404578, 0.5377863047276763, 0.7291368716278451),
                doubleArrayOf(
                    0.31602346113105123,
                    0.6209101471743771,
                    0.7173560908995228,
                    0.0,
                    -0.9475691481593702,
                    0.16882947489813216,
                    0.2713103718292879,
                    0.0,
                    0.04734851076091495,
                    -0.7654849427268405,
                    0.6417093742397794,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
            Case(
                EulerOrder.YZX,
                doubleArrayOf(-0.043486126630262606, -0.2297262716404578, 0.5377863047276763, 0.8100127698723166),
                doubleArrayOf(
                    0.31602346113105123,
                    0.8912073600614354,
                    0.32538994051303743,
                    0.0,
                    -0.8512477371060152,
                    0.4177896944760956,
                    -0.3175359212143711,
                    0.0,
                    -0.41893491390267235,
                    -0.1766386496831817,
                    0.8906695938177428,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
            Case(
                EulerOrder.XZY,
                doubleArrayOf(0.3554872051119209, -0.4210157594090833, 0.4058743645066381, 0.7291368716278451),
                doubleArrayOf(
                    0.31602346113105123,
                    0.29254449757980366,
                    0.9025225143732045,
                    0.0,
                    -0.8912073600614354,
                    0.4177896944760956,
                    0.1766386496831817,
                    0.0,
                    -0.32538994051303743,
                    -0.8601566648729468,
                    0.3927491546618159,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                ),
            ),
        )

    @Test
    fun `quaternion from Euler matches three js for every order`() {
        for (c in cases) {
            val q = Quat().setFromEuler(Euler(0.4, -0.8, 1.1, c.order))
            assertQuat(c.q[0], c.q[1], c.q[2], c.q[3], q)
        }
    }

    @Test
    fun `rotation matrix from Euler matches three js for every order`() {
        for (c in cases) assertMat(c.m, Mat4().makeRotationFromEuler(Euler(0.4, -0.8, 1.1, c.order)))
    }

    @Test
    fun `Euler from quaternion round trips for every order`() {
        for (c in cases) {
            val back = Euler().setFromQuaternion(Quat(c.q[0], c.q[1], c.q[2], c.q[3]), c.order)
            assertNear(doubleArrayOf(0.4, -0.8, 1.1), doubleArrayOf(back.x, back.y, back.z), 1e-12, c.order.name)
        }
    }

    @Test
    fun `slerp between two rotations`() {
        val a = Quat().setFromEuler(Euler(0.1, 0.2, 0.3))
        val b = Quat().setFromEuler(Euler(1.1, -0.2, 0.9))
        assertQuat(0.20258513144280607, -0.05051446883084923, 0.22240546206419418, 0.9523357409028868, a.slerp(b, 0.35))
    }

    @Test
    fun `setFromUnitVectors including the opposite case`() {
        val q = Quat().setFromUnitVectors(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0).normalize())
        assertQuat(0.0, 0.0, -0.3826834323650898, 0.9238795325112867, q)
        val opposite = Quat().setFromUnitVectors(Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0))
        assertQuat(0.0, 0.0, 1.0, 0.0, opposite)
    }

    @Test
    fun `changing the Euler fires the callback and the quaternion follows`() {
        val euler = Euler()
        val quat = Quat()
        euler.onChange = { quat.setFromEuler(euler, false) }
        quat.onChange = { euler.setFromQuaternion(quat, euler.order, false) }
        euler.set(0.3, 0.5, 0.7)
        assertQuat(0.21989576632910457, 0.18014585799688554, 0.36323736972823584, 0.8872721876797527, quat)
        quat.set(0.1, 0.2, 0.3, 0.9).normalize()
        assertNear(
            doubleArrayOf(0.0704713445787956, 0.4579444204670944, 0.6270706625890181),
            doubleArrayOf(euler.x, euler.y, euler.z),
        )
    }
}
