package app.zoeshorsefarm.scene.math

import app.zoeshorsefarm.scene.EPS
import app.zoeshorsefarm.scene.assertMat
import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertQuat
import app.zoeshorsefarm.scene.assertVec
import app.zoeshorsefarm.scene.toFixed
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorMatrixTest {
    private val composed: Mat4
        get() =
            Mat4().compose(
                Vec3(1.0, 2.0, 3.0),
                Quat().setFromEuler(Euler(0.3, 0.5, 0.7, EulerOrder.XYZ)),
                Vec3(1.0, 2.0, 0.5),
            )

    @Test
    fun `applyQuaternion rotates a vector`() {
        val q = Quat(0.1, 0.2, 0.3, 0.9).normalize()
        assertVec(1.9999999999999996, -1.0000000000000002, -0.4999999999999997, Vec3(1.0, -2.0, 0.5).applyQuaternion(q))
    }

    @Test
    fun `applyAxisAngle rotates about Y`() {
        assertVec(
            2.6974952489975617,
            2.0,
            1.6503088746157744,
            Vec3(1.0, 2.0, 3.0).applyAxisAngle(Vec3(0.0, 1.0, 0.0), 0.7),
        )
    }

    @Test
    fun `cross product and normalize`() {
        val v = Vec3(1.0, 2.0, 3.0).cross(Vec3(-2.0, 0.5, 4.0)).normalize()
        assertVec(0.5099019513592785, -0.7844645405527362, 0.3530090432487313, v)
    }

    @Test
    fun `angleTo between two vectors`() {
        assertNear(0.8588543554571453, Vec3(1.0, 2.0, 3.0).angleTo(Vec3(-2.0, 0.5, 4.0)))
    }

    @Test
    fun `normalize of a zero vector stays zero`() {
        assertVec(0.0, 0.0, 0.0, Vec3().normalize())
    }

    @Test
    fun `lerp and distance`() {
        val v = Vec3(0.0, 0.0, 0.0).lerp(Vec3(2.0, 4.0, 6.0), 0.25)
        assertVec(0.5, 1.0, 1.5, v)
        assertNear(sqrt(14.0), Vec3(1.0, 2.0, 3.0).distanceTo(Vec3()))
    }

    @Test
    fun `compose builds translation rotation scale`() {
        assertMat(
            doubleArrayOf(
                0.6712121661589576,
                0.7238074543621003,
                -0.159928099501168,
                0.0,
                -1.1307084167622872,
                1.278817860733795,
                1.0421724211142611,
                0.0,
                0.23971276930210142,
                -0.12967169002611534,
                0.4191933217971018,
                0.0,
                1.0,
                2.0,
                3.0,
                1.0,
            ),
            composed,
        )
    }

    @Test
    fun `applyMatrix4 transforms a point`() {
        assertVec(
            0.12893364054068757,
            4.892428105751344,
            6.1819967081186595,
            Vec3(1.0, 2.0, 3.0).applyMatrix4(composed),
        )
    }

    @Test
    fun `invert gives the inverse`() {
        assertMat(
            doubleArrayOf(
                0.671212166158958,
                -0.2826771041905719,
                0.9588510772084056,
                0.0,
                0.7238074543621006,
                0.31970446518344886,
                -0.5186867601044616,
                0.0,
                -0.1599280995011682,
                0.26054310527856533,
                1.6767732871884073,
                0.0,
                -1.6390427763796545,
                -1.1383611420120217,
                -4.951797418564704,
                1.0,
            ),
            composed.invert(),
        )
    }

    @Test
    fun `a singular matrix inverts to zeros`() {
        val m = Mat4().makeScale(0.0, 1.0, 1.0).invert()
        assertTrue(m.e.all { it == 0.0 })
    }

    @Test
    fun `decompose splits a composed matrix`() {
        val pos = Vec3()
        val q = Quat()
        val scale = Vec3()
        composed.decompose(pos, q, scale)
        assertVec(1.0, 2.0, 3.0, pos)
        assertQuat(0.21989576632910454, 0.18014585799688554, 0.3632373697282358, 0.887272187679753, q)
        assertVec(1.0, 2.0, 0.5, scale)
    }

    @Test
    fun `decompose of a mirrored matrix gives a negative x scale`() {
        val m = Mat4().makeScale(-1.0, 1.0, 1.0).multiply(Mat4().makeRotationY(0.4))
        val q = Quat()
        val scale = Vec3()
        m.decompose(Vec3(), q, scale)
        assertQuat(0.0, -0.19866933079506124, 0.0, 0.9800665778412415, q)
        assertVec(-1.0, 1.0, 1.0, scale)
    }

    @Test
    fun `lookAt builds the rotation of a view matrix`() {
        val m = Mat4().lookAt(Vec3(1.0, 2.0, 3.0), Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        assertMat(
            doubleArrayOf(
                0.9486832980505138,
                0.0,
                -0.31622776601683794,
                0.0,
                -0.16903085094570333,
                0.8451542547285166,
                -0.50709255283711,
                0.0,
                0.2672612419124244,
                0.5345224838248488,
                0.8017837257372732,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            ),
            m,
        )
    }

    @Test
    fun `makeRotationAxis and extractRotation`() {
        val axis = Vec3(1.0, 1.0, 0.0).normalize()
        assertMat(
            doubleArrayOf(
                0.8108049841353322,
                0.18919501586466778,
                -0.5538957696834953,
                0.0,
                0.18919501586466778,
                0.8108049841353322,
                0.5538957696834953,
                0.0,
                0.5538957696834953,
                -0.5538957696834953,
                0.6216099682706644,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            ),
            Mat4().makeRotationAxis(axis, 0.9),
        )
        assertMat(
            doubleArrayOf(
                0.671212166158958,
                0.7238074543621006,
                -0.15992809950116807,
                0.0,
                -0.5653542083811437,
                0.6394089303668976,
                0.5210862105571307,
                0.0,
                0.47942553860420295,
                -0.25934338005223073,
                0.8383866435942038,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            ),
            Mat4().extractRotation(composed),
        )
    }

    @Test
    fun `determinant and max scale`() {
        assertNear(0.9999999999999996, composed.determinant())
        assertNear(1.9999999999999998, composed.getMaxScaleOnAxis())
    }

    @Test
    fun `orthographic projection`() {
        assertMat(
            doubleArrayOf(
                0.1,
                0.0,
                0.0,
                0.0,
                0.0,
                0.125,
                0.0,
                0.0,
                0.0,
                0.0,
                -0.010256410256410256,
                0.0,
                0.0,
                0.0,
                -1.0512820512820513,
                1.0,
            ),
            Mat4().makeOrthographic(-10.0, 10.0, 8.0, -8.0, 5.0, 200.0),
        )
    }

    @Test
    fun `Mat3 normal matrix of a non-uniform scale`() {
        val n = Mat3().getNormalMatrix(Mat4().makeScale(2.0, 1.0, 4.0))
        assertNear(doubleArrayOf(0.5, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.25), n.e, EPS)
        assertEquals(1.0, Vec3(0.0, 3.0, 0.0).applyNormalMatrix(n).length(), EPS)
    }

    @Test
    fun `toFixed follows JavaScript rounding`() {
        assertEquals("1.500", 1.5.toFixed(3))
        assertEquals("-0.000", (-0.0004).toFixed(3))
        assertEquals("0.125", 0.125.toFixed(3))
        assertEquals("12", 11.5.toFixed(0))
    }
}
