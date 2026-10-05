package app.zoeshorsefarm.scene

/** Called after [GpuObject.dispose]; a render backend frees what it holds for the object. */
fun interface DisposeListener {
    fun onDispose(resource: GpuObject)
}

/**
 * Something a render backend may upload to the GPU (geometry, material, texture, instanced mesh,
 * bone data). Like in three.js, `dispose()` does not destroy the object: the backend frees its GPU
 * copy and uploads again if the object is used afterwards.
 */
interface GpuObject {
    /** How often [dispose] was called (a cheap way for tests to see that something was freed). */
    val disposeCount: Int

    fun addDisposeListener(listener: DisposeListener)

    fun removeDisposeListener(listener: DisposeListener)

    fun dispose()
}

/** Listener bookkeeping shared by the implementations of [GpuObject]. */
class DisposeSupport(
    private val owner: GpuObject,
) {
    private val listeners = ArrayList<DisposeListener>(0)

    var count: Int = 0
        private set

    fun add(listener: DisposeListener) {
        listeners.add(listener)
    }

    fun remove(listener: DisposeListener) {
        listeners.remove(listener)
    }

    fun fire() {
        count++
        // iterate over a copy: listeners remove themselves while they run
        for (listener in listeners.toTypedArray()) listener.onDispose(owner)
    }
}

/** Base class of the GPU objects of this module. */
abstract class GpuResource : GpuObject {
    private val support = DisposeSupport(this)

    override val disposeCount: Int get() = support.count

    override fun addDisposeListener(listener: DisposeListener) = support.add(listener)

    override fun removeDisposeListener(listener: DisposeListener) = support.remove(listener)

    override fun dispose() = support.fire()
}
