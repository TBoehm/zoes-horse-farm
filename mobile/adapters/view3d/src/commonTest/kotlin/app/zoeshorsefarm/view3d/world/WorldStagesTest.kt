package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Light
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.GpuCounts
import app.zoeshorsefarm.scene.render.GpuTracker
import app.zoeshorsefarm.view3d.horse.createHorse
import app.zoeshorsefarm.view3d.quality.MERGED_STAGE_ID
import app.zoeshorsefarm.view3d.quality.QUALITY_STAGE_IDS
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.quality.QualityStageId
import app.zoeshorsefarm.view3d.quality.planQualityStagesFromState
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

// A staged level change must never hold more on the GPU than the larger of the two levels (rule 4):
// what the target level drops is freed BEFORE anything new is compiled or uploaded. The stages run
// in the engine's order (planQualityStagesFromState), each followed by a compile and a frame,
// against the stand-in for the GPU (GpuTracker), which follows the bookkeeping of the backends (a
// material keeps all its programs until it is disposed).

private val COURSE =
    listOf(
        Obstacle(1, listOf(el("a", ElementKind.VERTICAL, 0.8, -10.0, -20.0)), directed = false),
        Obstacle(2, listOf(el("b", ElementKind.OXER, 1.0, 10.0, -20.0, 0.0, 1.2)), directed = false),
        Obstacle(3, listOf(el("c", ElementKind.CROSS, 0.6, 10.0, 0.0, PI)), directed = false),
    )

private val KEYS: List<Pair<String, (GpuCounts) -> Int>> =
    listOf(
        "programs" to { c -> c.programs },
        "materials" to { c -> c.materials },
        "geometries" to { c -> c.geometries },
        "instanced" to { c -> c.instanced },
    )

/**
 * A world with a horse at a level, and what the engine does around it: the stages of a change, each
 * followed by a compile (what the engine's precompile does) and a drawn frame.
 */
private class Rig(
    startLevel: GraphicsLevel,
) {
    private val backend = FakeRenderBackend()
    private val start = testPreset(startLevel)
    val world = World(backend, start)
    private val horse = createHorse(quality = levelOf(start))
    val tracker = GpuTracker(world.scene) { backend.shadowsEnabled }
    private val applied = QUALITY_STAGE_IDS.associateWith { start }.toMutableMap()
    private val camera = PerspectiveCamera()

    init {
        world.setObstacles(COURSE, flags = true)
        world.scene.add(horse.group)
        frame()
    }

    private fun levelOf(p: QualityPreset) = GraphicsLevel.fromId(p.characterDetail.id) ?: p.level

    private fun frame() {
        world.update(0.016, camera)
        tracker.compile(world.compileRoot)
    }

    /** Runs a whole change; returns the ids of the stages it took. */
    fun change(level: GraphicsLevel): List<String> {
        val target = testPreset(level)
        val stages = planQualityStagesFromState(applied, target)
        for (stage in stages) {
            when (stage.id) {
                QualityStageId.CHARACTERS.id -> horse.setQuality(levelOf(target))
                QualityStageId.PIXEL_RATIO.id -> Unit
                else -> world.applyQualityStage(stage.id, target)
            }
            for (covered in stage.covers) applied[covered] = target
            frame()
            frame()
        }
        return stages.map { it.id }
    }
}

/** Counts of a world that rests at a level. */
private fun settled(level: GraphicsLevel): GpuCounts {
    val rig = Rig(level)
    val counts = rig.tracker.snapshot()
    rig.world.dispose()
    return counts
}

private val resting: Map<GraphicsLevel, GpuCounts> by lazy { LEVELS.associateWith { settled(it) } }

class WorldStagesTest {
    @Test
    fun `steps down without ever holding more than before the change`() {
        for ((from, to) in listOf(
            GraphicsLevel.HIGH to GraphicsLevel.MEDIUM,
            GraphicsLevel.MEDIUM to GraphicsLevel.LOW,
            GraphicsLevel.HIGH to GraphicsLevel.LOW,
        )) {
            val rig = Rig(from)
            val before = rig.tracker.snapshot()
            rig.tracker.resetPeak()
            rig.change(to)
            val peak = rig.tracker.peak
            for ((key, count) in KEYS) assertTrue(count(peak) <= count(before), "$from to $to: $key")
            rig.world.dispose()
        }
    }

    @Test
    fun `steps up without ever holding more than the target level needs`() {
        for ((from, to) in listOf(
            GraphicsLevel.LOW to GraphicsLevel.MEDIUM,
            GraphicsLevel.MEDIUM to GraphicsLevel.HIGH,
            GraphicsLevel.LOW to GraphicsLevel.HIGH,
        )) {
            val rig = Rig(from)
            rig.tracker.resetPeak()
            rig.change(to)
            val peak = rig.tracker.peak
            val wanted = resting.getValue(to)
            for ((key, count) in KEYS) assertTrue(count(peak) <= count(wanted), "$from to $to: $key")
            rig.world.dispose()
        }
    }

    @Test
    fun `ends with exactly what a world that started at the target level holds so nothing leaks`() {
        for (from in LEVELS) {
            for (to in LEVELS) {
                val rig = Rig(from)
                rig.change(to)
                assertEquals(resting.getValue(to), rig.tracker.snapshot(), "$from to $to")
                rig.world.dispose()
            }
        }
    }

    @Test
    fun `survives a round trip high to low to high and comes back to the same counts`() {
        val rig = Rig(GraphicsLevel.HIGH)
        rig.change(GraphicsLevel.LOW)
        rig.change(GraphicsLevel.HIGH)
        assertEquals(resting.getValue(GraphicsLevel.HIGH), rig.tracker.snapshot())
        rig.world.dispose()
    }

    @Test
    fun `frees the programs of the old materials in the stage that swaps them`() {
        val rig = Rig(GraphicsLevel.MEDIUM)
        val before = rig.tracker.snapshot().programs
        val stages = rig.change(GraphicsLevel.LOW)
        // the shadows and the materials go in one stage, so nothing is compiled twice
        assertTrue(MERGED_STAGE_ID in stages)
        assertTrue(rig.tracker.snapshot().programs < before + 1)
        // the standard programs are gone: only Lambert, basic and shader programs are left
        assertFalse(rig.tracker.programKeys().any { it.startsWith("MeshStandardMaterial") })
        rig.world.dispose()
    }

    @Test
    fun `gives the GPU buffers of the hidden details back`() {
        val rig = Rig(GraphicsLevel.HIGH)
        val names = listOf("flowers", "birds", "butterflies", "grass-tufts", "planters")
        val meshes = names.map { checkNotNull(rig.world.scene.getObjectByName(it) as? InstancedMesh) }
        for (mesh in meshes) {
            assertTrue(rig.tracker.holds(mesh.geometry), "${mesh.name} geometry before")
            assertTrue(rig.tracker.holds(mesh), "${mesh.name} instances before")
        }
        rig.change(GraphicsLevel.MEDIUM)
        for (mesh in meshes) {
            assertFalse(mesh.visible, mesh.name)
            assertFalse(rig.tracker.holds(mesh.geometry), "${mesh.name} geometry after")
            assertFalse(rig.tracker.holds(mesh), "${mesh.name} instances after")
        }
        rig.world.dispose()
    }

    @Test
    fun `gives the buffers of the high tree models back when the scenery goes to the low ones`() {
        val rig = Rig(GraphicsLevel.HIGH)
        val trees = rig.world.scene.getObjectByName("trees-deciduous") as Mesh
        val highModel = trees.geometry
        assertTrue(rig.tracker.holds(highModel))
        rig.change(GraphicsLevel.LOW)
        assertNotSame(highModel, trees.geometry)
        assertFalse(rig.tracker.holds(highModel))
        rig.world.dispose()
    }

    @Test
    fun `does not compile the programs of hidden meshes because the compile root yields visible ones`() {
        val rig = Rig(GraphicsLevel.LOW)
        val seen = HashSet<String>()
        rig.world.compileRoot.traverse { if (it.name.isNotEmpty()) seen.add(it.name) }
        for (name in listOf("flowers", "birds", "butterflies", "bunting", "planters")) assertFalse(name in seen, name)
        assertTrue("terrain" in seen)
        // the lights come from the target scene: yielded again, they would count twice in the
        // programs (compiling a scene that is not the target walks the lights of that scene)
        var lights = 0
        rig.world.compileRoot.traverseVisible { if (it is Light) lights += 1 }
        assertEquals(0, lights)
        rig.world.dispose()
    }
}
