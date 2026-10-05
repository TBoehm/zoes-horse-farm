package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000
private const val DUST_FRAMES = 60
private const val DT = 1.0 / 60

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner (a
// regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024
private const val ALLOWED_DUST_BYTES = 8 * 1024

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** The per-frame path of the world: update, the poles, the highlight, the aid, the dust. */
class WorldAllocationTest {
    private val camera = PerspectiveCamera(50.0, 1.6, 0.1, 900.0).also { it.updateMatrixWorld(true) }
    private val aid = AidParams("b", 1, Zone(far = 4.0, near = 1.5, lastPoint = 1.0, reach = 5.0, center = 2.0))
    private val down =
        mapOf(
            "a" to booleanArrayOf(false),
            "b" to booleanArrayOf(false, true),
            "c" to booleanArrayOf(false),
        )
    private val up = mapOf("a" to booleanArrayOf(true), "b" to booleanArrayOf(true, true), "c" to booleanArrayOf(true))

    /** A world at high without the grazing horses (their code has its own allocation test). */
    private fun highWorld(): World {
        val world = buildWorld(FakeRenderBackend())
        val preset = testPreset(GraphicsLevel.HIGH).copy(grazingHorses = 0)
        for (id in listOf("density", "materials", "shadows")) world.applyQualityStage(id, preset)
        return world
    }

    private fun frame(world: World) {
        world.syncRails(null, DT)
        world.highlight("d2", 3)
        world.setAid(aid)
        world.update(DT, camera)
    }

    @Test
    fun aFrameOfTheWorldDoesNotAllocate() {
        val world = highWorld()
        // warm up every path: poles falling and rising, the highlight, the aid
        repeat(WARM_UP_FRAMES) { i ->
            if (i % 600 == 0) world.syncRails(if ((i / 600) % 2 == 0) down else up, DT)
            frame(world)
        }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { frame(world) } }
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
        world.dispose()
    }

    @Test
    fun poleAnimationDoesNotAllocate() {
        val world = highWorld()
        repeat(WARM_UP_FRAMES) { i ->
            world.syncRails(if ((i / 100) % 2 == 0) down else up, DT)
            world.update(DT, camera)
        }
        // the poles fall or rise during most of these frames; starting a fall draws a few numbers
        // (the target pose), so the allowance is higher than for the idle frame
        val allocated =
            allocatedBytes {
                repeat(MEASURED_FRAMES) { i -> world.syncRails(if ((i / 100) % 2 == 0) down else up, DT) }
            }
        assertTrue(allocated < ALLOWED_POLE_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
        world.dispose()
    }

    @Test
    fun hoofDustInFlightDoesNotAllocate() {
        val world = highWorld()
        repeat(WARM_UP_FRAMES) { i ->
            if (i % 30 == 0) world.emitHoofDust(0.0, 0.0, 5.0, 0.8)
            frame(world)
        }
        world.emitHoofDust(0.0, 0.0, 5.0, 1.0)
        val allocated = allocatedBytes { repeat(DUST_FRAMES) { frame(world) } }
        assertTrue(allocated < ALLOWED_DUST_BYTES, "allocated $allocated bytes in $DUST_FRAMES frames")
        world.dispose()
    }
}

// 200 falls and 200 rises over the measured frames; each fall allocates its target pose
private const val ALLOWED_POLE_BYTES = 512 * 1024
