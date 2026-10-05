package app.zoeshorsefarm.view3d

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000
private const val FRAME_MS = 16L

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner
// (a regression that allocates per frame costs several hundred KB here).
private const val ALLOWED_BYTES = 64 * 1024

private const val LONG_DELAY_MS = 1_000_000_000L

class TickSchedulerAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun advancingWithNothingScheduledDoesNotAllocate() {
        val scheduler = TickScheduler()
        repeat(WARM_UP_FRAMES) { scheduler.advance(FRAME_MS) }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { scheduler.advance(FRAME_MS) } }
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }

    @Test
    fun advancingWithOnlyFutureAndCancelledTasksDoesNotAllocate() {
        val scheduler = TickScheduler()
        var ran = 0
        scheduler.schedule(LONG_DELAY_MS) { ran++ }
        scheduler.schedule(LONG_DELAY_MS) { ran++ }
        scheduler.schedule(LONG_DELAY_MS) { ran++ }.cancel()
        repeat(WARM_UP_FRAMES) { scheduler.advance(FRAME_MS) }
        val allocated = allocatedBytes { repeat(MEASURED_FRAMES) { scheduler.advance(FRAME_MS) } }
        assertEquals(0, ran)
        assertEquals(2, scheduler.pending)
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES frames")
    }
}
