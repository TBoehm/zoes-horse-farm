package app.zoeshorsefarm.render.filament.backend

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.view3d.horse.createHorse
import app.zoeshorsefarm.view3d.quality.presetFor
import app.zoeshorsefarm.view3d.world.World
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 1500
private const val MEASURED_FRAMES = 6000
private const val DT = 1.0 / 60

// The JVM measures the bytes this thread allocated (escape analysis is off for tests); a frame that
// allocates costs at least 16 bytes, i.e. about 100 KB over the measured frames.
private const val ALLOWED_BYTES = 16 * 1024

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** The steady frame of the backend on the real world and horse: nothing is allocated per frame. */
class RenderCoreAllocationTest {
    private val camera = PerspectiveCamera(58.0, 1.6, 0.1, 900.0).also { it.position.set(0.0, 3.0, 30.0) }

    private fun course() =
        listOf(
            Obstacle(1, listOf(Element("a", ElementKind.VERTICAL, 0.8, 0.0, -10.0, -20.0, 0.0)), directed = false),
            Obstacle(2, listOf(Element("b", ElementKind.OXER, 1.0, 1.2, 10.0, -20.0, 0.0)), directed = false),
        )

    @Test
    fun `a steady frame of the world allocates nothing on every level`() {
        for (level in GraphicsLevel.entries) {
            val world = World(FakeRenderBackend(), presetFor(level).copy(envMap = false, grazingHorses = 0))
            world.setObstacles(course(), flags = true)
            val core = RenderCore(QuietDevice(), QuietStage())

            fun frame() {
                world.update(DT, camera)
                core.render(world.scene, camera)
            }
            core.compile(world.compileRoot, camera, world.scene)
            repeat(WARM_UP_FRAMES) { frame() }
            val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { frame() } }
            assertTrue(allocated < ALLOWED_BYTES, "$level: $allocated bytes in $MEASURED_FRAMES frames")
            world.dispose()
        }
    }

    @Test
    fun `a frame with the horse at canter allocates nothing`() {
        val horse = createHorse(quality = GraphicsLevel.HIGH)
        val scene = Scene()
        scene.add(horse.group)
        val core = RenderCore(QuietDevice(), QuietStage())
        val state =
            Horse().also {
                it.gait = Gait.CANTER
                it.speed = 5.0
            }

        // the horse's own update has its allocation test in view3d; measured here is the backend's frame
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        var allocated = 0L

        fun frame(measure: Boolean) {
            horse.update(DT, state)
            val before = threads.currentThreadAllocatedBytes
            core.render(scene, camera)
            if (measure) allocated += threads.currentThreadAllocatedBytes - before
        }
        repeat(WARM_UP_FRAMES) { frame(false) }
        repeat(MEASURED_FRAMES) { frame(true) }
        assertTrue(allocated < ALLOWED_BYTES, "$allocated bytes in $MEASURED_FRAMES frames")
    }
}
