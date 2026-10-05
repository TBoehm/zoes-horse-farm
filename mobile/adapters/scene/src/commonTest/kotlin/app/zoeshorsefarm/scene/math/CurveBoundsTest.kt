package app.zoeshorsefarm.scene.math

import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurveBoundsTest {
    private val points =
        listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 2.0, 0.0),
            Vec3(3.0, 2.0, 1.0),
            Vec3(4.0, 0.0, 3.0),
            Vec3(6.0, -1.0, 3.0),
        )

    @Test
    fun `centripetal Catmull-Rom curve length and points`() {
        val curve = CatmullRomCurve3(points, false, CatmullRomType.CENTRIPETAL)
        assertNear(9.90017118667773, curve.getLength())
        assertVec(0.35200000000000004, 0.896, -0.04800000000000001, curve.getPoint(0.1))
        assertVec(3.0, 2.0, 1.0, curve.getPoint(0.5))
        assertVec(4.124339619539194, -0.10279809906623004, 3.054171052395511, curve.getPoint(0.77))
        assertVec(6.0, -1.0, 3.0, curve.getPoint(1.0))
    }

    @Test
    fun `points and tangents by arc length`() {
        val curve = CatmullRomCurve3(points, false, CatmullRomType.CENTRIPETAL)
        assertVec(0.3606536502865451, 0.9198475563459046, -0.04963506394320358, curve.getPointAt(0.1))
        assertVec(3.1994498207315436, 1.8258712783651914, 1.248297753421126, curve.getPointAt(0.5))
        assertVec(0.44707055404696877, 0.894498692962965, -0.00008939621725682399, curve.getTangentAt(0.0))
        assertVec(0.9199234896419044, 0.11742934742467198, 0.37410041642382114, curve.getTangentAt(0.3))
    }

    @Test
    fun `closed uniform Catmull-Rom curve`() {
        val curve = CatmullRomCurve3(points, true, CatmullRomType.CATMULLROM, 0.5)
        assertVec(0.0, 1.0625, -0.25, curve.getPoint(0.1))
        assertVec(3.5, 1.0625, 2.0625, curve.getPoint(0.5))
        assertVec(3.0625, -0.6875, 1.5, curve.getPoint(0.9))
    }

    @Test
    fun `Frenet frames of an open curve`() {
        val frames = CatmullRomCurve3(points).computeFrenetFrames(4, false)
        assertVec(-0.00003996641653841097, -0.00007996479981158901, -0.9999999960041582, frames.normals[0])
        assertVec(0.8931707168475171, 0.13568453652721196, -0.4287607457702818, frames.normals[2])
        assertVec(0.3138272865071821, 0.6275772278969054, 0.7125021103609269, frames.binormals[4])
    }

    @Test
    fun `Box3 from points and transform`() {
        val box = Box3().setFromPoints(listOf(Vec3(-1.0, 0.0, 2.0), Vec3(3.0, 4.0, -5.0)))
        assertVec(-1.0, 0.0, -5.0, box.min)
        assertVec(3.0, 4.0, 2.0, box.max)
        assertVec(1.0, 2.0, -1.5, box.getCenter(Vec3()))
        assertVec(4.0, 4.0, 7.0, box.getSize(Vec3()))
        val m = Mat4().compose(Vec3(1.0, 2.0, 3.0), Quat().setFromEuler(Euler(0.3, 0.5, 0.7)), Vec3(1.0, 2.0, 0.5))
        val moved = box.clone().applyMatrix4(m)
        assertVec(-5.392609679718613, 1.016849165585669, 0.42424909251098697, moved.min)
        assertVec(3.493062037081076, 9.935052256152057, 8.167004427552417, moved.max)
    }

    @Test
    fun `an empty Box3 has a zero centre`() {
        val box = Box3()
        assertTrue(box.isEmpty())
        assertVec(0.0, 0.0, 0.0, box.getCenter(Vec3(1.0, 1.0, 1.0)))
    }

    @Test
    fun `Sphere under a similarity transform`() {
        val m = Mat4().compose(Vec3(1.0, 2.0, 3.0), Quat().setFromEuler(Euler(0.3, 0.5, 0.7)), Vec3(1.0, 2.0, 0.5))
        val sphere = Sphere(Vec3(1.0, 2.0, 3.0), 2.0).applyMatrix4(m)
        assertVec(0.12893364054068757, 4.892428105751344, 6.1819967081186595, sphere.center)
        assertNear(3.9999999999999996, sphere.radius)
    }

    @Test
    fun `frustum culls spheres outside the view`() {
        val camera = PerspectiveLike()
        val frustum = Frustum().setFromProjectionMatrix(camera.viewProjection)
        assertTrue(frustum.intersectsSphere(Sphere(Vec3(0.0, 1.0, 0.0), 1.0)))
        assertFalse(frustum.intersectsSphere(Sphere(Vec3(0.0, 1.0, 50.0), 1.0)))
        assertFalse(frustum.intersectsSphere(Sphere(Vec3(100.0, 1.0, 0.0), 1.0)))
        assertEquals(6, frustum.planes.size)
        assertVec(-0.9136505803916737, -0.17625585346184872, -0.36630109346054796, frustum.planes[0].normal)
        assertNear(7.108986089627896, frustum.planes[0].constant)
    }

    /** Projection * view of a camera at (3, 4, 10) looking at (0, 1, 0), fov 50, aspect 16:9, near 0.1, far 500. */
    private class PerspectiveLike {
        val viewProjection: Mat4

        init {
            val aspect = 16.0 / 9.0
            val near = 0.1
            val top = near * kotlin.math.tan(MathUtils.DEG2RAD * 0.5 * 50.0)
            val height = 2 * top
            val width = aspect * height
            val left = -0.5 * width
            val projection = Mat4().makePerspective(left, left + width, top, top - height, near, 500.0)
            val rotation = Mat4().lookAt(Vec3(3.0, 4.0, 10.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val world =
                Mat4().compose(
                    Vec3(3.0, 4.0, 10.0),
                    Quat().setFromRotationMatrix(rotation),
                    Vec3(1.0, 1.0, 1.0),
                )
            viewProjection = Mat4().multiplyMatrices(projection, world.invert())
        }
    }
}
