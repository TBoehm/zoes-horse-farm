package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpriteQuadsTest {
    @Test
    fun `each particle is a quad of four vertices and two triangles`() {
        val mesh = SpriteQuads.meshData(capacity = 3)
        assertEquals(12, mesh.vertexCount)
        val indices = checkNotNull(mesh.indices)
        assertEquals(18, indices.size)
        assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7), indices.copyOfRange(0, 12))
    }

    @Test
    fun `the corners run around the quad and repeat for every particle`() {
        val mesh = SpriteQuads.meshData(capacity = 2)
        val corners = mesh.custom.single { it.slot == 0 }
        assertEquals(2, corners.components)
        assertContentEquals(
            floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
            corners.data,
        )
    }

    @Test
    fun `the size and opacity attribute starts at zero so unused particles are invisible`() {
        val mesh = SpriteQuads.meshData(capacity = 2)
        val puff = mesh.custom.single { it.slot == 1 }
        assertEquals(2, puff.components)
        assertEquals(16, puff.data.size)
        assertEquals(true, puff.data.all { it == 0f })
    }

    @Test
    fun `the mesh provides what a sprite material requires`() {
        val mesh = SpriteQuads.meshData(capacity = 1)
        assertEquals(
            setOf(VertexSemantic.POSITION, VertexSemantic.CUSTOM0, VertexSemantic.CUSTOM1),
            mesh.semantics,
        )
    }

    @Test
    fun `centres are repeated for the four corners of a quad`() {
        val out = FloatArray(24)
        SpriteQuads.expandCenters(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 2, out)
        val first = listOf(1f, 2f, 3f)
        val second = listOf(4f, 5f, 6f)
        assertEquals(first + first + first + first + second + second + second + second, out.toList())
    }

    @Test
    fun `size and opacity are repeated for the four corners of a quad`() {
        val out = FloatArray(16)
        SpriteQuads.expandPuffs(floatArrayOf(0.5f, 0.25f), floatArrayOf(0.1f, 0.2f), 2, out)
        val first = listOf(0.5f, 0.1f)
        val second = listOf(0.25f, 0.2f)
        assertEquals(first + first + first + first + second + second + second + second, out.toList())
    }

    @Test
    fun `the capacity is limited by the 16 bit index range`() {
        assertEquals(16383, SpriteQuads.MAX_CAPACITY)
        assertEquals(true, runCatching { SpriteQuads.meshData(SpriteQuads.MAX_CAPACITY + 1) }.isFailure)
        assertEquals(true, runCatching { SpriteQuads.meshData(0) }.isFailure)
    }

    @Test
    fun `mesh data lists the attributes it provides`() {
        val mesh =
            MeshData(
                positions = FloatArray(9),
                normals = FloatArray(9),
                colors = FloatArray(9),
                uvs = FloatArray(6),
                skinIndices = ShortArray(12),
                skinWeights = FloatArray(12),
                custom = listOf(CustomAttribute(2, 3, FloatArray(9))),
            )
        assertEquals(
            setOf(
                VertexSemantic.POSITION,
                VertexSemantic.TANGENTS,
                VertexSemantic.COLOR,
                VertexSemantic.UV0,
                VertexSemantic.BONE_INDICES,
                VertexSemantic.BONE_WEIGHTS,
                VertexSemantic.CUSTOM2,
            ),
            mesh.semantics,
        )
    }
}
