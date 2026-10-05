package app.zoeshorsefarm.view3d

/** Something scheduled that can still be called off. */
fun interface ScheduledTask {
    fun cancel()
}

/**
 * Port for "run this once after a delay" (the web app's `setTimeout`): the render gate and the
 * restore watchdog use it. The app provides one (a platform timer, or a [TickScheduler] driven
 * by the frame loop).
 */
interface Scheduler {
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): ScheduledTask
}

/**
 * A [Scheduler] that runs tasks when [advance] is called with the time that has passed: the frame
 * loop calls it every frame (it keeps ticking while rendering is held back), and tests use it
 * instead of fake timers. Tasks run in the order of their due time; a task may schedule more.
 */
class TickScheduler : Scheduler {
    private class Task(
        val due: Long,
        val action: () -> Unit,
    ) : ScheduledTask {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val tasks = ArrayList<Task>()
    private var now = 0L

    /** Tasks that are scheduled and not yet run or cancelled. */
    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): ScheduledTask {
        val task = Task(now + delayMs, action)
        tasks.add(task)
        return task
    }

    /**
     * Lets [deltaMs] milliseconds pass and runs what falls due. Called every frame: does not
     * allocate while nothing runs.
     */
    fun advance(deltaMs: Long) {
        val target = now + deltaMs
        while (tasks.isNotEmpty()) {
            removeCancelled()
            val index = indexOfNextDue(target)
            if (index < 0) break
            val next = tasks.removeAt(index)
            now = maxOf(now, next.due)
            next.action()
        }
        now = target
    }

    private fun removeCancelled() {
        var i = tasks.size - 1
        while (i >= 0) {
            if (tasks[i].cancelled) tasks.removeAt(i)
            i--
        }
    }

    /** Index of the earliest task due by [target] (the first one scheduled on a tie), or -1. */
    private fun indexOfNextDue(target: Long): Int {
        var best = -1
        for (i in tasks.indices) {
            val due = tasks[i].due
            if (due <= target && (best < 0 || due < tasks[best].due)) best = i
        }
        return best
    }
}
