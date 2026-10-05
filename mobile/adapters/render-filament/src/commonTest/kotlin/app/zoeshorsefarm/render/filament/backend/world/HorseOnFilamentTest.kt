package app.zoeshorsefarm.render.filament.backend.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.render.filament.backend.RenderCore
import app.zoeshorsefarm.render.filament.backend.device.FakeGpuDevice
import app.zoeshorsefarm.render.filament.backend.device.FakeStage
import app.zoeshorsefarm.render.filament.backend.sync.RecordingLog
import app.zoeshorsefarm.render.filament.material.CoatKind
import app.zoeshorsefarm.render.filament.material.GlslSanity
import app.zoeshorsefarm.render.filament.material.MaterialSources
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.view3d.horse.createHorse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real horse and rider, drawn through the Filament backend on fakes. */
class HorseOnFilamentTest {
    private val device = FakeGpuDevice()
    private val log = RecordingLog()
    private val core = RenderCore(device, FakeStage(), log)
    private val camera = PerspectiveCamera(58.0, 1.6, 0.1, 900.0).also { it.position.set(0.0, 2.0, 6.0) }

    @Test
    fun `the horse and the rider are skinned with the coat and every shader is valid on all levels`() {
        for (level in GraphicsLevel.entries) {
            val horse = createHorse(quality = level)
            val scene = Scene()
            scene.add(horse.group)
            val state =
                Horse().also {
                    it.gait = Gait.TROT
                    it.speed = 3.0
                }
            repeat(3) {
                horse.update(1.0 / 60, state)
                core.render(scene, camera)
            }
            assertTrue(log.messages.isEmpty(), "$level: ${log.messages}")
            val skinned = device.liveRenderables.filter { it.options.boneCount > 0 }
            assertTrue(skinned.isNotEmpty(), "$level has skinned meshes")
            assertTrue(skinned.all { it.boneCalls == 3 }, "$level sets the bones every frame")
            val coats = skinned.flatMap { it.materials }.mapNotNull { it.spec.coat }.toSet()
            assertEquals(setOf(if (level == GraphicsLevel.LOW) CoatKind.LOW else CoatKind.STANDARD), coats)
            for (spec in device.builtMaterials.keys) {
                val problems = GlslSanity.check(MaterialSources.generate(spec))
                assertTrue(problems.isEmpty(), "$level ${spec.key}: $problems")
            }
            horse.dispose()
            core.render(scene, camera)
            assertTrue(device.liveRenderables.isEmpty(), "$level: nothing is left after the horse is gone")
        }
    }

    @Test
    fun `a quality change of the horse keeps the backend consistent`() {
        val horse = createHorse(quality = GraphicsLevel.HIGH)
        val scene = Scene()
        scene.add(horse.group)
        val state = Horse()
        for (level in listOf(GraphicsLevel.LOW, GraphicsLevel.HIGH, GraphicsLevel.MEDIUM)) {
            horse.setQuality(level)
            repeat(2) {
                horse.update(1.0 / 60, state)
                core.render(scene, camera)
            }
            assertTrue(log.messages.isEmpty(), "$level: ${log.messages}")
        }
    }
}
