package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.presentation.IdleEngine
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000
private const val DT = 1.0 / 60

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner
// (a regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_QUIET_BYTES = 64 * 1024

// The frame of the ride screen is called 60 times per second by the render loop: a frame without
// events must not allocate (the course HUD changes its time every frame).
class RideScreenAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private fun measure(route: Route.Ride): Pair<Long, RideScreenModel> {
        val app = TestApp()
        app.navigator.register(
            Route.Ride.NAME,
        ) { RideScreenModel(app.ctx, it as Route.Ride, seededRng(1), IdleEngine()) }
        app.navigator.go(route)
        val model = app.navigator.currentModel as RideScreenModel
        // somebody listens, as the UI does: events would reach it, quiet frames must not
        model.changes.listen { }
        model.input.touch.moveStick(force = 1.0, radian = PI / 2 - 0.5)
        var results = 0

        fun run(frames: Int) {
            repeat(frames) {
                if (model.frame(DT, DT) != FrameResult.RUNNING) results++
                // the engine reads the view every frame
                if (model.view.aid != null) results += 0
                // the UI reads the HUD every frame
                model.hud?.timeCs
            }
        }
        run(WARM_UP_FRAMES)
        results = 0
        val allocated = allocatedBytes { run(MEASURED_FRAMES) }
        assertEquals(0, results, "the measured ride must keep running")
        assertNull(model.feedbackText, "the measured ride must be free of events")
        return allocated to model
    }

    @Test
    fun aFreeRideFrameDoesNotAllocate() {
        val (allocated, _) = measure(Route.Ride())
        assertTrue(allocated < ALLOWED_QUIET_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aCourseRideFrameDoesNotAllocateAlthoughTheHudTimeChangesEveryFrame() {
        val (allocated, model) = measure(Route.Ride(RideModeId.COURSE, 1))
        assertTrue(model.hud != null)
        assertTrue(allocated < ALLOWED_QUIET_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }
}
