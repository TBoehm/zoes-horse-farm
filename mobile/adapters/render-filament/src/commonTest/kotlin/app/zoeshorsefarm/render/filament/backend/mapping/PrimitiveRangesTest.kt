package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrimitiveRangesTest {
    @Test
    fun `one material draws the whole index`() {
        val box = BoxGeometry()
        assertEquals(listOf(PrimitiveRange(0, 36)), PrimitiveRanges.of(box, materialList = false))
    }

    @Test
    fun `one material ignores the groups`() {
        val box = BoxGeometry()
        box.addGroup(0, 12, 0)
        box.addGroup(12, 24, 1)
        assertEquals(listOf(PrimitiveRange(0, 36)), PrimitiveRanges.of(box, materialList = false))
    }

    @Test
    fun `a material list draws one range per group`() {
        val box = BoxGeometry()
        box.clearGroups()
        box.addGroup(0, 12, 0)
        box.addGroup(12, 24, 1)
        assertEquals(
            listOf(PrimitiveRange(0, 12, 0), PrimitiveRange(12, 24, 1)),
            PrimitiveRanges.of(box, materialList = true),
        )
    }

    @Test
    fun `a material list without groups draws everything with the first material`() {
        val box = BoxGeometry()
        box.clearGroups()
        assertEquals(listOf(PrimitiveRange(0, 36, 0)), PrimitiveRanges.of(box, materialList = true))
    }

    @Test
    fun `the draw range cuts the ranges`() {
        val box = BoxGeometry()
        box.setDrawRange(6, 12)
        assertEquals(listOf(PrimitiveRange(6, 12)), PrimitiveRanges.of(box, materialList = false))
        box.clearGroups()
        box.addGroup(0, 12, 0)
        box.addGroup(12, 24, 1)
        assertEquals(
            listOf(PrimitiveRange(6, 6, 0), PrimitiveRange(12, 6, 1)),
            PrimitiveRanges.of(box, materialList = true),
        )
    }

    @Test
    fun `ranges are cut down to whole triangles`() {
        val box = BoxGeometry()
        box.setDrawRange(0, 10)
        assertEquals(listOf(PrimitiveRange(0, 9)), PrimitiveRanges.of(box, materialList = false))
    }

    @Test
    fun `a draw range outside the geometry draws nothing`() {
        val box = BoxGeometry()
        box.setDrawRange(100, 10)
        assertTrue(PrimitiveRanges.of(box, materialList = false).isEmpty())
        box.setDrawRange(0, 0)
        assertTrue(PrimitiveRanges.of(box, materialList = false).isEmpty())
    }

    @Test
    fun `a geometry without an index uses the vertex count`() {
        val flat = BoxGeometry().toNonIndexed()
        assertEquals(listOf(PrimitiveRange(0, 36)), PrimitiveRanges.of(flat, materialList = false))
    }

    @Test
    fun `an unbounded draw range does not overflow`() {
        val box = BoxGeometry()
        box.setDrawRange(5, Int.MAX_VALUE)
        assertEquals(listOf(PrimitiveRange(5, 30)), PrimitiveRanges.of(box, materialList = false))
    }

    @Test
    fun `triangles add up over the ranges`() {
        assertEquals(10, PrimitiveRanges.triangles(listOf(PrimitiveRange(0, 12), PrimitiveRange(12, 18))))
    }
}
