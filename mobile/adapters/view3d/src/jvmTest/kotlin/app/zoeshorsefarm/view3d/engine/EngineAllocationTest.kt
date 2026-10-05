package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.RideSession
import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.modes.FreeMode
import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.ContextListener
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.render.RenderCapabilities
import app.zoeshorsefarm.scene.render.RenderInfo
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import app.zoeshorsefarm.view3d.quality.DeviceInfo
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000
private const val DT = 1.0 / 60

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner (a
// regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024

// With "Automatic" on, the upgrade governor asks for a target level once per full window (every 10 s
// of riding, 33 times in the measured frames) and the budget estimate behind it allocates a few KB
private const val ALLOWED_AUTO_BYTES = 192 * 1024

// High adds grazing horses and hoof dust of the world, which allocate a little per event (they have
// their own allocation tests); here only a bound that a boxed number per call would break
private const val ALLOWED_HIGH_BYTES_PER_FRAME = 200

// A budget that carries medium but not high, so that the automatic has no level to climb to
private const val MEDIUM_ONLY_BUDGET_MB = 100

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** A backend that draws nothing and allocates nothing, so that the test measures the engine itself. */
private class CountingBackend : RenderBackend {
    override val info = RenderInfo()
    override val capabilities = RenderCapabilities()
    override val gpuDescription = "counting"
    override var pixelRatio = 1.0
        private set
    override var toneMapping = ToneMapping.NONE
    override var toneMappingExposure = 1.0
    override var shadowsEnabled = false
    override var shadowType = ShadowType.BASIC
    override var shadowAutoUpdate = true
    private var width = 0
    private var height = 0
    var renders = 0
        private set

    override fun setPixelRatio(ratio: Double) {
        pixelRatio = ratio
    }

    override fun setSize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
    }

    override fun getSize(target: Vec2): Vec2 = target.set(width.toDouble(), height.toDouble())

    override fun getDrawingBufferSize(target: Vec2): Vec2 = target.set(width * pixelRatio, height * pixelRatio)

    override fun render(
        scene: Scene,
        camera: Camera,
    ) {
        renders++
    }

    override fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit,
    ) = onComplete()

    override fun addContextListener(listener: ContextListener) = Unit

    override fun removeContextListener(listener: ContextListener) = Unit

    override fun dispose() = Unit
}

/** The engine frame as the ride runs it: session step, 3D update, governor and hint, draw. */
class EngineAllocationTest {
    private class Setup(
        level: GraphicsLevel,
        auto: Boolean,
        budgetMB: Int? = null,
    ) {
        val backend = CountingBackend()
        val store = FakeStore(Settings(graphicsLevel = level, graphicsAuto = auto))
        val engine =
            Engine(
                backend,
                SettingsService(store),
                EngineConfig(
                    ViewSize(800.0, 400.0, 2.0),
                    device = DeviceInfo(totalMemoryGiB = 16.0),
                    gpuBudgetOverrideMB = budgetMB,
                    log = { _, error -> throw error },
                ),
            )
        val session = RideSession(FreeMode(), FakeStore(), FixedClock(FIXED_ISO), seededRng(1))
        val input = SimInput().also { it.set(steer = 0.6, throttle = 1.0, gallop = false, jump = false) }
        var now = 0.0

        init {
            engine.beginRide(session.obstacles, session.flags)
            engine.run { dt, rawDt ->
                session.step(dt, input)
                engine.updateRide(dt, session.view)
                engine.governorFrame(rawDt, measuring = true, busy = session.view.jumping)
                engine.lowFpsHintFrame(rawDt, measuring = true)
            }
        }

        fun frame() {
            now += DT
            engine.frame(now)
        }
    }

    @Test
    fun aRidingFrameOfTheEngineDoesNotAllocate() {
        val s = Setup(GraphicsLevel.MEDIUM, auto = false)
        repeat(WARM_UP_FRAMES) { s.frame() }
        val drawn = s.backend.renders
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { s.frame() } }
        assertEquals(drawn + MEASURED_FRAMES, s.backend.renders)
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aRidingFrameWithTheAutomaticMeasuringDoesNotAllocateEither() {
        val s = Setup(GraphicsLevel.MEDIUM, auto = true, budgetMB = MEDIUM_ONLY_BUDGET_MB)
        repeat(WARM_UP_FRAMES) { s.frame() }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { s.frame() } }
        assertTrue(allocated < ALLOWED_AUTO_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
        assertEquals(GraphicsLevel.MEDIUM, s.engine.level)
    }

    @Test
    fun aHighFrameWithDustAndGrazingHorsesStaysSmall() {
        val s = Setup(GraphicsLevel.HIGH, auto = true)
        repeat(WARM_UP_FRAMES) { s.frame() }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { s.frame() } }
        val limit = ALLOWED_HIGH_BYTES_PER_FRAME * MEASURED_FRAMES
        assertTrue(allocated < limit, "allocated $allocated bytes in $MEASURED_FRAMES frames")
        assertEquals(GraphicsLevel.HIGH, s.engine.level)
    }

    @Test
    fun aPausedFrameAndACappedFrameDoNotAllocate() {
        val s = Setup(GraphicsLevel.MEDIUM, auto = false)
        repeat(WARM_UP_FRAMES) { s.frame() }
        s.engine.setCapTo30Fps(true)
        repeat(WARM_UP_FRAMES) { s.frame() }
        s.engine.setPaused(true)
        repeat(WARM_UP_FRAMES) { s.frame() }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { s.frame() } }
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }
}
