package app.zoeshorsefarm.render.filament.backend.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.render.filament.backend.RenderCore
import app.zoeshorsefarm.render.filament.backend.device.FakeGpuDevice
import app.zoeshorsefarm.render.filament.backend.device.FakeStage
import app.zoeshorsefarm.render.filament.backend.sync.RecordingLog
import app.zoeshorsefarm.render.filament.material.GlslSanity
import app.zoeshorsefarm.render.filament.material.MaterialSources
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.GpuTracker
import app.zoeshorsefarm.view3d.quality.presetFor
import app.zoeshorsefarm.view3d.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real world of the game, drawn through the Filament backend on fakes: every material must have a shader. */
class WorldOnFilamentTest {
    private val device = FakeGpuDevice()
    private val stage = FakeStage()
    private val log = RecordingLog()
    private val core = RenderCore(device, stage, log)
    private val camera = PerspectiveCamera(58.0, 1.6, 0.1, 900.0).also { it.position.set(0.0, 3.0, 30.0) }

    private fun course() =
        listOf(
            Obstacle(1, listOf(Element("a", ElementKind.VERTICAL, 0.8, 0.0, -10.0, -20.0, 0.0)), directed = false),
            Obstacle(2, listOf(Element("b", ElementKind.OXER, 1.0, 1.2, 10.0, -20.0, 0.0)), directed = false),
        )

    private fun world(level: GraphicsLevel): World {
        val world = World(FakeRenderBackend(), presetFor(level).copy(envMap = false))
        world.setObstacles(course(), flags = true)
        return world
    }

    private fun draw(
        world: World,
        frames: Int = 2,
    ) {
        repeat(frames) {
            world.update(0.016, camera)
            core.render(world.scene, camera)
        }
    }

    @Test
    fun `every level of the world draws without a warning and every shader is valid`() {
        for (level in GraphicsLevel.entries) {
            val world = world(level)
            core.compile(world.compileRoot, camera, world.scene)
            draw(world)
            assertTrue(log.messages.isEmpty(), "$level: ${log.messages}")
            assertTrue(device.liveRenderables.isNotEmpty(), "$level draws something")
            for (spec in device.builtMaterials.keys) {
                val problems = GlslSanity.check(MaterialSources.generate(spec))
                assertTrue(problems.isEmpty(), "$level ${spec.key}: $problems")
            }
            assertTrue(device.builtMaterials.keys.any { it.sand }, "$level has the sand shader")
            assertTrue(core.info.drawCalls > 10, "$level: ${core.info.drawCalls} draw calls")
            world.dispose()
            draw(world, 1)
        }
    }

    @Test
    fun `the program count follows the scene model through level changes`() {
        val world = World(core, presetFor(GraphicsLevel.HIGH).copy(envMap = false))
        world.setObstacles(course(), flags = true)
        val tracker = GpuTracker(world.scene) { core.shadowsEnabled }
        val levels =
            listOf(GraphicsLevel.HIGH, GraphicsLevel.LOW, GraphicsLevel.HIGH, GraphicsLevel.MEDIUM, GraphicsLevel.LOW)
        for (level in levels) {
            world.setQuality(presetFor(level).copy(envMap = false))
            core.compile(world.compileRoot, camera, world.scene)
            tracker.compile(world.compileRoot)
            draw(world)
            assertTrue(log.messages.isEmpty(), "$level: ${log.messages}")
            assertEquals(tracker.snapshot().programs, core.info.programs, "$level programs")
        }
    }

    @Test
    fun `every compile after a level change prepares the shaders it needs`() {
        val world = World(core, presetFor(GraphicsLevel.HIGH).copy(envMap = false))
        world.setObstacles(course(), flags = true)
        for (level in listOf(GraphicsLevel.HIGH, GraphicsLevel.LOW, GraphicsLevel.HIGH)) {
            world.setQuality(presetFor(level).copy(envMap = false))
            val before = device.preparedMaterials.size
            core.compile(world.compileRoot, camera, world.scene)
            val live = device.builtMaterials.keys
            val prepared = device.preparedMaterials.drop(before).toSet()
            // what this compile prepared is alive
            assertTrue(prepared.all { it in live }, "$level")
            draw(world)
        }
        // every material that is alive was prepared at least once since it was last built
        assertTrue(device.builtMaterials.keys.all { it in device.preparedMaterials })
    }

    @Test
    fun `the sun and the sky of the world reach the stage`() {
        val world = world(GraphicsLevel.HIGH)
        draw(world)
        assertTrue(stage.suns.isNotEmpty(), "sun")
        assertTrue(stage.ambients.last().any { it > 0f }, "ambient ${stage.ambients.last().toList()}")
        assertTrue(stage.winds.isNotEmpty(), "wind")
    }
}
