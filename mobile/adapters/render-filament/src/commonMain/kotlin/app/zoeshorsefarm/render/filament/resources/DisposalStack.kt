package app.zoeshorsefarm.render.filament.resources

/**
 * Native objects that must be destroyed in the reverse order of their creation (renderables before
 * the buffers and materials they use, those before the scene, the view and the engine).
 *
 * Every resource is added together with the call that destroys it. [disposeAll] runs them last to
 * first, never stops at a failure (a half torn down engine is worse than a logged error) and may be
 * called again safely. Destroy functions may add resources: they are disposed in the same pass.
 */
class DisposalStack {
    private class Entry(
        val resource: Any?,
        val destroy: () -> Unit,
    )

    private val entries = ArrayList<Entry>()

    val size: Int get() = entries.size

    /** Tracks `resource`; returns it. */
    fun <T> add(
        resource: T,
        destroy: (T) -> Unit,
    ): T {
        entries += Entry(resource) { destroy(resource) }
        return resource
    }

    /** Destroys one resource ahead of the others. False if it was not (or is no longer) held. */
    fun dispose(resource: Any?): Boolean {
        val index = entries.indexOfFirst { it.resource === resource }
        if (index < 0) return false
        val entry = entries.removeAt(index)
        entry.destroy()
        return true
    }

    /** Destroys everything, newest first. Returns the errors that destroy functions threw. */
    fun disposeAll(): List<Throwable> {
        val errors = ArrayList<Throwable>()
        while (entries.isNotEmpty()) {
            val entry = entries.removeAt(entries.size - 1)
            try {
                entry.destroy()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                errors += e
            }
        }
        return errors
    }
}
