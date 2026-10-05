package app.zoeshorsefarm.render.filament.mesh

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ColorStorage {
    /** Four floats per vertex: exact, 16 bytes. */
    FLOAT4,

    /** Four normalized bytes per vertex: 4 bytes, for big meshes whose colours need no precision. */
    UBYTE4,
}

enum class IndexFormat(
    val byteSize: Int,
) {
    USHORT(2),
    UINT(4),
}

class PackOptions(
    val colorStorage: ColorStorage = ColorStorage.FLOAT4,
    /** Tangents follow the uv direction (normal maps need it); otherwise any perpendicular tangent. */
    val tangentsFromUvs: Boolean = false,
)

class PackedIndices(
    val bytes: ByteArray,
    val count: Int,
    val format: IndexFormat,
)

/** A mesh as bytes in the layout the GPU buffers take; built without any native call. */
class PackedMesh(
    val vertexCount: Int,
    val layout: VertexLayout,
    val buffers: List<ByteArray>,
    val indices: PackedIndices?,
    val bounds: Aabb,
) {
    /** Bytes of vertex and index data on the GPU, for the memory estimate. */
    val byteSize: Long get() = buffers.sumOf { it.size.toLong() } + (indices?.bytes?.size ?: 0)
}

/**
 * Turns [MeshData] into the byte buffers of a Filament `VertexBuffer` and `IndexBuffer`.
 * All data is little endian, which is what every target of the app (arm64, x86-64) reads natively.
 */
object MeshPacker {
    fun pack(
        data: MeshData,
        options: PackOptions = PackOptions(),
    ): PackedMesh {
        val attributes = ArrayList<VertexAttributeSpec>()
        val buffers = ArrayList<ByteArray>()

        fun add(
            semantic: VertexSemantic,
            format: AttributeFormat,
            bytes: ByteArray,
        ) {
            attributes += VertexAttributeSpec(semantic, attributes.size, format)
            buffers += bytes
        }

        val n = data.vertexCount
        add(VertexSemantic.POSITION, AttributeFormat.FLOAT3, packFloatsInto(data.positions, n * 3, ByteArray(0)))
        if (data.normals != null) {
            val frames =
                if (options.tangentsFromUvs && data.uvs != null) {
                    TangentFrames.fromNormalsAndUvs(data.positions, data.normals, data.uvs, data.indices)
                } else {
                    TangentFrames.fromNormals(data.normals, n)
                }
            add(VertexSemantic.TANGENTS, AttributeFormat.SHORT4_SNORM, packShorts(TangentFrames.packSnorm16(frames)))
        }
        if (data.colors != null) {
            when (options.colorStorage) {
                ColorStorage.FLOAT4 -> {
                    add(
                        VertexSemantic.COLOR,
                        AttributeFormat.FLOAT4,
                        packColorsFloat(data.colors, data.colorComponents, n),
                    )
                }

                ColorStorage.UBYTE4 -> {
                    add(
                        VertexSemantic.COLOR,
                        AttributeFormat.UBYTE4_NORM,
                        packColorsByte(data.colors, data.colorComponents, n),
                    )
                }
            }
        }
        if (data.uvs !=
            null
        ) {
            add(VertexSemantic.UV0, AttributeFormat.FLOAT2, packFloatsInto(data.uvs, n * 2, ByteArray(0)))
        }
        if (data.skinIndices != null && data.skinWeights != null) {
            add(VertexSemantic.BONE_INDICES, AttributeFormat.USHORT4, packShorts(data.skinIndices))
            add(
                VertexSemantic.BONE_WEIGHTS,
                AttributeFormat.FLOAT4,
                packFloatsInto(data.skinWeights, n * 4, ByteArray(0)),
            )
        }
        for (attribute in data.custom.sortedBy { it.slot }) {
            add(
                VertexSemantic.custom(attribute.slot),
                AttributeFormat.floats(attribute.components),
                packFloatsInto(attribute.data, n * attribute.components, ByteArray(0)),
            )
        }
        return PackedMesh(
            vertexCount = n,
            layout = VertexLayout(attributes),
            buffers = buffers,
            indices = data.indices?.let(::packIndices),
            bounds = Aabb.ofPositions(data.positions, n),
        )
    }

    /**
     * Writes `count` floats (from `values` at `sourceOffset`) as little endian bytes into `scratch`
     * and returns it, or into a new array when `scratch` is too small. Dynamic data (particles)
     * reuses scratch arrays, see [UploadRing].
     */
    fun packFloatsInto(
        values: FloatArray,
        count: Int,
        scratch: ByteArray,
        sourceOffset: Int = 0,
    ): ByteArray {
        val bytes = if (scratch.size >= count * 4) scratch else ByteArray(count * 4)
        for (i in 0 until count) putInt(bytes, i * 4, values[sourceOffset + i].toRawBits())
        return bytes
    }

    private fun packShorts(values: ShortArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        for (i in values.indices) putShort(bytes, i * 2, values[i].toInt())
        return bytes
    }

    private fun packColorsFloat(
        colors: FloatArray,
        components: Int,
        vertexCount: Int,
    ): ByteArray {
        val bytes = ByteArray(vertexCount * 16)
        for (v in 0 until vertexCount) {
            for (c in 0..3) {
                val value = if (c < components) colors[v * components + c] else 1f
                putInt(bytes, v * 16 + c * 4, value.toRawBits())
            }
        }
        return bytes
    }

    private fun packColorsByte(
        colors: FloatArray,
        components: Int,
        vertexCount: Int,
    ): ByteArray {
        val bytes = ByteArray(vertexCount * 4)
        for (v in 0 until vertexCount) {
            for (c in 0..3) {
                val value = if (c < components) colors[v * components + c] else 1f
                bytes[v * 4 + c] = (max(0f, min(1f, value)) * 255f).roundToInt().toByte()
            }
        }
        return bytes
    }

    private fun packIndices(indices: IntArray): PackedIndices {
        val needsWide = indices.any { it > MAX_USHORT_INDEX }
        return if (needsWide) {
            val bytes = ByteArray(indices.size * 4)
            for (i in indices.indices) putInt(bytes, i * 4, indices[i])
            PackedIndices(bytes, indices.size, IndexFormat.UINT)
        } else {
            val bytes = ByteArray(indices.size * 2)
            for (i in indices.indices) putShort(bytes, i * 2, indices[i])
            PackedIndices(bytes, indices.size, IndexFormat.USHORT)
        }
    }

    private fun putInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun putShort(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private const val MAX_USHORT_INDEX = 0xffff
}
