package app.zoeshorsefarm.platform

// App lifecycle for the crash guard (rule 4): whatever happens to an app that is in the background
// or being closed is not a crash. Web: `visibilitychange` (hidden) and `pagehide` mark the guard
// clean, `visibilitychange` (visible) and `pageshow` mark it again. On the phones the two pairs
// collapse into one: the app is in the foreground or it is not.

/** Whether the player can see the app. */
enum class AppState { FOREGROUND, BACKGROUND }

/** The port the app shells drive (Android lifecycle observer, iOS scene phase). */
interface AppLifecycle {
    val state: AppState

    /** Calls [listener] with the new state after every change; returns the function that unsubscribes. */
    fun onChange(listener: (AppState) -> Unit): () -> Unit
}

/**
 * The [AppLifecycle] the shells push their events into with [update]. Repeating the current state
 * (the platforms often do) notifies nobody.
 */
class ManualAppLifecycle(
    initial: AppState = AppState.FOREGROUND,
) : AppLifecycle {
    private val listeners = LinkedHashSet<(AppState) -> Unit>()

    override var state: AppState = initial
        private set

    fun update(next: AppState) {
        if (next == state) return
        state = next
        for (listener in listeners.toList()) listener(next)
    }

    override fun onChange(listener: (AppState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}

/**
 * Connects the lifecycle to the crash guard: [onBackground] (the guard's `markBackground`) when
 * the app leaves the foreground, [onForeground] (its `resume`) when it returns. Returns the
 * function that removes the connection.
 */
fun bindCrashGuardLifecycle(
    lifecycle: AppLifecycle,
    onBackground: () -> Unit,
    onForeground: () -> Unit,
): () -> Unit =
    lifecycle.onChange { state ->
        when (state) {
            AppState.BACKGROUND -> onBackground()
            AppState.FOREGROUND -> onForeground()
        }
    }
