package app.zoeshorsefarm.scene

/** Called after [GpuResource.dispose]; a render backend frees what it holds for the resource. */
fun interface DisposeListener {
    fun onDispose(resource: GpuResource)
}

/**
 * Something a render backend may upload to the GPU (geometry, material, texture, instanced mesh).
 * Like in three.js, `dispose()` does not destroy the object: the backend frees its GPU copy and
 * uploads again if the resource is used afterwards.
 */
abstract class GpuResource {
    private val listeners = ArrayList<DisposeListener>(0)

    /** How often [dispose] was called (a cheap way for tests to see that something was freed). */
    var disposeCount: Int = 0
        private set

    fun addDisposeListener(listener: DisposeListener) {
        listeners.add(listener)
    }

    fun removeDisposeListener(listener: DisposeListener) {
        listeners.remove(listener)
    }

    open fun dispose() {
        disposeCount++
        // iterate over a copy: listeners remove themselves while they run
        for (listener in listeners.toTypedArray()) listener.onDispose(this)
    }
}
