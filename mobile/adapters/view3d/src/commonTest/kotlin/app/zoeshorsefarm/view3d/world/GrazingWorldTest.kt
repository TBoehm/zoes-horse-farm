package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.SceneStats
import app.zoeshorsefarm.view3d.PADDOCK
import app.zoeshorsefarm.view3d.paddockContains
import app.zoeshorsefarm.view3d.quality.presetFor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The grazing horses of the paddock and the hoof dust in the world.

// the calls of the world on high before the details (see WorldBudgetTest) and the room for the new ones
private const val HIGH_CALLS_BEFORE = 18
private const val HANDFUL_OF_CALLS = 10

class GrazingWorldTest {
    // a camera that looks at the paddock from the meadow, and one that looks away from it
    private val towards =
        PerspectiveCamera(50.0, 1.6, 0.1, 900.0).also {
            it.position.set(PADDOCK.x, 6.0, PADDOCK.z + 22)
            it.lookAt(PADDOCK.x, 0.0, PADDOCK.z)
            it.updateMatrixWorld(true)
        }
    private val away =
        PerspectiveCamera(50.0, 1.6, 0.1, 900.0).also {
            it.position.set(0.0, 6.0, 0.0)
            it.lookAt(60.0, 0.0, 40.0)
            it.updateMatrixWorld(true)
        }

    /** A world at a level, reached the way the budget tests do (no environment light in the test). */
    private fun worldAt(level: GraphicsLevel): World {
        val world = buildWorld()
        showLevel(world, level)
        return world
    }

    private fun horsesOf(world: World): List<Node> =
        world.scene.getObjectByName("paddock-horses")?.children ?: emptyList()

    private fun dustOf(world: World): Points? = world.scene.getObjectByName("hoof-dust") as? Points

    private fun meshesOf(horse: Node): List<Mesh> {
        val list = ArrayList<Mesh>()
        horse.traverse { if (it is Mesh) list.add(it) }
        return list
    }

    @Test
    fun `has no grazing horses and no dust on low and medium but two horses and the dust on high`() {
        val world = buildWorld()
        assertEquals(0, horsesOf(world).size)
        assertNull(dustOf(world))
        for (level in listOf(
            GraphicsLevel.MEDIUM,
            GraphicsLevel.HIGH,
            GraphicsLevel.LOW,
            GraphicsLevel.HIGH,
            GraphicsLevel.MEDIUM,
        )) {
            showLevel(world, level)
            val wanted = if (level == GraphicsLevel.HIGH) 2 else 0
            assertEquals(wanted, horsesOf(world).size, "$level")
            assertEquals(level == GraphicsLevel.HIGH, dustOf(world) != null, "$level")
        }
        world.dispose()
    }

    @Test
    fun `draws one call per grazing horse and the dust only while it is alive`() {
        val world = buildWorld()
        val camera = PerspectiveCamera()

        fun calls(level: GraphicsLevel): Int {
            showLevel(world, level)
            return SceneStats.of(world.scene).calls
        }
        val high = calls(GraphicsLevel.HIGH)
        // two horses are in the high budget; no puff yet, so no dust call
        assertTrue(high - HIGH_CALLS_BEFORE <= HANDFUL_OF_CALLS)
        world.emitHoofDust(0.0, 0.0, 0.0, 1.0)
        world.update(0.016, camera)
        assertEquals(high + 1, SceneStats.of(world.scene).calls)
        repeat(200) { world.update(0.016, camera) }
        assertEquals(high, SceneStats.of(world.scene).calls)
        world.dispose()
    }

    @Test
    fun `keeps the horses inside the paddock and away from the props`() {
        val world = worldAt(GraphicsLevel.HIGH)
        val group = checkNotNull(world.scene.getObjectByName("paddock-horses"))
        val keepOut = planPaddockKeepOut()
        for (i in 0 until 60 * 240) {
            world.update(1.0 / 30, towards)
            if (i % 30 != 0) continue
            for (horse in group.children) {
                val x = horse.position.x
                val z = horse.position.z
                assertTrue(paddockContains(x, z, 0.5))
                for (c in keepOut) assertTrue(hypot(x - c.x, z - c.z) > c.r - 0.8)
            }
        }
        world.dispose()
    }

    @Test
    fun `animates the horses only while the paddock is in view`() {
        val world = worldAt(GraphicsLevel.HIGH)
        val group = checkNotNull(world.scene.getObjectByName("paddock-horses"))

        fun state() = group.children.map { listOf(it.position.x, it.position.y, it.position.z) }
        val before = state()
        repeat(60 * 120) { world.update(1.0 / 30, away) }
        assertEquals(before, state())
        repeat(60 * 120) { world.update(1.0 / 30, towards) }
        assertNotEquals(before, state())
        world.dispose()
    }

    @Test
    fun `raises dust for strong footfalls on the sand only`() {
        val world = worldAt(GraphicsLevel.HIGH)
        val dust = checkNotNull(dustOf(world))

        fun puffs(): Boolean {
            world.update(0.016, PerspectiveCamera())
            return dust.visible
        }
        assertFalse(puffs())
        world.emitHoofDust(0.0, 0.0, 5.0, 0.12) // a step at the walk
        assertFalse(puffs())
        world.emitHoofDust(30.0, 0.0, 5.0, 0.9) // on the meadow
        world.emitHoofDust(-30.0, 0.0, 22.0, 0.9) // on the path to the stable
        assertFalse(puffs())
        world.emitHoofDust(0.0, 0.0, 5.0, 0.5) // a trot step on the sand
        assertTrue(puffs())
        world.dispose()
    }

    @Test
    fun `follows the level so the dust pool and the horses go with a switch to low and back`() {
        val world = worldAt(GraphicsLevel.HIGH)
        world.emitHoofDust(0.0, 0.0, 0.0, 1.0)
        showLevel(world, GraphicsLevel.LOW)
        assertNull(dustOf(world))
        world.emitHoofDust(0.0, 0.0, 0.0, 1.0) // nothing to emit into
        showLevel(world, GraphicsLevel.HIGH)
        assertNotNull(dustOf(world))
        assertEquals(2, horsesOf(world).size)
        world.dispose()
    }

    @Test
    fun `releases the GPU objects of the horses and the dust through the release hook`() {
        val released = HashSet<GpuObject?>()
        val world = World(FakeRenderBackend(), presetFor(GraphicsLevel.LOW), release = { released.add(it) })
        showLevel(world, GraphicsLevel.HIGH)
        val objects = world.gpuObjects()
        val geometries = horsesOf(world).flatMap { horse -> meshesOf(horse).map { it.geometry } }
        assertEquals(2, geometries.size)
        assertTrue(geometries.all { it in objects })
        showLevel(world, GraphicsLevel.LOW)
        assertTrue(geometries.all { it in released })
        assertEquals(0, horsesOf(world).size)
        world.dispose()
    }

    @Test
    fun `lists the horses and the dust among the objects of the context loss handling`() {
        val world = worldAt(GraphicsLevel.HIGH)
        world.emitHoofDust(0.0, 0.0, 0.0, 1.0)
        val objects = world.gpuObjects().toSet()
        assertTrue(checkNotNull(dustOf(world)).geometry in objects)
        for (horse in horsesOf(world)) {
            for (mesh in meshesOf(horse)) assertTrue(mesh.geometry in objects)
        }
        world.dispose()
    }
}
