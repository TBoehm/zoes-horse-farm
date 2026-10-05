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

    /** Lets [deltaMs] milliseconds pass and runs what falls due. */
    fun advance(deltaMs: Long) {
        val target = now + deltaMs
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks.filter { it.due <= target }.minByOrNull { it.due } ?: break
            tasks.remove(next)
            now = maxOf(now, next.due)
            next.action()
        }
        now = target
    }
}
