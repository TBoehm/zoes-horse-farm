package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.assertMat
import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.assertQuat
import app.zoeshorsefarm.scene.assertVec
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeTest {
    private fun hierarchy(): Triple<Group, Group, Mesh> {
        val root = Group()
        root.position.set(1.0, 2.0, 3.0)
        root.rotation.set(0.3, 0.5, 0.7)
        root.scale.set(2.0, 2.0, 2.0)
        val child = Group()
        child.position.set(0.5, -1.0, 0.25)
        child.rotation.y = 1.1
        val leaf = Mesh(BoxGeometry(), BasicMaterial())
        leaf.position.set(1.0, 1.0, 1.0)
        leaf.scale.set(1.0, 2.0, 3.0)
        root.add(child)
        child.add(leaf)
        root.updateMatrixWorld(true)
        return Triple(root, child, leaf)
    }

    @Test
    fun `world matrix of a nested node`() {
        val (_, _, leaf) = hierarchy()
        assertMat(
            doubleArrayOf(
                -0.24561666676424032,
                1.1188899660866545,
                -1.6394382259781026,
                0.0,
                -2.2614168335245743,
                2.55763572146759,
                2.0843448422285222,
                0.0,
                4.8939287248012615,
                3.164552275714464,
                1.4265589825995604,
                0.0,
                3.2966178436305724,
                4.767876488994127,
                2.095346657184351,
                1.0,
            ),
            leaf.matrixWorld,
        )
    }

    @Test
    fun `world position quaternion scale and direction`() {
        val (_, _, leaf) = hierarchy()
        assertVec(3.2966178436305724, 4.767876488994127, 2.095346657184351, leaf.getWorldPosition(Vec3()))
        assertQuat(
            -0.0023930011346842478,
            0.6173446025753683,
            0.42460547377785957,
            0.6622613584186453,
            leaf.getWorldQuaternion(Quat()),
        )
        assertVec(2.0, 4.0, 6.0, leaf.getWorldScale(Vec3()), 1e-12)
        assertVec(0.815654787466877, 0.5274253792857441, 0.2377598304332601, leaf.getWorldDirection(Vec3()))
    }

    @Test
    fun `localToWorld and worldToLocal`() {
        val (_, _, leaf) = hierarchy()
        assertVec(3.051001176866332, 5.886766455080782, 0.45590843120624847, leaf.localToWorld(Vec3(1.0, 0.0, 0.0)))
        assertVec(
            -0.3070854410904981,
            -0.5432202094691372,
            -0.32703591778449814,
            leaf.worldToLocal(Vec3(3.0, 2.0, 1.0)),
        )
    }

    @Test
    fun `attach keeps the world transform`() {
        val (_, _, leaf) = hierarchy()
        val other = Group()
        other.position.set(-4.0, 1.0, 2.0)
        other.rotation.z = 0.6
        other.scale.set(0.5, 0.5, 0.5)
        other.updateMatrixWorld(true)
        other.attach(leaf)
        other.updateMatrixWorld(true)
        assertVec(16.299323349852834, -2.0204353753964543, 0.1906933143687024, leaf.position)
        assertQuat(0.18015168323185243, 0.5904790053948575, 0.20992948907875747, 0.7581619383955923, leaf.quaternion)
        assertVec(4.0, 8.0, 12.0, leaf.scale, 1e-12)
        assertMat(
            doubleArrayOf(
                -0.2456166667642397,
                1.118889966086655,
                -1.6394382259781026,
                0.0,
                -2.2614168335245752,
                2.5576357214675896,
                2.084344842228522,
                0.0,
                4.893928724801262,
                3.164552275714465,
                1.4265589825995613,
                0.0,
                3.2966178436305738,
                4.767876488994128,
                2.095346657184351,
                1.0,
            ),
            leaf.matrixWorld,
            1e-12,
        )
        assertSame(other, leaf.parent)
    }

    @Test
    fun `lookAt of a plain node points +Z at the target in the parent's frame`() {
        val parent = Node()
        parent.rotation.y = 0.5
        parent.position.set(1.0, 0.0, 2.0)
        val node = Node()
        parent.add(node)
        node.position.set(0.0, 1.0, 0.0)
        node.lookAt(5.0, 1.0, -3.0)
        node.updateMatrixWorld(true)
        assertQuat(0.0, 0.8324007720605929, 0.0, 0.5541741194542824, node.quaternion)
        assertMat(
            doubleArrayOf(
                -0.7808688094430312,
                0.0,
                -0.6246950475544243,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.6246950475544243,
                0.0,
                -0.7808688094430312,
                0.0,
                1.0,
                1.0,
                2.0,
                1.0,
            ),
            node.matrixWorld,
        )
    }

    @Test
    fun `rotateOnAxis and translateOnAxis follow the local frame and keep Euler in sync`() {
        val node = Node()
        node.rotateY(0.5)
        node.rotateX(0.3)
        node.translateZ(2.0)
        node.translateOnAxis(Vec3(1.0, 1.0, 0.0).normalize(), 1.5)
        assertQuat(0.14479246283091116, 0.2446258794777393, -0.036971585637570345, 0.9580325796404553, node.quaternion)
        assertVec(1.9971165559364195, 0.4222469513408176, 1.4433409070929983, node.position)
        assertVec(
            0.33888849168115803,
            0.4757583501169825,
            -0.16006233978386206,
            Vec3(node.rotation.x, node.rotation.y, node.rotation.z),
        )
    }

    @Test
    fun `setting the Euler angles updates the quaternion and the other way round`() {
        val node = Node()
        node.rotation.set(0.3, 0.5, 0.7)
        assertQuat(0.21989576632910457, 0.18014585799688554, 0.36323736972823584, 0.8872721876797527, node.quaternion)
        node.quaternion.set(0.1, 0.2, 0.3, 0.9).normalize()
        assertVec(
            0.0704713445787956,
            0.4579444204670944,
            0.6270706625890181,
            Vec3(node.rotation.x, node.rotation.y, node.rotation.z),
        )
        node.rotation.x = 0.0
        assertNear(0.0, node.rotation.x)
    }

    @Test
    fun `children are added once and removed from the old parent`() {
        val a = Group()
        val b = Group()
        val node = Node()
        a.add(node)
        b.add(node)
        assertTrue(a.children.isEmpty())
        assertEquals(listOf<Node>(node), b.children)
        assertSame(b, node.parent)
        node.removeFromParent()
        assertNull(node.parent)
        assertTrue(b.children.isEmpty())
    }

    @Test
    fun `traverseVisible skips hidden branches and getObjectByName finds nested nodes`() {
        val root = Group()
        val hidden = Group().also { it.visible = false }
        val inner = Node().also { it.name = "inner" }
        hidden.add(inner)
        val shown = Node().also { it.name = "shown" }
        root.add(hidden, shown)
        val seen = ArrayList<Node>()
        root.traverseVisible { seen.add(it) }
        assertEquals(listOf(root, shown), seen)
        var all = 0
        root.traverse { all++ }
        assertEquals(4, all)
        assertSame(inner, root.getObjectByName("inner"))
        assertNull(root.getObjectByName("missing"))
    }

    @Test
    fun `matrices are only recomputed when needed`() {
        val node = Node()
        node.position.set(1.0, 0.0, 0.0)
        node.updateMatrixWorld()
        assertNear(1.0, node.matrixWorld.e[12])
        node.matrixAutoUpdate = false
        node.position.set(5.0, 0.0, 0.0)
        node.updateMatrixWorld()
        assertNear(1.0, node.matrixWorld.e[12])
        node.updateMatrix()
        node.updateMatrixWorld()
        assertNear(5.0, node.matrixWorld.e[12])
        assertFalse(node.matrixWorldNeedsUpdate)
    }
}
