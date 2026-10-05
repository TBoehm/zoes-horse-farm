package app.zoeshorsefarm.presentation

/**
 * A list of listeners for a model whose state the UI shows: the Compose layer subscribes, reads the
 * model again and redraws when [fire] is called. Models with several sources of change call [fire]
 * from each one.
 */
class Changes {
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Calls [listener] on every change; returns the function that unsubscribes. */
    fun listen(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun fire() {
        for (listener in listeners.toList()) listener()
    }

    fun clear() = listeners.clear()
}
