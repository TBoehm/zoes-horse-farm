package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.IndexFormat
import app.zoeshorsefarm.render.filament.mesh.MeshPacker
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryMappingTest {
    @Test
    fun `a box becomes positions normals uvs and indices`() {
        val box = BoxGeometry()
        val data = GeometryMapping.toMeshData(box)
        assertEquals(24, data.vertexCount)
        assertContentEquals(box.position.array, data.positions)
        assertContentEquals(box.normal.array, data.normals)
        assertContentEquals(box.uv.array, data.uvs)
        assertContentEquals(box.index, data.indices)
        assertEquals(
            setOf(VertexSemantic.POSITION, VertexSemantic.TANGENTS, VertexSemantic.UV0),
            data.semantics,
        )
    }

    @Test
    fun `colours with three or four floats are taken`() {
        val three = BoxGeometry()
        three.setAttribute("color", FloatAttribute(FloatArray(72) { 0.5f }, 3))
        assertEquals(3, GeometryMapping.toMeshData(three).colorComponents)
        val four = BoxGeometry()
        four.setAttribute("color", FloatAttribute(FloatArray(96) { 0.5f }, 4))
        val data = GeometryMapping.toMeshData(four)
        assertEquals(4, data.colorComponents)
        assertEquals(96, data.colors?.size)
    }

    @Test
    fun `an attribute with the wrong size is left out`() {
        val box = BoxGeometry()
        box.setAttribute("uv", FloatAttribute(FloatArray(10), 2))
        box.setAttribute("normal", FloatAttribute(FloatArray(10), 3))
        val data = GeometryMapping.toMeshData(box)
        assertNull(data.uvs)
        assertNull(data.normals)
    }

    @Test
    fun `skin indices and weights come together`() {
        val box = BoxGeometry()
        box.setAttribute("skinIndex", UShortAttribute(ShortArray(96), 4))
        assertNull(GeometryMapping.toMeshData(box).skinIndices)
        box.setAttribute("skinWeight", FloatAttribute(FloatArray(96), 4))
        val data = GeometryMapping.toMeshData(box)
        assertEquals(96, data.skinIndices?.size)
        assertEquals(96, data.skinWeights?.size)
        assertTrue(data.isSkinned)
    }

    @Test
    fun `the effect attributes land in their custom slots`() {
        val box = BoxGeometry()
        box.setAttribute("aRest", FloatAttribute(FloatArray(72), 3))
        box.setAttribute("aMat", FloatAttribute(FloatArray(96), 4))
        box.setAttribute("aFace", FloatAttribute(FloatArray(72), 3))
        val data = GeometryMapping.toMeshData(box)
        assertEquals(listOf(0, 1, 2), data.custom.map { it.slot })
        assertEquals(listOf(3, 4, 3), data.custom.map { it.components })
    }

    @Test
    fun `petal and flutter use slot zero`() {
        val flower = BoxGeometry()
        flower.setAttribute("petal", FloatAttribute(FloatArray(24), 1))
        assertEquals(listOf(0 to 1), GeometryMapping.toMeshData(flower).custom.map { it.slot to it.components })
        val bunting = BoxGeometry()
        bunting.setAttribute("aFlutter", FloatAttribute(FloatArray(72), 3))
        assertEquals(listOf(0 to 3), GeometryMapping.toMeshData(bunting).custom.map { it.slot to it.components })
    }

    @Test
    fun `two attributes for one slot keep the first`() {
        val box = BoxGeometry()
        box.setAttribute("petal", FloatAttribute(FloatArray(24), 1))
        box.setAttribute("aFlutter", FloatAttribute(FloatArray(72), 3))
        assertEquals(1, GeometryMapping.toMeshData(box).custom.size)
    }

    @Test
    fun `unknown attributes are not uploaded`() {
        val box = BoxGeometry()
        box.setAttribute("mystery", FloatAttribute(FloatArray(24), 1))
        assertTrue(GeometryMapping.toMeshData(box).custom.isEmpty())
        assertNull(GeometryMapping.customSlot("mystery"))
        assertEquals(2, GeometryMapping.customSlot("aFace"))
    }

    @Test
    fun `a geometry without positions cannot be drawn`() {
        assertFalse(GeometryMapping.isDrawable(Geometry()))
        assertFailsWith<IllegalArgumentException> { GeometryMapping.toMeshData(Geometry()) }
        assertTrue(GeometryMapping.isDrawable(PlaneGeometry()))
    }

    @Test
    fun `small meshes get 16 bit indices and big ones 32 bit`() {
        val small = MeshPacker.pack(GeometryMapping.toMeshData(BoxGeometry()))
        assertEquals(IndexFormat.USHORT, small.indices?.format)
        val count = 70000
        val big = Geometry()
        big.setAttribute("position", FloatAttribute(FloatArray(count * 3), 3))
        big.setIndex(IntArray(count) { it })
        assertEquals(IndexFormat.UINT, MeshPacker.pack(GeometryMapping.toMeshData(big)).indices?.format)
    }

    @Test
    fun `a geometry without an index draws consecutive vertices`() {
        val flat = BoxGeometry().toNonIndexed()
        val data = GeometryMapping.toMeshData(flat)
        assertNull(data.indices)
        assertEquals(36, data.vertexCount)
    }

    @Test
    fun `an index outside the vertices is refused`() {
        val broken = BoxGeometry()
        broken.setIndex(intArrayOf(0, 1, 99))
        assertFailsWith<IllegalArgumentException> { GeometryMapping.toMeshData(broken) }
    }
}
