package app.zoeshorsefarm.render.filament.mesh

import io.github.erkko68.filament.IndexBuffer
import io.github.erkko68.filament.VertexBuffer

/** The Filament vertex attribute slot of a [VertexSemantic]: a one to one mapping, one branch per attribute. */
@Suppress("CyclomaticComplexMethod")
internal fun VertexSemantic.toFilament(): VertexBuffer.VertexAttribute =
    when (this) {
        VertexSemantic.POSITION -> VertexBuffer.VertexAttribute.POSITION
        VertexSemantic.TANGENTS -> VertexBuffer.VertexAttribute.TANGENTS
        VertexSemantic.COLOR -> VertexBuffer.VertexAttribute.COLOR
        VertexSemantic.UV0 -> VertexBuffer.VertexAttribute.UV0
        VertexSemantic.BONE_INDICES -> VertexBuffer.VertexAttribute.BONE_INDICES
        VertexSemantic.BONE_WEIGHTS -> VertexBuffer.VertexAttribute.BONE_WEIGHTS
        VertexSemantic.CUSTOM0 -> VertexBuffer.VertexAttribute.CUSTOM0
        VertexSemantic.CUSTOM1 -> VertexBuffer.VertexAttribute.CUSTOM1
        VertexSemantic.CUSTOM2 -> VertexBuffer.VertexAttribute.CUSTOM2
        VertexSemantic.CUSTOM3 -> VertexBuffer.VertexAttribute.CUSTOM3
        VertexSemantic.CUSTOM4 -> VertexBuffer.VertexAttribute.CUSTOM4
        VertexSemantic.CUSTOM5 -> VertexBuffer.VertexAttribute.CUSTOM5
        VertexSemantic.CUSTOM6 -> VertexBuffer.VertexAttribute.CUSTOM6
        VertexSemantic.CUSTOM7 -> VertexBuffer.VertexAttribute.CUSTOM7
    }

internal fun AttributeFormat.toFilament(): VertexBuffer.AttributeType =
    when (this) {
        AttributeFormat.FLOAT1 -> VertexBuffer.AttributeType.FLOAT
        AttributeFormat.FLOAT2 -> VertexBuffer.AttributeType.FLOAT2
        AttributeFormat.FLOAT3 -> VertexBuffer.AttributeType.FLOAT3
        AttributeFormat.FLOAT4 -> VertexBuffer.AttributeType.FLOAT4
        AttributeFormat.SHORT4_SNORM -> VertexBuffer.AttributeType.SHORT4
        AttributeFormat.UBYTE4_NORM -> VertexBuffer.AttributeType.UBYTE4
        AttributeFormat.USHORT4 -> VertexBuffer.AttributeType.USHORT4
    }

internal fun IndexFormat.toFilament(): IndexBuffer.IndexType =
    when (this) {
        IndexFormat.USHORT -> IndexBuffer.IndexType.USHORT
        IndexFormat.UINT -> IndexBuffer.IndexType.UINT
    }
