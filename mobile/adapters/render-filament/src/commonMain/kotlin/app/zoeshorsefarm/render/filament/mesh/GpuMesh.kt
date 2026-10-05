package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import app.zoeshorsefarm.render.filament.resources.ResourceKind
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndexBuffer
import io.github.erkko68.filament.VertexBuffer

/**
 * A mesh on the GPU: one Filament `VertexBuffer` (one buffer per attribute, see [VertexLayout]) and
 * an `IndexBuffer` for indexed meshes. Made from [MeshData] with [upload]; destroy it with
 * [destroy] after every renderable that uses it is gone.
 *
 * Attributes that change (particles) are rewritten with [updateFloats]; Filament reads the bytes
 * later, so updates go through an [UploadRing].
 */
class GpuMesh private constructor(
    private val engine: Engine,
    private val tracker: GpuResourceTracker,
    val label: String,
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer?,
    val layout: VertexLayout,
    val vertexCount: Int,
    val indexCount: Int,
    val bounds: Aabb,
    private val vertexByteSizes: List<Int>,
    private val trackerIds: List<Int>,
) {
    private var destroyed = false
    private var frameScratch: FloatArray? = null

    /** GPU bytes of this mesh. */
    val byteSize: Long get() = vertexByteSizes.sumOf { it.toLong() } + indexBytes

    private var indexBytes: Long = 0

    fun has(semantic: VertexSemantic): Boolean = layout.has(semantic)

    /**
     * Rewrites the first `count` floats of a float attribute (`POSITION`, `UV0`, `CUSTOM*`, a float
     * `COLOR`), for example the positions of the particles. `count` is vertices times components.
     */
    fun updateFloats(
        semantic: VertexSemantic,
        values: FloatArray,
        count: Int,
        ring: UploadRing,
    ) {
        check(!destroyed) { "mesh $label is destroyed" }
        val attribute = requireNotNull(layout.attribute(semantic)) { "mesh $label has no $semantic" }
        require(attribute.format.isFloat) { "$semantic is not stored as floats" }
        val bytes = count * FLOAT_BYTES
        require(bytes <= vertexByteSizes[attribute.bufferIndex]) { "$count floats do not fit into $semantic" }
        // Filament reads a byte count of 0 as "the whole array"
        if (bytes == 0) return
        val slot = ring.acquire(bytes)
        MeshPacker.packFloatsInto(values, count, slot.bytes)
        vertexBuffer.setBufferAt(engine, attribute.bufferIndex, slot.bytes, 0, bytes, slot.onConsumed)
    }

    /**
     * Rewrites the normals (as new tangent frames) of a mesh whose geometry was deformed on the CPU
     * (the reins, every frame). Nothing is allocated after the first call: the frames are computed
     * into a scratch array of the mesh and packed straight into a slot of `ring`.
     */
    fun updateNormals(
        normals: FloatArray,
        ring: UploadRing,
    ) {
        check(!destroyed) { "mesh $label is destroyed" }
        val attribute = requireNotNull(layout.attribute(VertexSemantic.TANGENTS)) { "mesh $label has no normals" }
        require(normals.size == vertexCount * 3) { "normals must hold one xyz per vertex" }
        if (vertexCount == 0) return
        val frames = frameScratch ?: FloatArray(vertexCount * FRAME_FLOATS).also { frameScratch = it }
        TangentFrames.fromNormalsInto(normals, vertexCount, frames)
        val bytes = vertexCount * FRAME_FLOATS * SHORT_BYTES
        val slot = ring.acquire(bytes)
        TangentFrames.packSnorm16Into(frames, vertexCount * FRAME_FLOATS, slot.bytes)
        vertexBuffer.setBufferAt(engine, attribute.bufferIndex, slot.bytes, 0, bytes, slot.onConsumed)
    }

    /** Frees the buffers. Safe to call twice. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        engine.destroy(vertexBuffer)
        indexBuffer?.let { engine.destroy(it) }
        trackerIds.forEach { tracker.release(it) }
    }

    companion object {
        private const val FLOAT_BYTES = 4
        private const val SHORT_BYTES = 2
        private const val FRAME_FLOATS = 4

        /**
         * Packs `data` and creates the GPU buffers. The packed bytes are handed to Filament as they
         * are and are not touched again.
         */
        fun upload(
            engine: Engine,
            tracker: GpuResourceTracker,
            label: String,
            data: MeshData,
            options: PackOptions = PackOptions(),
        ): GpuMesh = upload(engine, tracker, label, MeshPacker.pack(data, options))

        fun upload(
            engine: Engine,
            tracker: GpuResourceTracker,
            label: String,
            packed: PackedMesh,
        ): GpuMesh {
            val builder =
                VertexBuffer
                    .Builder()
                    .vertexCount(packed.vertexCount)
                    .bufferCount(packed.layout.bufferCount)
            for (attribute in packed.layout.attributes) {
                builder.attribute(
                    attribute.semantic.toFilament(),
                    attribute.bufferIndex,
                    attribute.format.toFilament(),
                    0,
                    attribute.format.byteSize,
                )
                if (attribute.normalized) builder.normalized(attribute.semantic.toFilament())
            }
            val vertexBuffer = builder.build(engine)
            val ids = ArrayList<Int>()
            packed.layout.attributes.forEachIndexed { index, attribute ->
                vertexBuffer.setBufferAt(engine, attribute.bufferIndex, packed.buffers[index])
                ids +=
                    tracker.register(
                        ResourceKind.VERTEX_BUFFER,
                        "$label ${attribute.semantic}",
                        packed.buffers[index].size.toLong(),
                    )
            }
            var indexBuffer: IndexBuffer? = null
            var indexBytes = 0L
            val indices = packed.indices
            if (indices != null) {
                indexBuffer =
                    IndexBuffer
                        .Builder()
                        .indexCount(indices.count)
                        .bufferType(indices.format.toFilament())
                        .build(engine)
                indexBuffer.setBuffer(engine, indices.bytes)
                indexBytes = indices.bytes.size.toLong()
                ids += tracker.register(ResourceKind.INDEX_BUFFER, "$label indices", indexBytes)
            }
            return GpuMesh(
                engine = engine,
                tracker = tracker,
                label = label,
                vertexBuffer = vertexBuffer,
                indexBuffer = indexBuffer,
                layout = packed.layout,
                vertexCount = packed.vertexCount,
                indexCount = indices?.count ?: 0,
                bounds = packed.bounds,
                vertexByteSizes = packed.buffers.map { it.size },
                trackerIds = ids,
            ).also { it.indexBytes = indexBytes }
        }
    }
}
