package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.view3d.PADDOCK
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000
private const val DUST_FRAMES = 3000
private const val DT = 1.0 / 60

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner (a
// regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024

// The grazing horses pick a new spot now and then (a few numbers each time); a boxed number per call
// would cost about 130 bytes per frame. Measured: about 4 bytes per frame.
private const val ALLOWED_GRAZING_BYTES_PER_FRAME = 16

private inline fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** The per-frame path of the world: update, the poles, the highlight, the aid, the dust. */
class WorldAllocationTest {
    private val camera = PerspectiveCamera(50.0, 1.6, 0.1, 900.0).also { it.updateMatrixWorld(true) }
    private val zone = Zone(far = 4.0, near = 1.5, lastPoint = 1.0, reach = 5.0, center = 2.0)
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
        world.setAid("b", 1, zone)
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
    fun aFrameWithTheGrazingHorsesDoesNotAllocate() {
        val world = buildWorld(FakeRenderBackend())
        for (id in listOf(
            "density",
            "materials",
            "shadows",
        )) {
            world.applyQualityStage(id, testPreset(GraphicsLevel.HIGH))
        }
        // the horses are only animated while the paddock is in view
        val towards =
            PerspectiveCamera(50.0, 1.6, 0.1, 900.0).also {
                it.position.set(PADDOCK.x, 6.0, PADDOCK.z + 22)
                it.lookAt(PADDOCK.x, 0.0, PADDOCK.z)
                it.updateMatrixWorld(true)
            }
        repeat(WARM_UP_FRAMES) { world.update(DT, towards) }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { world.update(DT, towards) } }
        assertTrue(
            allocated < ALLOWED_GRAZING_BYTES_PER_FRAME * MEASURED_FRAMES,
            "allocated $allocated bytes in $MEASURED_FRAMES frames",
        )
        world.dispose()
    }

    @Test
    fun polesSteppingDoNotAllocate() {
        val world = highWorld()
        repeat(WARM_UP_FRAMES) { i ->
            world.syncRails(if ((i / 100) % 2 == 0) down else up, DT)
            world.update(DT, camera)
        }
        // 200 transitions (a fall or a rise); the frames between them step the poles on
        var stepping = 0L
        var transitions = 0L
        repeat(CYCLES) { i ->
            transitions += allocatedBytes { world.syncRails(if (i % 2 == 0) down else up, DT) }
            stepping += allocatedBytes { repeat(CYCLE_FRAMES) { world.syncRails(null, DT) } }
        }
        assertTrue(stepping < ALLOWED_BYTES, "allocated $stepping bytes stepping the poles")
        // a fall draws its target pose (a few numbers and arrays), once per pole and fall
        assertTrue(
            transitions < ALLOWED_TRANSITION_BYTES * CYCLES,
            "allocated $transitions bytes in $CYCLES transitions",
        )
        world.dispose()
    }

    @Test
    fun hoofDustInFlightDoesNotAllocate() {
        val world = highWorld()
        repeat(WARM_UP_FRAMES) { i ->
            if (i % EMIT_EVERY == 0) world.emitHoofDust(0.0, 0.0, 5.0, 0.8)
            frame(world)
        }
        // footfalls are part of the measured block: a puff takes its numbers without boxing
        val allocated =
            allocatedBytes {
                repeat(DUST_FRAMES) { i ->
                    if (i % EMIT_EVERY == 0) world.emitHoofDust(0.0, 0.0, 5.0, 1.0)
                    frame(world)
                }
            }
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $DUST_FRAMES frames")
        world.dispose()
    }
}

private const val CYCLES = 200
private const val CYCLE_FRAMES = 60 // 1 s: a fall (0.7 s) and a rise (0.45 s) are over
private const val EMIT_EVERY = 20

// a transition starts the fall of some poles: measured about 512 bytes
private const val ALLOWED_TRANSITION_BYTES = 1024
