package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshPackerTest {
    private val triangle = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f, 0f)
    private val normalsUp = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)

    private fun floatBytes(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            for (b in 0..3) out[i * 4 + b] = ((bits shr (8 * b)) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `positions are written as little endian floats in buffer 0`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle))
        val position = packed.layout.attribute(VertexSemantic.POSITION)
        assertNotNull(position)
        assertEquals(0, position.bufferIndex)
        assertEquals(AttributeFormat.FLOAT3, position.format)
        assertContentEquals(floatBytes(*triangle), packed.buffers[0])
    }

    @Test
    fun `the vertex count comes from the positions`() {
        assertEquals(3, MeshPacker.pack(MeshData(positions = triangle)).vertexCount)
    }

    @Test
    fun `normals become tangent frame quaternions in a normalized short4 attribute`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle, normals = normalsUp))
        val tangents = packed.layout.attribute(VertexSemantic.TANGENTS)
        assertNotNull(tangents)
        assertEquals(AttributeFormat.SHORT4_SNORM, tangents.format)
        assertTrue(tangents.normalized)
        // 3 vertices x 4 shorts x 2 bytes
        assertEquals(24, packed.buffers[tangents.bufferIndex].size)
    }

    @Test
    fun `meshes without normals have no tangent attribute`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle))
        assertNull(packed.layout.attribute(VertexSemantic.TANGENTS))
    }

    @Test
    fun `uvs with normals give uv derived tangents when asked`() {
        // u grows along +Y, while the arbitrary frame of a +Z normal has its tangent along +X
        val uvs = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f)
        val data = MeshData(positions = triangle, normals = normalsUp, uvs = uvs)
        val plain = MeshPacker.pack(data)
        val withUvTangents = MeshPacker.pack(data, PackOptions(tangentsFromUvs = true))
        val index = plain.layout.attribute(VertexSemantic.TANGENTS)!!.bufferIndex
        assertTrue(!plain.buffers[index].contentEquals(withUvTangents.buffers[index]))
    }

    @Test
    fun `float colours keep their components and gain an opaque alpha`() {
        val colors = floatArrayOf(1f, 0.5f, 0.25f, 0f, 0f, 0f, 1f, 1f, 1f)
        val packed = MeshPacker.pack(MeshData(positions = triangle, colors = colors))
        val attribute = packed.layout.attribute(VertexSemantic.COLOR)!!
        assertEquals(AttributeFormat.FLOAT4, attribute.format)
        val expected = floatBytes(1f, 0.5f, 0.25f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f)
        assertContentEquals(expected, packed.buffers[attribute.bufferIndex])
    }

    @Test
    fun `four component colours keep their alpha`() {
        val colors = floatArrayOf(1f, 0f, 0f, 0.5f, 0f, 1f, 0f, 0.25f, 0f, 0f, 1f, 1f)
        val packed = MeshPacker.pack(MeshData(positions = triangle, colors = colors, colorComponents = 4))
        val attribute = packed.layout.attribute(VertexSemantic.COLOR)!!
        assertContentEquals(floatBytes(*colors), packed.buffers[attribute.bufferIndex])
    }

    @Test
    fun `byte colours are rounded to normalized unsigned bytes`() {
        val colors = floatArrayOf(1f, 0.5f, 0f, 0f, 0f, 0f, 2f, -1f, 1f)
        val packed =
            MeshPacker.pack(
                MeshData(positions = triangle, colors = colors),
                PackOptions(colorStorage = ColorStorage.UBYTE4),
            )
        val attribute = packed.layout.attribute(VertexSemantic.COLOR)!!
        assertEquals(AttributeFormat.UBYTE4_NORM, attribute.format)
        assertContentEquals(
            byteArrayOf(-1, 128.toByte(), 0, -1, 0, 0, 0, -1, -1, 0, -1, -1),
            packed.buffers[attribute.bufferIndex],
        )
    }

    @Test
    fun `uvs are packed as float2`() {
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 0.5f, 0.25f)
        val packed = MeshPacker.pack(MeshData(positions = triangle, uvs = uvs))
        val attribute = packed.layout.attribute(VertexSemantic.UV0)!!
        assertEquals(AttributeFormat.FLOAT2, attribute.format)
        assertContentEquals(floatBytes(*uvs), packed.buffers[attribute.bufferIndex])
    }

    @Test
    fun `custom attributes use the requested slot and component count`() {
        val petal = CustomAttribute(slot = 0, components = 1, data = floatArrayOf(0f, 1f, 1f))
        val flutter = CustomAttribute(slot = 2, components = 3, data = FloatArray(9) { it.toFloat() })
        val packed = MeshPacker.pack(MeshData(positions = triangle, custom = listOf(petal, flutter)))
        val a = packed.layout.attribute(VertexSemantic.CUSTOM0)!!
        val b = packed.layout.attribute(VertexSemantic.CUSTOM2)!!
        assertEquals(AttributeFormat.FLOAT1, a.format)
        assertEquals(AttributeFormat.FLOAT3, b.format)
        assertContentEquals(floatBytes(0f, 1f, 1f), packed.buffers[a.bufferIndex])
        assertEquals(36, packed.buffers[b.bufferIndex].size)
    }

    @Test
    fun `skin indices are unsigned shorts and weights floats`() {
        val indices = ShortArray(12) { (it % 5).toShort() }
        val weights = FloatArray(12) { 0.25f }
        val packed = MeshPacker.pack(MeshData(positions = triangle, skinIndices = indices, skinWeights = weights))
        val i = packed.layout.attribute(VertexSemantic.BONE_INDICES)!!
        val w = packed.layout.attribute(VertexSemantic.BONE_WEIGHTS)!!
        assertEquals(AttributeFormat.USHORT4, i.format)
        assertEquals(AttributeFormat.FLOAT4, w.format)
        assertEquals(24, packed.buffers[i.bufferIndex].size)
        assertEquals(48, packed.buffers[w.bufferIndex].size)
        assertEquals(0, packed.buffers[i.bufferIndex][0].toInt())
        assertEquals(1, packed.buffers[i.bufferIndex][2].toInt())
    }

    @Test
    fun `every attribute gets its own buffer in declaration order`() {
        val data =
            MeshData(
                positions = triangle,
                normals = normalsUp,
                colors = FloatArray(9),
                uvs = FloatArray(6),
            )
        val packed = MeshPacker.pack(data)
        val order = packed.layout.attributes.map { it.semantic }
        assertEquals(
            listOf(VertexSemantic.POSITION, VertexSemantic.TANGENTS, VertexSemantic.COLOR, VertexSemantic.UV0),
            order,
        )
        assertEquals(listOf(0, 1, 2, 3), packed.layout.attributes.map { it.bufferIndex })
        assertEquals(4, packed.layout.bufferCount)
        assertEquals(4, packed.buffers.size)
    }

    @Test
    fun `small meshes use 16 bit indices`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle, indices = intArrayOf(0, 1, 2)))
        val indices = assertNotNull(packed.indices)
        assertEquals(IndexFormat.USHORT, indices.format)
        assertEquals(3, indices.count)
        assertContentEquals(byteArrayOf(0, 0, 1, 0, 2, 0), indices.bytes)
    }

    @Test
    fun `a vertex index above 65535 switches to 32 bit indices`() {
        val positions = FloatArray(70000 * 3)
        val packed = MeshPacker.pack(MeshData(positions = positions, indices = intArrayOf(0, 1, 69999)))
        val indices = assertNotNull(packed.indices)
        assertEquals(IndexFormat.UINT, indices.format)
        assertEquals(12, indices.bytes.size)
        // 69999 = 0x0001116F little endian
        assertContentEquals(byteArrayOf(0x6F, 0x11, 0x01, 0x00), indices.bytes.copyOfRange(8, 12))
    }

    @Test
    fun `non indexed meshes have no index buffer`() {
        assertNull(MeshPacker.pack(MeshData(positions = triangle)).indices)
    }

    @Test
    fun `bounds cover all positions`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle))
        assertContentEquals(floatArrayOf(0f, 0f, 0f), packed.bounds.min)
        assertContentEquals(floatArrayOf(1f, 2f, 0f), packed.bounds.max)
    }

    @Test
    fun `mismatching attribute sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, normals = FloatArray(6)) }
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, uvs = FloatArray(5)) }
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, colors = FloatArray(8)) }
        assertFailsWith<IllegalArgumentException> { MeshData(positions = FloatArray(4)) }
    }

    @Test
    fun `skin indices and weights must come together`() {
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, skinIndices = ShortArray(12)) }
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, skinWeights = FloatArray(12)) }
    }

    @Test
    fun `an index outside the vertex range is rejected`() {
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, indices = intArrayOf(0, 1, 3)) }
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, indices = intArrayOf(0, -1, 2)) }
    }

    @Test
    fun `duplicate custom slots and slots out of range are rejected`() {
        val a = CustomAttribute(0, 1, FloatArray(3))
        assertFailsWith<IllegalArgumentException> { MeshData(positions = triangle, custom = listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { CustomAttribute(8, 1, FloatArray(3)) }
        assertFailsWith<IllegalArgumentException> { CustomAttribute(0, 5, FloatArray(15)) }
        assertFailsWith<IllegalArgumentException> {
            MeshData(positions = triangle, custom = listOf(CustomAttribute(0, 2, FloatArray(3))))
        }
    }

    @Test
    fun `byte size sums every buffer and the index buffer`() {
        val packed = MeshPacker.pack(MeshData(positions = triangle, normals = normalsUp, indices = intArrayOf(0, 1, 2)))
        // positions 36 + tangents 24 + indices 6
        assertEquals(66L, packed.byteSize)
    }

    @Test
    fun `packFloatsInto reuses a large enough scratch array`() {
        val scratch = ByteArray(64)
        val result = MeshPacker.packFloatsInto(floatArrayOf(1f, 2f), 2, scratch)
        assertTrue(result === scratch)
        assertContentEquals(floatBytes(1f, 2f), scratch.copyOfRange(0, 8))
    }

    @Test
    fun `packFloatsInto allocates when the scratch array is too small`() {
        val result = MeshPacker.packFloatsInto(floatArrayOf(1f, 2f), 2, ByteArray(4))
        assertEquals(8, result.size)
    }

    @Test
    fun `packFloatsInto reads from a source offset`() {
        val result = MeshPacker.packFloatsInto(floatArrayOf(9f, 1f, 2f), 2, ByteArray(0), sourceOffset = 1)
        assertContentEquals(floatBytes(1f, 2f), result)
    }

    @Test
    fun `an empty mesh is allowed and has an empty box`() {
        val packed = MeshPacker.pack(MeshData(positions = FloatArray(0)))
        assertEquals(0, packed.vertexCount)
        assertTrue(packed.bounds.isEmpty)
    }
}
