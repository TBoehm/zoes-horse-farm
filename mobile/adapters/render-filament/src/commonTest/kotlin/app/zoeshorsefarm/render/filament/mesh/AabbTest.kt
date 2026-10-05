package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AabbTest {
    @Test
    fun `a new box is empty`() {
        assertTrue(Aabb().isEmpty)
    }

    @Test
    fun `setFromPositions fits the box and forgets the old one`() {
        val box = Aabb()
        box.expand(100f, 100f, 100f)
        box.setFromPositions(floatArrayOf(1f, 2f, 3f, -1f, 5f, 0f, 9f, 9f, 9f), 2)
        assertContentEquals(floatArrayOf(-1f, 2f, 0f), box.min)
        assertContentEquals(floatArrayOf(1f, 5f, 3f), box.max)
    }

    @Test
    fun `setFromPositions of no vertices is empty`() {
        val box = Aabb()
        box.expand(1f, 1f, 1f)
        box.setFromPositions(FloatArray(0), 0)
        assertTrue(box.isEmpty)
    }

    @Test
    fun `copyFrom copies the numbers and keeps the arrays apart`() {
        val source = Aabb()
        source.expand(1f, 2f, 3f)
        val target = Aabb()
        target.copyFrom(source)
        source.expand(5f, 5f, 5f)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), target.max)
    }

    @Test
    fun `reset empties the box`() {
        val box = Aabb()
        box.expand(0f, 0f, 0f)
        assertFalse(box.isEmpty)
        box.reset()
        assertTrue(box.isEmpty)
    }

    @Test
    fun `setFromSphere and inflate`() {
        val box = Aabb()
        box.setFromSphere(1f, 2f, 3f, 2f)
        assertContentEquals(floatArrayOf(-1f, 0f, 1f), box.min)
        assertContentEquals(floatArrayOf(3f, 4f, 5f), box.max)
        box.inflate(0.5f)
        assertEquals(-1.5f, box.min[0])
        assertEquals(5.5f, box.max[2])
    }
}
