package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.GpuTracker
import app.zoeshorsefarm.scene.render.SceneStats
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The world of the web app and this one are the same: the expected numbers below were computed
// with the web app's world (three.js r186, node) for the course of WorldTestSupport.kt, the random
// numbers are drawn in the same order, so positions, instance matrices and counts agree. Sums
// weigh every number of a buffer by its place, so a single moved vertex shows.

private fun mesh(
    world: World,
    name: String,
): Mesh = checkNotNull(world.scene.getObjectByName(name) as? Mesh) { "no mesh $name" }

/** Sum of the positions, each weighted by (index mod 7) + 1. */
private fun positionSum(mesh: Mesh): Double {
    val a = mesh.geometry.position.array
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * ((i % 7) + 1)
    return s
}

/** Sum of the instance matrices, each weighted by (index mod 5) + 1. */
private fun instanceSum(mesh: Mesh): Double {
    if (mesh !is InstancedMesh) return 0.0
    val a = mesh.instanceMatrix.array
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * ((i % 5) + 1)
    return s
}

private fun assertSum(
    expected: Double,
    actual: Double,
    message: String,
) = assertTrue(
    abs(expected - actual) <= 1e-3 * maxOf(1.0, abs(expected)),
    "$message: expected $expected but was $actual",
)

private class Sums(
    val pos: Double,
    val inst: Double,
)

// name to the sums of its positions and of its instance matrices (the world after showing high)
private val SUMS =
    mapOf(
        "terrain" to Sums(50549.167, 0.0),
        "trees-deciduous" to Sums(19149.894, 2326.649),
        "trees-conifer" to Sums(1776.714, 1260.739),
        "forest" to Sums(1002.996, 46199.273),
        "bushes" to Sums(1650.031, 360.931),
        "grass-tufts" to Sums(6.036, 115041.382),
        "flowers" to Sums(68.241, 128729.768),
        "birds" to Sums(-3.800, 3457.715),
        "butterflies" to Sums(0.184, 127.890),
        "buildings" to Sums(-250023.296, 0.0),
        "arena-ground" to Sums(-2761.692, 0.0),
        "fence-posts" to Sums(43.500, -2769.033),
        "fence-boards" to Sums(43.500, -9115.025),
        "bunting" to Sums(14718.152, 0.0),
        "decor-props" to Sums(-89393.061, 0.0),
        "obstacle-static" to Sums(81133.878, 0.0),
        "poles-white" to Sums(4.906, 1658.202),
        "number-boards" to Sums(257.716, 0.0),
        "planters" to Sums(434.069, 1552.085),
    )

class WorldFingerprintTest {
    private val world = buildWorld()

    @Test
    fun `draws the same calls and triangles as the web app on low and medium`() {
        showLevel(world, GraphicsLevel.LOW)
        val low = SceneStats.of(world.scene)
        assertEquals(17, low.calls)
        assertEquals(23924, low.triangles)
        assertEquals(649, low.instances)
        showLevel(world, GraphicsLevel.MEDIUM)
        val medium = SceneStats.of(world.scene)
        assertEquals(19, medium.calls)
        assertEquals(52078, medium.triangles)
        assertEquals(978, medium.instances)
    }

    @Test
    fun `holds the same shader programs and draws the same calls on high as the web app`() {
        val backend = FakeRenderBackend()
        val own = buildWorld(backend)
        // programs of the visible objects per level, counted by a fresh tracker each time
        val programs = ArrayList<Int>()
        for (level in LEVELS) {
            showLevel(own, level)
            val tracker = GpuTracker(own.scene) { backend.shadowsEnabled }
            tracker.compile(own.compileRoot)
            programs.add(tracker.snapshot().programs)
        }
        assertEquals(listOf(10, 12, 20), programs)
        val high = SceneStats.of(own.scene)
        assertEquals(26, high.calls)
        assertEquals(187760, high.triangles)
        assertEquals(16050, high.instances)
    }

    @Test
    fun `builds the scenery of the web app vertex by vertex and instance by instance`() {
        // the animals move with the frames: the same three frames as the numbers were taken after
        for (level in LEVELS) showLevel(world, level)
        for ((name, sums) in SUMS) {
            val m = mesh(world, name)
            assertSum(sums.pos, positionSum(m), "$name positions")
            assertSum(sums.inst, instanceSum(m), "$name instances")
        }
    }

    @Test
    fun `places the take off aid like the web app`() {
        world.setAid("b", 1, Zone(far = 4.0, near = 1.5, lastPoint = 1.0, reach = 5.0, center = 2.0))
        val aid = mesh(world, "aid-marker")
        assertTrue(aid.visible)
        assertEquals(10.0, aid.position.x, 1e-4)
        assertEquals(0.018, aid.position.y, 1e-4)
        assertEquals(-23.35, aid.position.z, 1e-4)
        assertEquals(0.0, aid.rotation.y, 1e-4)
        assertEquals(3.5, aid.scale.x, 1e-4)
        assertEquals(2.5, aid.scale.z, 1e-4)
    }

    @Test
    fun `lets the poles fall and rise like the web app`() {
        fun poleSum(name: String): Double = instanceSum(mesh(world, name))
        world.setAid("b", 1, Zone(far = 4.0, near = 1.5, lastPoint = 1.0, reach = 5.0, center = 2.0))
        val down =
            mapOf(
                "a" to booleanArrayOf(false),
                "b" to booleanArrayOf(false, true, true),
                "c" to booleanArrayOf(false),
            )
        world.syncRails(down, 0.0)
        repeat(40) { world.syncRails(null, 0.02) }
        assertSum(1650.0245, poleSum("poles-white"), "fallen white poles")
        assertSum(1650.0245, poleSum("poles-colored"), "fallen coloured poles")
        val up =
            mapOf("a" to booleanArrayOf(true), "b" to booleanArrayOf(true, true, true), "c" to booleanArrayOf(true))
        world.syncRails(up, 0.1)
        repeat(10) { world.syncRails(null, 0.02) }
        assertSum(1661.2972, poleSum("poles-white"), "rising poles")
    }

    @Test
    fun `shows the highlight ring and badge like the web app`() {
        world.highlight("d2", 3)
        world.update(0.5, newCamera())
        val h = checkNotNull(world.scene.getObjectByName("highlight"))
        assertTrue(h.visible)
        val ring = h.children[0] as Mesh
        assertEquals(168, ring.geometry.index?.size)
        val badge = h.children[1]
        assertEquals(2.4118, badge.position.y, 1e-4)
        assertEquals(1.1, badge.scale.x, 1e-4)
        assertEquals(1.375, badge.scale.y, 1e-4)
        assertEquals(-10.0, h.position.x, 1e-4)
        assertEquals(14.0, h.position.z, 1e-4)
        assertEquals(0.9319, (ring.material as BasicMaterial).opacity, 1e-4)
    }

    @Test
    fun `builds the clouds of the sky like the web app`() {
        val clouds = checkNotNull(world.scene.getObjectByName("sky")).children[1] as Mesh
        assertSum(14140.786, positionSum(clouds), "cloud positions")
        val uv = clouds.geometry.uv.array
        var uvSum = 0.0
        for (i in uv.indices) uvSum += uv[i].toDouble() * ((i % 3) + 1)
        assertSum(76.0, uvSum, "cloud uvs")
        assertEquals(54, clouds.geometry.index?.size)
    }

    @Test
    fun `follows the shadow focus on the texel grid like the web app`() {
        // the texel grid depends on the shadow map of the level
        showLevel(world, GraphicsLevel.HIGH)
        val sun = world.scene.children.first { it is DirectionalLight } as DirectionalLight
        world.setShadowFocus(7.3, -4.1)
        assertEquals(-39.6318, sun.position.x, 1e-4)
        assertEquals(66.7835, sun.position.y, 1e-4)
        assertEquals(-42.0127, sun.position.z, 1e-4)
        assertEquals(7.2998, sun.target.position.x, 1e-4)
        assertEquals(-0.0038, sun.target.position.y, 1e-4)
        assertEquals(-4.1064, sun.target.position.z, 1e-4)
    }
}
