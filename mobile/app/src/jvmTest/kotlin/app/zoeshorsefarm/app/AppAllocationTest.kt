package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.presentation.courses.PrestartModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner (a
// regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024
private const val FRAMES_PER_BEAT = 300 // 5 s at 60 frames per second
private const val ALLOWED_BYTES_PER_HEARTBEAT = 48 * 1024

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** A steady riding frame of the whole app: UI timers, input, session, engine, sound, crash guard. */
class AppAllocationTest {
    private fun riding(
        course: Boolean,
        sound: Boolean,
    ): Pair<AppRig, RideScreenModel> {
        val rig = AppRig(quiet = true)
        rig.app.settings.setGraphicsLevel(GraphicsLevel.MEDIUM)
        rig.app.start()
        if (sound) rig.app.onTouch()
        if (course) {
            rig.app.navigator.go(Route.Prestart(1))
            rig.app.model<PrestartModel>().go()
        } else {
            rig.app.navigator.go(Route.Ride())
        }
        val ride = rig.app.model<RideScreenModel>()
        // a circle at a trot: steady riding without jumps or fences
        ride.input.touch.moveStick(1.0, PI / 3)
        return rig to ride
    }

    @Test
    fun aSteadyFreeRideFrameOfTheAppDoesNotAllocate() {
        val (rig, ride) = riding(course = false, sound = false)
        repeat(WARM_UP_FRAMES) { rig.frame(wallClock = false) }
        val drawn = rig.backends.last.quiet.renders

        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { rig.frame(wallClock = false) } }

        assertEquals(drawn + MEASURED_FRAMES, rig.backends.last.quiet.renders)
        assertFalse(ride.paused)
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aSteadyFrameWithTheSoundOnDoesNotAllocateEither() {
        val (rig, _) = riding(course = false, sound = true)
        repeat(WARM_UP_FRAMES) { rig.frame(wallClock = false) }

        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { rig.frame(wallClock = false) } }

        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun aSteadyCourseRideFrameWithTheHudStaysSmall() {
        val (rig, _) = riding(course = true, sound = false)
        repeat(WARM_UP_FRAMES) { rig.frame(wallClock = false) }

        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { rig.frame(wallClock = false) } }

        // the HUD text of the course time is rebuilt whenever a visible value changes (every second)
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun theHeartbeatOfTheCrashGuardIsTheOnlyThingThatAllocatesInASteadyFrame() {
        val (rig, _) = riding(course = false, sound = false)
        repeat(WARM_UP_FRAMES) { rig.frame() }

        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { rig.frame() } }

        // every 5 seconds the guard writes its section (the web app does too): JSON text of the save
        val heartbeats = MEASURED_FRAMES / FRAMES_PER_BEAT + 1
        val limit = ALLOWED_BYTES + heartbeats * ALLOWED_BYTES_PER_HEARTBEAT
        assertTrue(allocated < limit, "allocated $allocated bytes in $MEASURED_FRAMES frames, $heartbeats beats")
    }

    @Test
    fun aPausedFrameDoesNotAllocate() {
        val (rig, ride) = riding(course = false, sound = false)
        repeat(WARM_UP_FRAMES) { rig.frame(wallClock = false) }
        ride.onFocusLost()
        repeat(WARM_UP_FRAMES) { rig.frame(wallClock = false) }

        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { rig.frame(wallClock = false) } }

        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }
}
