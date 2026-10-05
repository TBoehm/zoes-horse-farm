package app.zoeshorsefarm.render.filament.mesh

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A byte array that is being filled for an upload and that Filament reads later.
 *
 * Filament does not copy the data when `setBufferAt` or `setImage` is called: on iOS the array is
 * pinned and its address is handed to the render thread (filament-kmp `upload`), and the array is
 * released through the callback once the driver has consumed it. Writing to it earlier corrupts the
 * frame. [onConsumed] is that callback: pass it as the release callback of the upload.
 */
@OptIn(ExperimentalAtomicApi::class)
class UploadSlot internal constructor(
    bytes: ByteArray,
    private val tracked: Boolean,
) {
    var bytes: ByteArray = bytes
        internal set

    private val busy = AtomicBoolean(false)

    val isBusy: Boolean get() = busy.load()

    internal fun claim(): Boolean = busy.compareAndSet(expectedValue = false, newValue = true)

    /** Marks the array free again; called by Filament (on its own thread) when the upload is done. */
    val onConsumed: () -> Unit = { if (tracked) busy.store(false) }
}

/**
 * A few reusable byte arrays for data that changes often (the particle buffers of the hoof dust), so
 * that updates do not allocate every frame. A slot is only handed out again after Filament has
 * consumed it. If the driver falls behind and every slot is still in use, a one shot array is made
 * instead (counted in [overflowCount]) so that nothing is ever overwritten too early.
 */
class UploadRing(
    slotCount: Int = DEFAULT_SLOTS,
) {
    private val slots: List<UploadSlot>

    /** How often all slots were busy. */
    var overflowCount: Int = 0
        private set

    init {
        require(slotCount >= 1) { "an upload ring needs at least one slot" }
        slots = List(slotCount) { UploadSlot(ByteArray(0), tracked = true) }
    }

    /** A slot whose `bytes` hold at least `size` bytes; it is marked busy until `onConsumed` runs. */
    fun acquire(size: Int): UploadSlot {
        for (slot in slots) {
            if (slot.claim()) {
                if (slot.bytes.size < size) slot.bytes = ByteArray(size)
                return slot
            }
        }
        overflowCount++
        return UploadSlot(ByteArray(size), tracked = false).also { it.claim() }
    }

    companion object {
        /** The driver is at most a frame or two behind; three slots cover that. */
        const val DEFAULT_SLOTS = 3
    }
}
