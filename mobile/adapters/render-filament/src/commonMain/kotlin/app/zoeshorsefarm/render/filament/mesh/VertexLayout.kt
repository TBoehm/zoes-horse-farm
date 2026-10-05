package app.zoeshorsefarm.render.filament.mesh

/** Vertex attributes a mesh can carry; maps one to one onto Filament's `VertexAttribute`. */
enum class VertexSemantic {
    POSITION,
    TANGENTS,
    COLOR,
    UV0,
    BONE_INDICES,
    BONE_WEIGHTS,
    CUSTOM0,
    CUSTOM1,
    CUSTOM2,
    CUSTOM3,
    CUSTOM4,
    CUSTOM5,
    CUSTOM6,
    CUSTOM7,
    ;

    companion object {
        fun custom(slot: Int): VertexSemantic {
            require(slot in 0 until CustomAttribute.MAX_CUSTOM_SLOTS) { "custom slot $slot" }
            return entries[CUSTOM0.ordinal + slot]
        }
    }
}

/** How one attribute is stored in its vertex buffer. */
enum class AttributeFormat(
    val byteSize: Int,
    val normalized: Boolean = false,
) {
    FLOAT1(4),
    FLOAT2(8),
    FLOAT3(12),
    FLOAT4(16),
    SHORT4_SNORM(8, normalized = true),
    UBYTE4_NORM(4, normalized = true),
    USHORT4(8),
    ;

    /** Stored as plain floats: the attributes that can be rewritten from a `FloatArray`. */
    val isFloat: Boolean get() = this == FLOAT1 || this == FLOAT2 || this == FLOAT3 || this == FLOAT4

    companion object {
        fun floats(components: Int): AttributeFormat =
            when (components) {
                1 -> FLOAT1
                2 -> FLOAT2
                3 -> FLOAT3
                4 -> FLOAT4
                else -> throw IllegalArgumentException("a float attribute has 1 to 4 components, not $components")
            }
    }
}

class VertexAttributeSpec(
    val semantic: VertexSemantic,
    val bufferIndex: Int,
    val format: AttributeFormat,
) {
    val normalized: Boolean get() = format.normalized
}

/** One attribute per buffer, so a single attribute can be updated without touching the others. */
class VertexLayout(
    val attributes: List<VertexAttributeSpec>,
) {
    val bufferCount: Int get() = attributes.size

    fun attribute(semantic: VertexSemantic): VertexAttributeSpec? = attributes.firstOrNull { it.semantic == semantic }

    fun has(semantic: VertexSemantic): Boolean = attribute(semantic) != null
}
