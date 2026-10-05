package app.zoeshorsefarm.render.filament.resources

enum class ResourceKind { VERTEX_BUFFER, INDEX_BUFFER, TEXTURE, MATERIAL, SKINNING, SHADOW_MAP, FRAME, OTHER }

class ResourceInfo(
    val id: Int,
    val kind: ResourceKind,
    val label: String,
    val bytes: Long,
)

/**
 * What the backend has on the GPU, in bytes, for the memory estimate that decides which graphics
 * level fits (the web `estimateGpuMemoryMB`). Every wrapper that creates a buffer or texture
 * registers it here and releases it when it destroys the object; the tracker never touches native
 * objects itself.
 */
class GpuResourceTracker {
    private val live = LinkedHashMap<Int, ResourceInfo>()
    private var nextId = 1

    val totalBytes: Long get() = live.values.sumOf { it.bytes }
    val totalMegabytes: Double get() = totalBytes / BYTES_PER_MEGABYTE
    val totalCount: Int get() = live.size

    fun register(
        kind: ResourceKind,
        label: String,
        bytes: Long,
    ): Int {
        require(bytes >= 0) { "a resource cannot have a negative size" }
        val id = nextId++
        live[id] = ResourceInfo(id, kind, label, bytes)
        return id
    }

    fun resize(
        id: Int,
        bytes: Long,
    ) {
        require(bytes >= 0) { "a resource cannot have a negative size" }
        val old = requireNotNull(live[id]) { "unknown resource $id" }
        live[id] = ResourceInfo(id, old.kind, old.label, bytes)
    }

    /** True if the resource was tracked. */
    fun release(id: Int): Boolean = live.remove(id) != null

    fun bytesOf(kind: ResourceKind): Long = live.values.filter { it.kind == kind }.sumOf { it.bytes }

    fun countOf(kind: ResourceKind): Int = live.values.count { it.kind == kind }

    fun snapshot(): List<ResourceInfo> = live.values.toList()

    fun clear() {
        live.clear()
    }

    private companion object {
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
    }
}
