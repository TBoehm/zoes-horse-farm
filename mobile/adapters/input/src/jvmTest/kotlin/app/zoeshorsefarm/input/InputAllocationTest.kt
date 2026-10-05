package app.zoeshorsefarm.input

import app.zoeshorsefarm.platform.GameKey
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WARM_UP_FRAMES = 3000
private const val MEASURED_FRAMES = 20000

// The JVM measures the bytes this thread allocated; some slack for the JIT and the test runner
// (a poll that allocates costs several hundred KB over this many frames).
private const val ALLOWED_BYTES = 64 * 1024

class InputAllocationTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private fun pollFrames(
        input: Input,
        frames: Int,
    ): Int {
        var jumps = 0
        repeat(frames) {
            val state = input.poll()
            if (state.jump) jumps++
            if (state.steer > 1.0 || state.throttle > 1.0) jumps++ // read like the game does
        }
        return jumps
    }

    @Test
    fun pollingEveryFrameDoesNotAllocate() {
        val input = Input(touchMode = true)
        input.keyboard.onKeyDown(GameKey.KEY_D)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        input.touch.moveStick(0.5, 1.0)
        pollFrames(input, WARM_UP_FRAMES)
        var jumps = 0
        val allocated = allocatedBytes { jumps = pollFrames(input, MEASURED_FRAMES) }
        assertTrue(jumps == 0, "no edge was pressed")
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES polls")
    }

    @Test
    fun pollingFramesWithEdgesDoesNotAllocate() {
        val input = Input(touchMode = false)
        pollFrames(input, WARM_UP_FRAMES)
        var jumps = 0
        val allocated =
            allocatedBytes {
                repeat(MEASURED_FRAMES) {
                    input.keyboard.onKeyDown(GameKey.SPACE)
                    input.touch.pressPause()
                    if (input.poll().jump) jumps++
                }
            }
        assertTrue(jumps == MEASURED_FRAMES, "every pressed jump edge is seen once")
        assertTrue(allocated < ALLOWED_BYTES, "allocated $allocated bytes in $MEASURED_FRAMES polls")
    }
}
