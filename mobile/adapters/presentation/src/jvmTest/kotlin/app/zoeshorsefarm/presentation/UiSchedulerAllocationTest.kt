package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.testing.ManualClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class UiSchedulerAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun anIdleTickDoesNotAllocate() {
        val scheduler = UiScheduler(ManualClock())
        repeat(1000) { scheduler.tick() }
        val allocated = allocatedBytes { repeat(100_000) { scheduler.tick() } }
        assertTrue(allocated < 1024, "allocated $allocated bytes")
    }

    @Test
    fun aTickWithOnlyFutureTasksDoesNotAllocate() {
        val scheduler = UiScheduler(ManualClock())
        scheduler.postDelayed(1_000_000) { }
        repeat(1000) { scheduler.tick() }
        val allocated = allocatedBytes { repeat(100_000) { scheduler.tick() } }
        assertTrue(allocated < 1024, "allocated $allocated bytes")
    }

    @Test
    fun firingChangesWithoutAChangeOfListenersDoesNotAllocate() {
        val changes = Changes()
        repeat(3) { changes.listen { } }
        repeat(1000) { changes.fire() }
        val allocated = allocatedBytes { repeat(100_000) { changes.fire() } }
        assertTrue(allocated < 1024, "allocated $allocated bytes")
    }
}
