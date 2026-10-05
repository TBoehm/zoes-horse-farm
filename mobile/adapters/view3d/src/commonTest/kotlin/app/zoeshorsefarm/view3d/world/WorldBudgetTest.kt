package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.collectGpuObjects
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.GpuTracker
import app.zoeshorsefarm.scene.render.SceneStats
import app.zoeshorsefarm.view3d.GpuEpoch
import app.zoeshorsefarm.view3d.paddockContains
import app.zoeshorsefarm.view3d.quality.GpuMemoryContext
import app.zoeshorsefarm.view3d.quality.estimateGpuMemoryMB
import app.zoeshorsefarm.view3d.quality.presetFor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The built world per quality level (rule 3): what is drawn, what it costs in draw calls and
// triangles, and that the details appear, disappear and are freed correctly. No GPU: the numbers
// come from the scene graph (SceneStats) and the stand-in for the GPU (GpuTracker).

private class Budget(
    val calls: Int,
    val triangles: Int,
    val programs: Int = 0,
    val memoryMB: Double = 0.0,
)

// What the world cost before the details (same course, measured at the web app's commit before the
// change, 5e240fc). "low" must never get more than this, "medium" only a little more (the tablet
// lost its context with the full set of details); a deliberate change of the base scenery updates
// these numbers. `programs`: distinct shader programs of what is drawn; `memoryMB`: the estimate
// on the tablet below.
private val BEFORE =
    mapOf(
        GraphicsLevel.LOW to Budget(17, 23924, 10, 30.79),
        GraphicsLevel.MEDIUM to Budget(17, 48796, 11, 122.99),
        GraphicsLevel.HIGH to Budget(18, 94846, 12, 148.89),
    )
private val TABLET = GpuMemoryContext(cssWidth = 1280.0, cssHeight = 800.0, devicePixelRatio = 1.5)

// "A handful" of additional draw calls on high; the limit leaves room for the hoof dust (+1)
private const val HANDFUL_OF_CALLS = 10

// What medium may add to what it was before the details: a couple of draw calls (the paddock props
// and the bunting), one shader program (the bunting is double-sided), a few per cent of triangles
// and under a megabyte of memory
private const val MEDIUM_EXTRA_CALLS = 2
private const val MEDIUM_EXTRA_PROGRAMS = 1
private const val MEDIUM_EXTRA_TRIANGLES = 1.1
private const val MEDIUM_EXTRA_MEMORY_MB = 1.0

// Budgets per level from the research (three.js forum, mobile practice): ~100 draw calls on mobile
private val BUDGET =
    mapOf(
        GraphicsLevel.LOW to Budget(60, 60_000),
        GraphicsLevel.MEDIUM to Budget(100, 90_000),
        GraphicsLevel.HIGH to Budget(150, 250_000),
    )

private fun baseline(level: GraphicsLevel): Budget = BEFORE.getValue(level)

/** One world that rests through the level tests; each test shows the level it needs. */
private object Shared {
    val backend = FakeRenderBackend()
    val world = buildWorld(backend)
    val stats = HashMap<GraphicsLevel, SceneStats>()
    val names = HashMap<GraphicsLevel, Set<String>>()
    val programs = HashMap<GraphicsLevel, Int>()

    init {
        for (level in LEVELS) {
            showLevel(world, level)
            stats[level] = SceneStats.of(world.scene)
            names[level] = visibleNames(world)
            // a fresh tracker per level: what a ride at this level has on the GPU
            val tracker = GpuTracker(world.scene) { backend.shadowsEnabled }
            tracker.compile(world.compileRoot)
            programs[level] = tracker.snapshot().programs
        }
    }

    fun stat(level: GraphicsLevel): SceneStats = stats.getValue(level)

    fun name(level: GraphicsLevel): Set<String> = names.getValue(level)

    fun programs(level: GraphicsLevel): Int = programs.getValue(level)
}

private fun mesh(
    world: World,
    name: String,
): Mesh = world.scene.getObjectByName(name) as Mesh

class WorldPerLevelTest {
    private val low = GraphicsLevel.LOW
    private val medium = GraphicsLevel.MEDIUM
    private val high = GraphicsLevel.HIGH

    @Test
    fun `low draws no more calls and triangles than before the details were added`() {
        assertTrue(Shared.stat(low).calls <= baseline(low).calls)
        assertTrue(Shared.stat(low).triangles <= baseline(low).triangles)
    }

    @Test
    fun `low has no more shader programs than before`() {
        assertTrue(Shared.programs(low) <= baseline(low).programs)
    }

    @Test
    fun `low shows none of the new details`() {
        for (name in listOf("flowers", "birds", "butterflies", "bunting", "decor-props", "planters")) {
            assertFalse(name in Shared.name(low), name)
        }
    }

    @Test
    fun `medium and high stay inside the budget of their level`() {
        for (level in listOf(medium, high)) {
            val budget = BUDGET.getValue(level)
            assertTrue(Shared.stat(level).calls <= budget.calls, "$level calls")
            assertTrue(Shared.stat(level).triangles <= budget.triangles, "$level triangles")
        }
    }

    @Test
    fun `medium stays close to what it was before the details`() {
        val before = baseline(medium)
        assertTrue(Shared.stat(medium).calls <= before.calls + MEDIUM_EXTRA_CALLS)
        assertTrue(Shared.stat(medium).triangles <= before.triangles * MEDIUM_EXTRA_TRIANGLES)
        assertTrue(Shared.programs(medium) <= before.programs + MEDIUM_EXTRA_PROGRAMS)
        val memory = estimateGpuMemoryMB(presetFor(medium), TABLET)
        assertTrue(memory <= before.memoryMB + MEDIUM_EXTRA_MEMORY_MB)
    }

    @Test
    fun `high stays below the budget with its details and costs more programs than medium`() {
        assertTrue(Shared.programs(high) > Shared.programs(medium))
        assertTrue(estimateGpuMemoryMB(presetFor(high), TABLET) > estimateGpuMemoryMB(presetFor(medium), TABLET))
    }

    @Test
    fun `the levels get richer from low to high`() {
        assertTrue(Shared.stat(low).triangles < Shared.stat(medium).triangles)
        assertTrue(Shared.stat(medium).triangles < Shared.stat(high).triangles)
        assertTrue(Shared.stat(low).calls <= Shared.stat(medium).calls)
        assertTrue(Shared.stat(medium).calls <= Shared.stat(high).calls)
        assertTrue(Shared.stat(medium).triangles > baseline(medium).triangles)
        assertTrue(Shared.stat(high).triangles > baseline(high).triangles)
    }

    @Test
    fun `medium adds only the static bunting and the paddock props`() {
        for (name in listOf("bunting", "decor-props")) assertTrue(name in Shared.name(medium), name)
        for (name in listOf("flowers", "birds", "butterflies", "planters", "grass-tufts")) {
            assertFalse(name in Shared.name(medium), name)
        }
    }

    @Test
    fun `high has all the details also the butterflies`() {
        for (name in listOf("flowers", "birds", "butterflies", "bunting", "decor-props", "planters")) {
            assertTrue(name in Shared.name(high), name)
        }
    }

    @Test
    fun `adds only a handful of draw calls on high`() {
        // the hoof dust adds one more while it is alive
        assertTrue(Shared.stat(high).calls - baseline(high).calls <= HANDFUL_OF_CALLS)
    }

    @Test
    fun `has the wind only on high so medium builds the plain programs of the trees and bushes`() {
        val world = Shared.world

        fun windy(level: GraphicsLevel): List<Boolean> {
            showLevel(world, level)
            return listOf("trees-deciduous", "bushes").map {
                mesh(world, it).material.customProgramCacheKey().startsWith("wind-")
            }
        }
        assertEquals(listOf(true, true), windy(high))
        assertEquals(listOf(false, false), windy(medium))
        assertEquals(listOf(false, false), windy(low))
        showLevel(world, high)
    }

    @Test
    fun `thins the bunting out on medium with every second pennant and no grass tufts`() {
        val world = Shared.world

        fun count(
            name: String,
            level: GraphicsLevel,
        ): Int {
            showLevel(world, level)
            val m = mesh(world, name)
            return if (m is InstancedMesh) m.count else m.geometry.drawRange.count
        }
        val mediumBunting = count("bunting", medium)
        val highBunting = count("bunting", high)
        assertTrue(mediumBunting > 0)
        assertTrue(mediumBunting < highBunting)
        // the grass tufts are the biggest cost of the details and only high has them
        assertEquals(0, count("grass-tufts", medium))
        assertTrue(count("grass-tufts", high) > 0)
        // the flowers and the birds are high only
        assertEquals(0, count("flowers", medium))
        assertEquals(0, count("birds", medium))
        showLevel(world, high)
    }

    @Test
    fun `is back to the old cost after high to low and nothing stays behind`() {
        val world = Shared.world
        showLevel(world, high)
        showLevel(world, low)
        val again = SceneStats.of(world.scene)
        assertEquals(Shared.stat(low).calls, again.calls)
        assertEquals(Shared.stat(low).triangles, again.triangles)
        assertEquals(Shared.name(low), visibleNames(world))
    }

    @Test
    fun `puts the paddock fence in only where decoration is on`() {
        val world = Shared.world
        val posts = mesh(world, "fence-posts") as InstancedMesh
        showLevel(world, low)
        val lowCount = posts.count
        showLevel(world, medium)
        assertTrue(posts.count > lowCount)
        showLevel(world, low)
        assertEquals(lowCount, posts.count)
    }

    @Test
    fun `keeps the meadow flowers out of the paddock`() {
        val flowers = mesh(Shared.world, "flowers") as InstancedMesh
        val m = Mat4()
        val p = Vec3()
        for (i in 0 until flowers.userData[USER_TOTAL] as Int) {
            flowers.getMatrixAt(i, m)
            p.setFromMatrixPosition(m)
            assertFalse(paddockContains(p.x, p.z, -1.0))
        }
    }
}

class FlowerBoxTest {
    private val world = buildWorld()
    private val planters = mesh(world, "planters") as InstancedMesh

    @Test
    fun `stand at every foot of every stand and are shown on high only`() {
        assertEquals(STANDS, planters.count)
        assertFalse(planters.visible)
        showLevel(world, GraphicsLevel.MEDIUM)
        assertFalse(planters.visible)
        showLevel(world, GraphicsLevel.HIGH)
        assertTrue(planters.visible)
        showLevel(world, GraphicsLevel.LOW)
        assertFalse(planters.visible)
    }

    @Test
    fun `follow the obstacles when a new course is set or the course is cleared`() {
        showLevel(world, GraphicsLevel.HIGH)
        world.setObstacles(listOf(OBSTACLES[0], OBSTACLES[2]))
        assertEquals(4, planters.count)
        assertTrue(planters.visible)
        world.setObstacles(OBSTACLES)
        assertEquals(STANDS, planters.count)
        world.setObstacles(emptyList())
        assertEquals(0, planters.count)
        assertFalse(planters.visible)
        world.setObstacles(OBSTACLES)
        assertTrue(planters.visible)
    }

    @Test
    fun `sit outside the opening between the stands beside every stand`() {
        world.setObstacles(listOf(OBSTACLES[0]))
        val m = Mat4()
        val p = Vec3()
        val xs = ArrayList<Double>()
        for (i in 0 until planters.count) {
            planters.getMatrixAt(i, m)
            p.setFromMatrixPosition(m)
            xs.add(p.x - OBSTACLES[0].elements[0].x)
            assertEquals(0.0, p.y)
        }
        xs.sort()
        assertTrue(xs[0] < -1.8)
        assertTrue(xs[1] > 1.8)
    }

    @Test
    fun `give each obstacle its own blossom colour and keep it for all its boxes`() {
        world.setObstacles(OBSTACLES)
        val colors = ArrayList<Int>()
        val c = Color()
        for (i in 0 until planters.count) {
            planters.getColorAt(i, c)
            colors.add(c.getHex())
        }
        // the first obstacle has one element with one row: two boxes of one colour
        assertEquals(colors[0], colors[1])
        assertTrue(colors.toSet().size > 2)
    }
}

class DetailStagesTest {
    private val details = listOf("grass-tufts", "flowers", "birds", "butterflies", "bunting", "decor-props", "planters")

    private fun shownDetails(world: World): List<String> =
        details.filter {
            world.scene.getObjectByName(it)?.visible ==
                true
        }

    @Test
    fun `draws no detail between the materials and the density stage of a downgrade`() {
        val world = buildWorld()
        showLevel(world, GraphicsLevel.HIGH)
        assertEquals(details, shownDetails(world))
        val low = testPreset(GraphicsLevel.LOW)
        world.applyQualityStage("shadows", low)
        assertEquals(details, shownDetails(world))
        // the engine compiles after this stage: the details must not be part of it, they go next
        world.applyQualityStage("materials", low)
        assertEquals(emptyList(), shownDetails(world))
        world.applyQualityStage("density", low)
        assertEquals(emptyList(), shownDetails(world))
        world.dispose()
    }

    @Test
    fun `shows no detail with the old materials during a climb and only with the new ones`() {
        val world = buildWorld()
        val high = testPreset(GraphicsLevel.HIGH)
        world.applyQualityStage("density", high)
        assertEquals(emptyList(), shownDetails(world))
        world.applyQualityStage("materials", high)
        assertEquals(details, shownDetails(world))
        for (name in details) assertTrue(mesh(world, name).material is StandardMaterial, name)
        world.dispose()
    }

    @Test
    fun `keeps the details visible when only the density changes from medium to high`() {
        val world = buildWorld()
        showLevel(world, GraphicsLevel.MEDIUM)
        assertTrue(shownDetails(world).isNotEmpty())
        // the same shader programs (no wind code), more details: nothing is held back
        world.applyQualityStage("density", testPreset(GraphicsLevel.HIGH).copy(wind = false))
        assertEquals(details, shownDetails(world))
        world.dispose()
    }

    @Test
    fun `does not show the flower boxes of a new course while the stages are out of step`() {
        val world = buildWorld()
        showLevel(world, GraphicsLevel.HIGH)
        world.applyQualityStage("materials", testPreset(GraphicsLevel.LOW))
        world.setObstacles(OBSTACLES)
        assertEquals(emptyList(), shownDetails(world))
        world.applyQualityStage("density", testPreset(GraphicsLevel.LOW))
        assertEquals(emptyList(), shownDetails(world))
        world.dispose()
    }
}

class ContextLossLevelChangeTest {
    @Test
    fun `marks the materials for a rebuild although their dispose is skipped so no stale wind`() {
        val epoch = GpuEpoch()
        val world = World(FakeRenderBackend(), testPreset(GraphicsLevel.HIGH), release = epoch::release)
        world.setObstacles(OBSTACLES, flags = true)
        world.update(0.016, newCamera())
        val trees = mesh(world, "trees-deciduous").material
        val bushes = mesh(world, "bushes").material
        assertTrue(trees.customProgramCacheKey().startsWith("wind-"))
        val disposed = ArrayList<GpuObject>()
        for (material in listOf(trees, bushes)) material.addDisposeListener { disposed.add(it) }

        epoch.contextLost(world.gpuObjects())
        epoch.contextRestored()
        val versions = listOf(trees.version, bushes.version)
        for (id in listOf(
            "shadows",
            "materials",
            "density",
        )) {
            world.applyQualityStage(id, testPreset(GraphicsLevel.MEDIUM))
        }

        // the backend recompiles a material only when its version changed
        assertEquals(listOf(true, true), listOf(trees.version, bushes.version).mapIndexed { i, v -> v > versions[i] })
        assertFalse(trees.customProgramCacheKey().startsWith("wind-"))
        assertFalse(bushes.customProgramCacheKey().startsWith("wind-"))
        // the dispose of a pre-loss material would delete handles of the lost device
        assertEquals(emptyList(), disposed)
        world.dispose()
    }
}

class AnimationAndCleanupTest {
    @Test
    fun `moves the birds and the wind with every frame`() {
        val world = buildWorld()
        val camera = newCamera()
        showLevel(world, GraphicsLevel.HIGH)
        val birds = mesh(world, "birds") as InstancedMesh
        val m = Mat4()
        birds.getMatrixAt(0, m)
        val before = Vec3().setFromMatrixPosition(m)
        repeat(120) { world.update(1.0 / 60, camera) }
        birds.getMatrixAt(0, m)
        val after = Vec3().setFromMatrixPosition(m)
        assertTrue(after.distanceTo(before) > 3)
        // altitude: birds circle high above the meadow, at a distance
        assertTrue(after.y > 8)
        assertTrue(hypot(after.x, after.z) > 20)
    }

    @Test
    fun `hides the flowers again on low and nothing of them is drawn`() {
        val world = buildWorld()
        val flowers = mesh(world, "flowers") as InstancedMesh
        showLevel(world, GraphicsLevel.HIGH)
        assertTrue(flowers.visible)
        showLevel(world, GraphicsLevel.LOW)
        assertFalse(flowers.visible)
        assertEquals(0, flowers.count)
    }

    @Test
    fun `frees every geometry material and instanced mesh when the world is disposed`() {
        val world = buildWorld()
        showLevel(world, GraphicsLevel.HIGH)
        val freed = HashSet<GpuObject>()
        val objects = collectGpuObjects(world.scene)
        for (o in objects) o.addDisposeListener { freed.add(it) }
        assertTrue(objects.size > 20)
        world.dispose()
        val missed =
            objects
                .filter {
                    it !in freed
                }.map { (it as? Mesh)?.name?.ifEmpty { null } ?: it::class.simpleName }
        assertEquals(emptyList(), missed)
    }

    @Test
    fun `lists the details among the objects handed to the context loss handling`() {
        val world = buildWorld()
        val objects = world.gpuObjects().toSet()
        for (name in listOf("flowers", "birds", "butterflies", "planters")) {
            val mesh = mesh(world, name)
            assertTrue(mesh as GpuObject in objects, name)
            assertTrue(mesh.geometry in objects, "$name geometry")
        }
    }
}
