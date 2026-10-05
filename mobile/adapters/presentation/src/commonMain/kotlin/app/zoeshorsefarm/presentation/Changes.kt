package app.zoeshorsefarm.presentation

/**
 * A list of listeners for a model whose state the UI shows: the Compose layer subscribes, reads the
 * model again and redraws when [fire] is called. Models with several sources of change call [fire]
 * from each one.
 *
 * [fire] does not allocate: it walks a snapshot that is only rebuilt when a listener is added or
 * removed, so a listener may unsubscribe (itself or others) while it is called.
 */
class Changes {
    private var snapshot: Array<() -> Unit> = emptyArray()

    /** Calls [listener] on every change; returns the function that unsubscribes. */
    fun listen(listener: () -> Unit): () -> Unit {
        snapshot += listener
        return {
            val index = snapshot.indexOfFirst { it === listener }
            if (index >= 0) snapshot = Array(snapshot.size - 1) { snapshot[if (it < index) it else it + 1] }
        }
    }

    fun fire() {
        val listeners = snapshot
        for (i in listeners.indices) listeners[i]()
    }

    fun clear() {
        snapshot = emptyArray()
    }
}
