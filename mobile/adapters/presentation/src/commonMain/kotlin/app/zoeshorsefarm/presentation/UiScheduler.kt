package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.Clock

/** A delayed task that can still be called off. */
fun interface UiTask {
    fun cancel()
}

/**
 * "Run this once after a delay" for the screen models (the web's `setTimeout`): the music delay, the
 * badge toasts. The models are not thread safe, so the tasks run on the thread that calls [tick] and
 * nowhere else: the UI calls [tick] on every frame of its own clock (Compose frame callback), the
 * engine's frame loop may call it too. Idle (no task) a tick does nothing and allocates nothing.
 *
 * Never reuse the scheduler of the audio module for the UI: its tasks run on a daemon thread.
 *
 * @param clock the time source ([AppContext.clock]); only differences matter
 */
class UiScheduler(
    private val clock: Clock,
) {
    private class Task(
        val due: Long,
        val action: () -> Unit,
    ) : UiTask {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val tasks = ArrayList<Task>()

    // while a task runs: its due time, so that a task it schedules counts from there, not from "now"
    private var running = NOT_RUNNING

    /** Tasks that are scheduled and neither run nor cancelled. */
    val pending: Int get() = tasks.count { !it.cancelled }

    /** Runs [action] once after [delayMs] milliseconds (counted from the next [tick]s). */
    fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): UiTask {
        val base = if (running != NOT_RUNNING) running else clock.nowMs()
        return Task(base + delayMs, action).also { tasks.add(it) }
    }

    /** Runs the tasks that are due by now, in the order of their due time. */
    fun tick() {
        if (tasks.isEmpty()) return
        val now = clock.nowMs()
        while (true) {
            val next = takeNextDue(now) ?: break
            running = next.due
            try {
                next.action()
            } finally {
                running = NOT_RUNNING
            }
        }
    }

    // the cancelled tasks are dropped on the way; no allocation
    private fun takeNextDue(now: Long): Task? {
        var best = -1
        var i = tasks.size - 1
        while (i >= 0) {
            val task = tasks[i]
            if (task.cancelled) {
                tasks.removeAt(i)
                if (best > i) best--
            } else if (task.due <= now && (best < 0 || task.due <= tasks[best].due)) {
                best = i
            }
            i--
        }
        return if (best >= 0) tasks.removeAt(best) else null
    }

    /** Drops every task. */
    fun clear() = tasks.clear()

    private companion object {
        const val NOT_RUNNING = Long.MIN_VALUE
    }
}
