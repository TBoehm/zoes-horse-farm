package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.EPS
import app.zoeshorsefarm.scene.assertMat
import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertVec
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkinnedInstancedTest {
    private class Rig(
        val holder: Group,
        val mesh: SkinnedMesh,
        val skeleton: Skeleton,
        val bones: List<Bone>,
    )

    private fun rig(): Rig {
        val root = Bone()
        val b1 = Bone().also { it.position.set(0.0, 1.0, 0.0) }
        val b2 = Bone().also { it.position.set(0.0, 1.0, 0.0) }
        root.add(b1)
        b1.add(b2)
        val geometry = Geometry()
        geometry.setAttribute(
            "position",
            FloatAttribute(floatArrayOf(0f, 0.5f, 0f, 0f, 1.5f, 0f, 0f, 2.5f, 0f, 0.5f, 2f, 0f), 3),
        )
        geometry.setAttribute(
            "skinIndex",
            UShortAttribute(intArrayOf(0, 1, 0, 0, 1, 2, 0, 0, 2, 1, 0, 0, 2, 0, 0, 0), 4),
        )
        geometry.setAttribute(
            "skinWeight",
            FloatAttribute(floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0.75f, 0.25f, 0f, 0f, 1f, 0f, 0f, 0f), 4),
        )
        val holder = Group()
        holder.position.set(3.0, 0.0, -1.0)
        holder.rotation.y = 0.4
        val mesh = SkinnedMesh(geometry, BasicMaterial())
        holder.add(root, mesh)
        holder.updateMatrixWorld(true)
        val skeleton = Skeleton(listOf(root, b1, b2))
        mesh.bind(skeleton, Mat4().copy(mesh.matrixWorld))
        return Rig(holder, mesh, skeleton, listOf(root, b1, b2))
    }

    private fun pose(rig: Rig) {
        rig.bones[1].rotation.z = 0.6
        rig.bones[2].rotation.x = -0.4
        rig.bones[2].position.y = 1.2
        rig.bones[0].position.x = 0.3
        rig.holder.position.set(5.0, 1.0, 2.0)
        rig.holder.updateMatrixWorld(true)
    }

    @Test
    fun `bind pose inverses come from the world matrices at construction`() {
        val r = rig()
        assertMat(
            doubleArrayOf(
                0.9210609940028851,
                0.0,
                0.38941834230865047,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                -0.38941834230865047,
                0.0,
                0.9210609940028851,
                0.0,
                -3.1526013243173057,
                -1.0,
                -0.2471940329230664,
                1.0,
            ),
            r.skeleton.boneInverses[1],
        )
    }

    @Test
    fun `skeleton update computes bone matrices as float data`() {
        val r = rig()
        pose(r)
        r.skeleton.update()
        val expected =
            doubleArrayOf(
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                2.276318311691284,
                1.0,
                2.883174419403076,
                1.0,
                0.8518229126930237,
                0.5200701355934143,
                0.06264828145503998,
                0.0,
                -0.5200701355934143,
                0.8253356218338013,
                0.21988213062286377,
                0.0,
                0.06264828145503998,
                -0.21988213062286377,
                0.9735127091407776,
                0.0,
                3.303568124771118,
                -0.6054282188415527,
                2.4488601684570312,
                1.0,
                0.7609851360321045,
                0.6452295184135437,
                0.06767898052930832,
                0.0,
                -0.6306629776954651,
                0.7601844668388367,
                -0.15615318715572357,
                0.0,
                -0.15220315754413605,
                0.07614763081073761,
                0.9854114651679993,
                0.0,
                3.4784014225006104,
                -0.38950711488723755,
                3.2417140007019043,
                1.0,
            )
        assertEquals(48, r.skeleton.boneMatrices.size)
        for (i in expected.indices) assertNear(expected[i], r.skeleton.boneMatrices[i].toDouble(), 1e-6, "[$i]")
        assertEquals(1, r.skeleton.version)
    }

    @Test
    fun `skinned vertices follow the bones`() {
        val r = rig()
        pose(r)
        val expected =
            listOf(
                doubleArrayOf(0.2999999999999998, 0.5, 0.0),
                doubleArrayOf(-0.049928562935409815, 1.5114891622095543, 0.09735458557716292),
                doubleArrayOf(-0.6149454627542248, 2.3373720747053492, -0.14603187836574438),
                doubleArrayOf(0.03509683938079711, 2.2727239745891312, 0.0),
            )
        for (i in expected.indices) {
            val v = r.mesh.getVertexPosition(i, Vec3())
            assertNear(expected[i], doubleArrayOf(v.x, v.y, v.z), 1e-6, "vertex $i")
        }
    }

    @Test
    fun `bounding sphere of the skinned pose and the bind matrix inverse`() {
        val r = rig()
        pose(r)
        r.mesh.computeBoundingSphere()
        val sphere = assertNotNull(r.mesh.boundingSphere)
        assertNear(
            doubleArrayOf(-0.1154992022652927, 1.43859736412857, -0.014613985755481285),
            doubleArrayOf(sphere.center.x, sphere.center.y, sphere.center.z),
            1e-6,
        )
        assertNear(1.0365872900951825, sphere.radius, 1e-6)
        assertMat(
            doubleArrayOf(
                0.9210609940028851,
                0.0,
                0.38941834230865047,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                -0.38941834230865047,
                0.0,
                0.9210609940028851,
                0.0,
                -3.826468285397125,
                -1.0,
                -3.7892136995490224,
                1.0,
            ),
            r.mesh.bindMatrixInverse,
        )
    }

    @Test
    fun `a skeleton can be reset to its bind pose`() {
        val r = rig()
        val rest = r.bones[2].matrixWorld.clone()
        pose(r)
        r.skeleton.pose()
        assertMat(rest.e, r.bones[2].matrixWorld, 1e-9)
    }

    private fun instanced(): InstancedMesh {
        val g = BoxGeometry(1.0, 2.0, 1.0)
        g.translate(0.0, 1.0, 0.0)
        val mesh = InstancedMesh(g, BasicMaterial(), 4)
        val m = Mat4()
        mesh.setMatrixAt(0, m.makeTranslation(5.0, 0.0, 0.0))
        mesh.setMatrixAt(
            1,
            m.compose(Vec3(-3.0, 0.0, 4.0), Quat().setFromEuler(Euler(0.0, 0.5, 0.0)), Vec3(2.0, 2.0, 2.0)),
        )
        mesh.setMatrixAt(2, m.makeTranslation(0.0, 7.0, -2.0))
        mesh.setColorAt(1, Color(0x336699))
        mesh.count = 3
        return mesh
    }

    @Test
    fun `instanced mesh bounds cover the first count instances`() {
        val mesh = instanced()
        mesh.computeBoundingSphere()
        val sphere = assertNotNull(mesh.boundingSphere)
        assertNear(
            doubleArrayOf(0.3784208202324345, 2.6584311326667587, 1.5479187179808558),
            doubleArrayOf(sphere.center.x, sphere.center.y, sphere.center.z),
            1e-6,
        )
        assertNear(7.648395508766573, sphere.radius, 1e-6)
    }

    @Test
    fun `instance colours default to white and store the linear working colour`() {
        val mesh = instanced()
        val colors = assertNotNull(mesh.instanceColor).array
        assertEquals(12, colors.size)
        val expected =
            doubleArrayOf(
                1.0,
                1.0,
                1.0,
                0.03310476616024971,
                0.13286831974983215,
                0.31854677200317383,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
            )
        for (i in expected.indices) assertNear(expected[i], colors[i].toDouble(), 1e-6)
        val c = mesh.getColorAt(1, Color())
        assertNear(0.03310476616024971, c.r, 1e-6)
    }

    @Test
    fun `instance matrices are stored column-major and start as identity`() {
        val mesh = instanced()
        val expected =
            doubleArrayOf(
                1.7551651000976562,
                0.0,
                -0.9588510990142822,
                0.0,
                0.0,
                2.0,
                0.0,
                0.0,
                0.9588510990142822,
                0.0,
                1.7551651000976562,
                0.0,
                -3.0,
                0.0,
                4.0,
                1.0,
            )
        for (i in 0 until 16) assertNear(expected[i], mesh.instanceMatrix.array[16 + i].toDouble(), 1e-6)
        val identity = mesh.getMatrixAt(3, Mat4())
        assertTrue(identity.equals(Mat4()))
        assertEquals(4, mesh.capacity)
    }

    @Test
    fun `sphere expand and union follow three js`() {
        val s = Sphere().makeEmpty()
        s.expandByPoint(Vec3(1.0, 0.0, 0.0))
        s.expandByPoint(Vec3(-1.0, 2.0, 0.0))
        s.expandByPoint(Vec3(0.0, 0.0, 3.0))
        assertVec(0.0, 0.7236067977499789, 0.829179606750063, s.center, EPS)
        assertNear(2.2882456112707374, s.radius)
        val u = Sphere(Vec3(0.0, 0.0, 0.0), 1.0).union(Sphere(Vec3(4.0, 1.0, 0.0), 2.0))
        assertVec(2.485071250072666, 0.6212678125181665, 0.0, u.center)
        assertNear(3.5615528128088303, u.radius)
    }
}
