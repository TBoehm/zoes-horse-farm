package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.course.courseById
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DT = 1.0 / 60
private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner
// (a regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024

class RidingSimAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun steppingTheSimOverTheCourseObstaclesDoesNotAllocatePerFrame() {
        val course = courseById(1)
        val sim = RidingSim(course.obstacles)
        sim.reset(course.startPose, speed = 0.0, gallop = false)
        // one reused input object, a constant circle at a trot: no obstacle is ever touched
        val input = SimInput()
        input.set(steer = 0.6, throttle = 1.0, gallop = false, jump = false)
        var events = 0

        fun run(frames: Int) {
            repeat(frames) {
                events += sim.step(DT, input).size
                // reading the views must not allocate either
                if (sim.approach != null && sim.horse.jump != null) events++
            }
        }
        run(WARM_UP_FRAMES)
        events = 0
        val allocated = allocatedBytes { run(MEASURED_FRAMES) }
        assertEquals(0, events, "the measured ride must be free of events")
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun theInputOverloadAndThePrimitiveOverloadStepTheSameWay() {
        val course = courseById(1)
        val a = RidingSim(course.obstacles)
        val b = RidingSim(course.obstacles)
        a.reset(course.startPose, speed = 0.0, gallop = false)
        b.reset(course.startPose, speed = 0.0, gallop = false)
        val input = SimInput(steer = 0.3, throttle = 1.0)
        repeat(300) {
            a.step(DT, input)
            b.step(DT, 0.3, 1.0, gallop = false, jump = false)
        }
        assertEquals(a.horse.x, b.horse.x)
        assertEquals(a.horse.z, b.horse.z)
        assertEquals(a.horse.heading, b.horse.heading)
    }
}
