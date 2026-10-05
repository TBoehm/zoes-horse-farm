package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.modes.CourseMode
import app.zoeshorsefarm.application.modes.FreeMode
import app.zoeshorsefarm.application.modes.RideMode
import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.domain.sim.SimInput
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner
// (a regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_QUIET_BYTES = 64 * 1024

// A whole ride with jumps: only its events (take-offs, landings, rails) may allocate.
private const val ALLOWED_BYTES_PER_FRAME = 4.0

class RideSessionAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private fun session(mode: RideMode) = RideSession(mode, FakeStore(), FixedClock(FIXED_ISO), seededRng(1))

    @Test
    fun aFreeRideFrameWithoutEventsDoesNotAllocate() {
        val session = session(FreeMode())
        val input = SimInput()
        input.set(steer = 0.6, throttle = 1.0, gallop = false, jump = false)
        var commands = 0

        fun run(frames: Int) {
            repeat(frames) {
                val out = session.step(STEP_DT, input)
                commands += out.commands.size + out.events.size
                // the view is read every frame by the render loop
                if (session.view.aid != null) commands++
            }
        }
        run(WARM_UP_FRAMES)
        commands = 0
        val allocated = allocatedBytes { run(MEASURED_FRAMES) }
        assertEquals(0, commands, "the measured ride must be free of events and commands")
        assertTrue(allocated < ALLOWED_QUIET_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aCourseRideFrameWithoutEventsDoesNotAllocate() {
        val session = session(CourseMode(1))
        val input = SimInput()
        input.set(steer = 0.6, throttle = 1.0, gallop = false, jump = false)
        var commands = 0

        fun run(frames: Int) {
            repeat(frames) {
                val out = session.step(STEP_DT, input)
                commands += out.commands.size + out.events.size
                // the HUD model is read every frame by the render loop
                if (session.view.hud != null) {
                    commands += session.view.hud
                        ?.timeCs
                        ?.let { 0 } ?: 1
                }
            }
        }
        run(WARM_UP_FRAMES)
        commands = 0
        val allocated = allocatedBytes { run(MEASURED_FRAMES) }
        assertEquals(0, commands, "the measured ride must be free of events and commands")
        assertTrue(allocated < ALLOWED_QUIET_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aWholeCourseRideWithTheAutopilotAllocatesOnlyForItsEvents() {
        val rider = SessionCourseRider(1)
        // warm up on the first frames of the ride, then measure the next ones (jumps included)
        repeat(WARM_UP_FRAMES) { rider.step() }
        var frames = 0
        val allocated =
            allocatedBytes {
                while (frames < MEASURED_FRAMES) {
                    rider.step()
                    frames++
                }
            }
        // jumps, landings and rail events allocate a little each; the frames in between nothing
        val perFrame = allocated.toDouble() / frames
        assertTrue(perFrame < ALLOWED_BYTES_PER_FRAME, "allocated $allocated bytes ($perFrame per frame)")
    }
}
